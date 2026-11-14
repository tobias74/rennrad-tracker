use std::{
    fs, io,
    path::{Path, PathBuf},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use uuid::Uuid;

use crate::store::write_json;

pub const PRIMARY: Uuid = Uuid::nil();
pub type Issue = (axum::http::StatusCode, &'static str);
use axum::http::StatusCode;

pub const UNAUTHORIZED: Issue = (StatusCode::UNAUTHORIZED, "Zugangsschlüssel ungültig");
const EXPIRED: Issue = (StatusCode::GONE, "Kopplungscode abgelaufen");
const WAIT: Issue = (StatusCode::ACCEPTED, "Wartet auf Bestätigung");
const LIMIT: Issue = (
    StatusCode::TOO_MANY_REQUESTS,
    "Zu viele Geräte oder Kopplungen",
);
const LIFETIME_MS: u64 = 300_000;

pub fn fingerprint(key: &str) -> String {
    format!("{:x}", Sha256::digest(key.as_bytes()))
}

#[derive(Clone, Deserialize, Serialize)]
pub struct Device {
    pub id: Uuid,
    pub name: String,
    digest: String,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    revoked: bool,
}

#[derive(Deserialize)]
pub struct Enrollment {
    pub challenge: String,
    pub name: String,
    #[serde(default)]
    pub expires: u64,
}

impl Enrollment {
    pub fn validate(&mut self) -> Result<(), Issue> {
        self.name = self.name.trim().to_owned();
        if !valid_secret(&self.challenge)
            || self.name.is_empty()
            || self.name.chars().count() > 48
            || self.name.chars().any(|c| {
                c.is_control() || matches!(c, '\u{202a}'..='\u{202e}' | '\u{2066}'..='\u{2069}')
            })
        {
            return Err((StatusCode::BAD_REQUEST, "Ungültige Kopplungsdaten"));
        }
        Ok(())
    }
}

pub fn valid_secret(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

struct Pending {
    enrollment: Enrollment,
    expires: Instant,
    approved_by: Uuid,
}

pub struct Devices {
    file: PathBuf,
    registered: Vec<Device>,
    pending: Vec<Pending>,
    root: String,
}

impl Devices {
    pub fn load(directory: &Path, root: &str) -> io::Result<Self> {
        let file = directory.join("devices.json");
        let registered = match fs::read(&file) {
            Ok(bytes) => serde_json::from_slice(&bytes)?,
            Err(error) if error.kind() == io::ErrorKind::NotFound => Vec::new(),
            Err(error) => return Err(error),
        };
        Ok(Self {
            file,
            registered,
            pending: Vec::new(),
            root: fingerprint(root),
        })
    }

    pub fn auth(&self, digest: &str) -> Result<(Uuid, bool), Issue> {
        if bool::from(self.root.as_bytes().ct_eq(digest.as_bytes())) {
            return Ok((PRIMARY, true));
        }
        self.find(digest)
            .filter(|device| !device.revoked)
            .map(|device| (device.id, false))
            .ok_or(UNAUTHORIZED)
    }

    fn find(&self, digest: &str) -> Option<&Device> {
        self.registered
            .iter()
            .find(|device| bool::from(device.digest.as_bytes().ct_eq(digest.as_bytes())))
    }

    pub fn name(&self, id: Uuid) -> &str {
        self.registered
            .iter()
            .find(|dev| dev.id == id)
            .map_or("Erstes Handy", |dev| &dev.name)
    }

    pub fn has_name(&self, id: Uuid) -> bool {
        self.registered.iter().any(|device| device.id == id)
    }

    pub fn removed(&self, id: Uuid) -> bool {
        self.registered
            .iter()
            .any(|dev| dev.id == id && dev.revoked)
    }

    pub fn list(&self) -> impl Iterator<Item = (Uuid, &str)> {
        self.registered
            .iter()
            .filter(|dev| !dev.revoked)
            .map(|dev| (dev.id, dev.name.as_str()))
    }

    pub fn register_primary(&mut self, enrollment: Enrollment) -> Result<(), Issue> {
        if enrollment.challenge == self.root
            || self
                .find(&enrollment.challenge)
                .is_some_and(|dev| dev.id != PRIMARY)
        {
            return Err((StatusCode::CONFLICT, "Geräteschlüssel bereits registriert"));
        }
        let mut next = self.registered.clone();
        next.retain(|dev| dev.id != PRIMARY);
        next.push(Device {
            id: PRIMARY,
            name: enrollment.name,
            digest: enrollment.challenge,
            revoked: false,
        });
        self.save(next)
    }

    pub fn approve(&mut self, enrollment: Enrollment, approved_by: Uuid) -> Result<(), Issue> {
        self.prune();
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;
        let remaining = enrollment.expires.saturating_sub(now);
        if remaining == 0 || remaining > LIFETIME_MS + 60_000 {
            return Err(EXPIRED);
        }
        if enrollment.challenge == self.root
            || self
                .find(&enrollment.challenge)
                .is_some_and(|device| device.revoked)
        {
            return Err(EXPIRED);
        }
        if self.find(&enrollment.challenge).is_some() {
            return Ok(());
        }
        if self
            .pending
            .iter()
            .any(|p| p.enrollment.challenge == enrollment.challenge)
        {
            return Ok(());
        }
        if self.list().count() + self.pending.len() >= 64 || self.pending.len() >= 12 {
            return Err(LIMIT);
        }
        self.pending.push(Pending {
            enrollment,
            expires: Instant::now() + Duration::from_millis(remaining.min(LIFETIME_MS)),
            approved_by,
        });
        Ok(())
    }

    pub fn claim(&mut self, digest: String) -> Result<(), Issue> {
        self.prune();
        if let Some(device) = self.find(&digest) {
            return if device.revoked {
                Err(UNAUTHORIZED)
            } else {
                Ok(())
            };
        }
        let index = self
            .pending
            .iter()
            .position(|p| bool::from(p.enrollment.challenge.as_bytes().ct_eq(digest.as_bytes())))
            .ok_or(WAIT)?;
        let mut next = self.registered.clone();
        next.push(Device {
            id: Uuid::new_v4(),
            name: self.pending[index].enrollment.name.clone(),
            digest,
            revoked: false,
        });
        self.save(next)?;
        self.pending.swap_remove(index);
        Ok(())
    }

    pub fn revoke(&mut self, id: Uuid) -> Result<(), Issue> {
        let mut next = self.registered.clone();
        let device = next
            .iter_mut()
            .find(|device| device.id == id && !device.revoked)
            .ok_or((StatusCode::NOT_FOUND, "Gerät nicht gefunden"))?;
        device.revoked = true;
        self.save(next)?;
        self.pending.retain(|pending| pending.approved_by != id);
        Ok(())
    }

    fn prune(&mut self) {
        let now = Instant::now();
        self.pending.retain(|p| p.expires > now);
    }

    fn save(&mut self, registered: Vec<Device>) -> Result<(), Issue> {
        write_json(&self.file, &registered).map_err(crate::storage_error)?;
        self.registered = registered;
        Ok(())
    }
}
