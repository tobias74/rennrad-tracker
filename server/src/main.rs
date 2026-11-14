mod devices;
mod store;

use std::{
    convert::Infallible,
    env, io,
    path::PathBuf,
    sync::{Arc, Mutex},
};

use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, Path, State},
    http::{HeaderMap, StatusCode, header},
    response::{
        Html, IntoResponse, Response, Sse,
        sse::{Event, KeepAlive},
    },
    routing::{get, post},
};
use devices::{Enrollment, UNAUTHORIZED, fingerprint};
use serde_json::json;
use store::{Batch, Cursor, Store};
use tokio::sync::watch;
use uuid::Uuid;

#[derive(Clone)]
struct App {
    store: Arc<Mutex<Store>>,
    changed: watch::Sender<u64>,
}

type ApiResult<T> = Result<T, (StatusCode, &'static str)>;
const INTERNAL: (StatusCode, &str) = (
    StatusCode::INTERNAL_SERVER_ERROR,
    "Speichern fehlgeschlagen",
);
const NOT_FOUND: (StatusCode, &str) = (StatusCode::NOT_FOUND, "Link nicht verfügbar");

impl App {
    async fn with_store<T: Send + 'static>(
        &self,
        operation: impl FnOnce(&mut Store) -> ApiResult<T> + Send + 'static,
    ) -> ApiResult<T> {
        let store = self.store.clone();
        tokio::task::spawn_blocking(move || {
            let mut store = store.lock().map_err(|_| INTERNAL)?;
            operation(&mut store)
        })
        .await
        .map_err(|_| INTERNAL)?
    }

    fn notify(&self) {
        self.changed
            .send_modify(|version| *version = version.wrapping_add(1));
    }
}

fn bearer(headers: &HeaderMap) -> ApiResult<&str> {
    headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "))
        .filter(|value| (32..=1024).contains(&value.len()))
        .ok_or(UNAUTHORIZED)
}

fn credential(headers: &HeaderMap) -> ApiResult<String> {
    bearer(headers).map(fingerprint)
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let owner_key = env::var("TRACKER_OWNER_KEY")?;
    if owner_key.len() < 32 || !owner_key.bytes().all(|b| b.is_ascii_graphic()) {
        return Err("TRACKER_OWNER_KEY must contain at least 32 visible ASCII characters".into());
    }
    let data = env::var_os("TRACKER_DATA")
        .map(PathBuf::from)
        .unwrap_or_else(|| "data".into());
    let address = env::var("TRACKER_BIND").unwrap_or_else(|_| "127.0.0.1:8787".into());
    let app = App {
        store: Arc::new(Mutex::new(Store::open(data, &owner_key)?)),
        changed: watch::channel(0).0,
    };
    let routes = Router::new()
        .route(
            "/",
            get(|| async { Html(include_str!("../web/index.html")) }),
        )
        .route("/health", get(|| async { "ok" }))
        .route("/impressum", get(|| async { legal_page(false, false) }))
        .route("/datenschutz", get(|| async { legal_page(true, false) }))
        .route("/en/imprint", get(|| async { legal_page(false, true) }))
        .route("/en/privacy", get(|| async { legal_page(true, true) }))
        .route("/f/{token}", get(family_page))
        .route("/api/family/{token}/events", get(family_events))
        .route("/api/family/{token}/rides", get(family_rides))
        .route("/api/owner/link", get(get_link).post(rotate_link))
        .route(
            "/api/owner/devices",
            get(list_devices).post(register_primary),
        )
        .route(
            "/api/owner/devices/{id}",
            axum::routing::delete(remove_device),
        )
        .route("/api/owner/pair", post(approve_pair))
        .route("/api/pair/claim", post(claim_pair))
        .route("/api/owner/rides/{id}", post(upload))
        .route("/app-v2.js", get(legacy_app))
        .route("/app.js", get(legacy_app))
        .route(
            "/app-v3.js",
            get(|| async {
                asset(
                    "text/javascript; charset=utf-8",
                    include_str!("../web/app.js"),
                )
            }),
        )
        .route(
            "/i18n.js",
            get(|| async {
                asset(
                    "text/javascript; charset=utf-8",
                    include_str!("../web/i18n.js"),
                )
            }),
        )
        .route(
            "/landing.js",
            get(|| async {
                asset(
                    "text/javascript; charset=utf-8",
                    include_str!("../web/landing.js"),
                )
            }),
        )
        .route(
            "/road-cycling.webp",
            get(|| async {
                asset(
                    "image/webp",
                    &include_bytes!("../web/road-cycling.webp")[..],
                )
            }),
        )
        .route(
            "/style.css",
            get(|| async { asset("text/css; charset=utf-8", include_str!("../web/style.css")) }),
        )
        .route(
            "/leaflet.js",
            get(|| async {
                asset(
                    "text/javascript; charset=utf-8",
                    include_str!("../web/vendor/leaflet.js"),
                )
            }),
        )
        .route(
            "/leaflet.css",
            get(|| async {
                asset(
                    "text/css; charset=utf-8",
                    include_str!("../web/vendor/leaflet.css"),
                )
            }),
        )
        .layer(DefaultBodyLimit::max(128 * 1024))
        .layer(axum::middleware::map_response(security_headers))
        .with_state(app);
    let listener = tokio::net::TcpListener::bind(&address).await?;
    println!(
        "Rennrad-Tracker läuft auf http://{}",
        listener.local_addr()?
    );
    axum::serve(listener, routes).await?;
    Ok(())
}

async fn legacy_app() -> impl IntoResponse {
    asset(
        "text/javascript; charset=utf-8",
        concat!(
            include_str!("../web/i18n.js"),
            "\n",
            include_str!("../web/app.js")
        ),
    )
}

fn asset(mime: &'static str, content: impl IntoResponse) -> impl IntoResponse {
    (
        [
            (header::CONTENT_TYPE, mime),
            (header::CACHE_CONTROL, "public, max-age=3600"),
        ],
        content,
    )
}

fn legal_page(privacy: bool, english: bool) -> Html<String> {
    let (title, content) = match (privacy, english) {
        (false, false) => ("Impressum", include_str!("../web/legal/imprint.de.html")),
        (false, true) => ("Imprint", include_str!("../web/legal/imprint.en.html")),
        (true, false) => (
            "Datenschutzerklärung",
            include_str!("../web/legal/privacy.de.html"),
        ),
        (true, true) => (
            "Privacy Policy",
            include_str!("../web/legal/privacy.en.html"),
        ),
    };
    let (german_path, english_path) = if privacy {
        ("/datenschutz", "/en/privacy")
    } else {
        ("/impressum", "/en/imprint")
    };
    let current = " aria-current=\"page\"";
    Html(
        include_str!("../web/legal.html")
            .replace("{{language}}", if english { "en" } else { "de" })
            .replace(
                "{{language_label}}",
                if english { "Language" } else { "Sprache" },
            )
            .replace("{{title}}", title)
            .replace("{{german_path}}", german_path)
            .replace("{{english_path}}", english_path)
            .replace("{{german_current}}", if english { "" } else { current })
            .replace("{{english_current}}", if english { current } else { "" })
            .replace("{{content}}", content),
    )
}

async fn security_headers(mut response: Response) -> Response {
    let headers = response.headers_mut();
    headers
        .entry(header::CACHE_CONTROL)
        .or_insert("no-store".parse().unwrap());
    headers.insert(
        header::REFERRER_POLICY,
        "strict-origin-when-cross-origin".parse().unwrap(),
    );
    headers.insert(header::X_CONTENT_TYPE_OPTIONS, "nosniff".parse().unwrap());
    headers.insert(header::CONTENT_SECURITY_POLICY, "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' https://tile.openstreetmap.org data:; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'".parse().unwrap());
    response
}

async fn family_page(State(app): State<App>, Path(token): Path<Uuid>) -> Response {
    match app.with_store(move |store| Ok(store.permits(token))).await {
        Ok(true) => Html(include_str!("../web/family.html")).into_response(),
        Ok(false) => (
            StatusCode::NOT_FOUND,
            Html(include_str!("../web/revoked.html")),
        )
            .into_response(),
        Err(error) => error.into_response(),
    }
}

async fn get_link(
    State(app): State<App>,
    headers: HeaderMap,
) -> ApiResult<Json<serde_json::Value>> {
    let key = credential(&headers)?;
    app.with_store(move |store| {
        let (id, recovery) = store.devices.auth(&key)?;
        Ok(Json(
            json!({ "path": store.link(), "device": id, "recovery": recovery }),
        ))
    })
    .await
}

async fn rotate_link(
    State(app): State<App>,
    headers: HeaderMap,
) -> ApiResult<Json<serde_json::Value>> {
    let key = credential(&headers)?;
    let path = app
        .with_store(move |store| {
            store.devices.auth(&key)?;
            store.rotate().map_err(storage_error)
        })
        .await?;
    app.notify();
    Ok(Json(json!({ "path": path })))
}

async fn upload(
    State(app): State<App>,
    Path(id): Path<Uuid>,
    headers: HeaderMap,
    Json(batch): Json<Batch>,
) -> ApiResult<StatusCode> {
    let key = credential(&headers)?;
    batch
        .validate()
        .map_err(|message| (StatusCode::BAD_REQUEST, message))?;
    let changed = app
        .with_store(move |store| {
            let (device, _) = store.devices.auth(&key)?;
            store
                .upload(device, id, batch)
                .map_err(|error| match error {
                    store::UploadError::Conflict(message) => (StatusCode::CONFLICT, message),
                    store::UploadError::Storage(error) => storage_error(error),
                })
        })
        .await?;
    if changed {
        app.notify();
    }
    Ok(StatusCode::NO_CONTENT)
}

async fn list_devices(
    State(app): State<App>,
    headers: HeaderMap,
) -> ApiResult<Json<serde_json::Value>> {
    let key = credential(&headers)?;
    app.with_store(move |store| {
        let (current, _) = store.devices.auth(&key)?;
        let devices: Vec<_> = store
            .devices
            .list()
            .map(|(id, name)| json!({ "id": id, "name": name }))
            .collect();
        Ok(Json(json!({ "current": current, "devices": devices })))
    })
    .await
}

async fn register_primary(
    State(app): State<App>,
    headers: HeaderMap,
    Json(mut enrollment): Json<Enrollment>,
) -> ApiResult<StatusCode> {
    let key = credential(&headers)?;
    enrollment.validate()?;
    app.with_store(move |store| {
        if !store.devices.auth(&key)?.1 {
            return Err((
                StatusCode::FORBIDDEN,
                "SSH-Wiederherstellungsschlüssel erforderlich",
            ));
        }
        store.devices.register_primary(enrollment)
    })
    .await?;
    app.notify();
    Ok(StatusCode::NO_CONTENT)
}

async fn remove_device(
    State(app): State<App>,
    headers: HeaderMap,
    Path(id): Path<Uuid>,
) -> ApiResult<StatusCode> {
    let key = credential(&headers)?;
    app.with_store(move |store| {
        let (current, recovery) = store.devices.auth(&key)?;
        if !recovery && current == id {
            return Err((
                StatusCode::CONFLICT,
                "Dieses Handy kann sich nicht selbst entfernen",
            ));
        }
        store.devices.revoke(id)
    })
    .await?;
    app.notify();
    Ok(StatusCode::NO_CONTENT)
}

async fn approve_pair(
    State(app): State<App>,
    headers: HeaderMap,
    Json(mut enrollment): Json<Enrollment>,
) -> ApiResult<StatusCode> {
    let key = credential(&headers)?;
    enrollment.validate()?;
    app.with_store(move |store| {
        let (device, _) = store.devices.auth(&key)?;
        store.devices.approve(enrollment, device)
    })
    .await?;
    Ok(StatusCode::NO_CONTENT)
}

async fn claim_pair(
    State(app): State<App>,
    headers: HeaderMap,
) -> ApiResult<Json<serde_json::Value>> {
    let token = bearer(&headers)?;
    if !devices::valid_secret(token) {
        return Err(UNAUTHORIZED);
    }
    let key = fingerprint(token);
    app.with_store(move |store| {
        store.devices.claim(key)?;
        Ok(Json(json!({ "path": store.link() })))
    })
    .await
}

fn storage_error(error: io::Error) -> (StatusCode, &'static str) {
    eprintln!("Storage error: {error}");
    INTERNAL
}

async fn family_events(State(app): State<App>, Path(token): Path<Uuid>) -> impl IntoResponse {
    family_stream(app, token, false)
}

async fn family_rides(State(app): State<App>, Path(token): Path<Uuid>) -> impl IntoResponse {
    family_stream(app, token, true)
}

fn family_stream(app: App, token: Uuid, multi: bool) -> impl IntoResponse {
    let mut changes = app.changed.subscribe();
    let stream = async_stream::stream! {
        let mut cursor = Cursor::default();
        loop {
            changes.borrow_and_update();
            match app.with_store(move |store| {
                if !store.permits(token) { return Err(NOT_FOUND); }
                let event = store.update(&mut cursor, multi);
                Ok((cursor, event))
            }).await {
                Ok((next, update)) => {
                    cursor = next;
                    if let Some(update) = update {
                        yield Ok::<_, Infallible>(Event::default().event(if multi { "rides" } else { "ride" }).json_data(update).unwrap());
                    }
                }
                Err(NOT_FOUND) => {
                    yield Ok(Event::default().event("revoked").data("{}"));
                    break;
                }
                Err(_) => break,
            }
            if changes.changed().await.is_err() { break; }
        }
    };
    (
        [("X-Accel-Buffering", "no")],
        Sse::new(stream).keep_alive(KeepAlive::default()),
    )
}
