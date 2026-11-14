use std::{
    collections::BTreeMap,
    fs::{self, File, OpenOptions},
    io::{self, BufRead, BufReader, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
};

use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::devices::{Devices, PRIMARY};

#[derive(Clone, Serialize, Deserialize)]
pub struct Point {
    seq: usize,
    time: u64,
    lat: f64,
    lon: f64,
    speed: Option<f64>,
    bearing: Option<f64>,
    altitude: Option<f64>,
    accuracy: Option<f64>,
    battery: Option<u8>,
}

#[derive(Deserialize)]
pub struct Batch {
    started: u64,
    points: Vec<Point>,
    ended: Option<u64>,
}

impl Batch {
    pub fn validate(&self) -> Result<(), &'static str> {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;
        let latest = now + 600_000;
        if self.started == 0
            || self.started > latest
            || self.points.len() > 250
            || self
                .ended
                .is_some_and(|end| end < self.started || end > latest)
            || self.points.iter().any(|p| {
                p.time < self.started
                    || p.time > self.ended.unwrap_or(latest)
                    || !(-90.0..=90.0).contains(&p.lat)
                    || !(-180.0..=180.0).contains(&p.lon)
                    || p.speed.is_some_and(|v| v < 0.0)
                    || p.accuracy.is_some_and(|v| v < 0.0)
                    || p.bearing.is_some_and(|v| !(0.0..360.0).contains(&v))
                    || p.battery.is_some_and(|v| v > 100)
            })
            || self.points.windows(2).any(|pair| {
                pair[0].seq.checked_add(1) != Some(pair[1].seq) || pair[0].time > pair[1].time
            })
        {
            return Err("Ungültige Fahrtdaten");
        }
        Ok(())
    }
}

#[derive(Serialize, Deserialize)]
struct Entry {
    points: Vec<Point>,
    ended: Option<u64>,
}

#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
struct RideKey {
    started: u64,
    id: Uuid,
    device: Uuid,
}

impl RideKey {
    fn filename(self) -> String {
        if self.device == PRIMARY {
            format!("{}-{}.ndjson", self.started, self.id)
        } else {
            format!("{}-{}.{}.ndjson", self.started, self.id, self.device)
        }
    }

    fn from_filename(filename: &str) -> Option<Self> {
        let (started, suffix) = filename.strip_suffix(".ndjson")?.split_once('-')?;
        let (id, device) = match suffix.split_once('.') {
            Some((id, device)) => (id, device.parse().ok()?),
            None => (suffix, PRIMARY),
        };
        Some(Self {
            started: started.parse().ok()?,
            id: id.parse().ok()?,
            device,
        })
    }
}

struct Ride {
    key: RideKey,
    bytes: u64,
    points: Vec<Point>,
    ended: Option<u64>,
}

impl Ride {
    fn load(path: &Path, key: RideKey) -> io::Result<Self> {
        let mut ride = Self {
            key,
            bytes: 0,
            points: Vec::new(),
            ended: None,
        };
        let file = match File::open(path) {
            Ok(file) => file,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(ride),
            Err(error) => return Err(error),
        };
        let mut reader = BufReader::new(file);
        let mut line = Vec::new();
        loop {
            line.clear();
            let bytes = reader.read_until(b'\n', &mut line)?;
            // An interrupted append can leave an incomplete final record.
            if line.last() != Some(&b'\n') {
                break;
            }
            let entry: Entry = serde_json::from_slice(&line)?;
            ride.bytes += bytes as u64;
            ride.points.extend(entry.points);
            ride.ended = entry.ended;
        }
        Ok(ride)
    }

    fn append(&mut self, path: &Path, batch: Batch) -> Result<bool, UploadError> {
        let points: Vec<_> = batch
            .points
            .into_iter()
            .skip_while(|p| p.seq < self.points.len())
            .collect();
        if points.first().is_some_and(|point| {
            point.seq != self.points.len()
                || self
                    .points
                    .last()
                    .is_some_and(|last| last.time > point.time)
        }) || batch
            .ended
            .is_some_and(|end| self.points.last().is_some_and(|p| p.time > end))
        {
            return Err(UploadError::Conflict("Punktfolge ungültig"));
        }
        if self.ended.is_some()
            && (!points.is_empty() || batch.ended.is_some_and(|end| Some(end) != self.ended))
        {
            return Err(UploadError::Conflict("Fahrt bereits beendet"));
        }
        let ended = self.ended.or(batch.ended);
        if self.bytes > 0 && points.is_empty() && ended == self.ended {
            return Ok(false);
        }

        let entry = Entry { points, ended };
        let mut line = serde_json::to_vec(&entry).map_err(io::Error::from)?;
        line.push(b'\n');
        let mut file = OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(false)
            .open(path)?;
        file.set_len(self.bytes)?;
        file.seek(SeekFrom::Start(self.bytes))?;
        file.write_all(&line)?;
        file.sync_all()?;
        if self.bytes == 0 {
            sync_directory(path.parent().unwrap())?;
        }

        self.bytes += line.len() as u64;
        self.points.extend(entry.points);
        self.ended = entry.ended;
        Ok(true)
    }
}

pub enum UploadError {
    Conflict(&'static str),
    Storage(io::Error),
}

impl From<io::Error> for UploadError {
    fn from(error: io::Error) -> Self {
        Self::Storage(error)
    }
}

pub struct Store {
    directory: PathBuf,
    token: Uuid,
    latest: BTreeMap<Uuid, Ride>,
    pub devices: Devices,
    _lock: File,
}

impl Store {
    pub fn open(directory: PathBuf, owner_key: &str) -> io::Result<Self> {
        fs::create_dir_all(&directory)?;
        let lock = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .open(directory.join("tracker.lock"))?;
        fs2::FileExt::try_lock_exclusive(&lock)?;
        let token = match fs::read(directory.join("family.json")) {
            Ok(data) => serde_json::from_slice(&data)?,
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                let token = Uuid::new_v4();
                write_json(&directory.join("family.json"), &token)?;
                token
            }
            Err(error) => return Err(error),
        };
        let mut keys = Vec::new();
        for entry in fs::read_dir(&directory)? {
            if let Some(key) = entry?.file_name().to_str().and_then(RideKey::from_filename) {
                keys.push(key);
            }
        }
        keys.sort_unstable();
        let mut latest = BTreeMap::new();
        for key in keys.into_iter().rev() {
            if latest.contains_key(&key.device) {
                continue;
            }
            let ride = Ride::load(&directory.join(key.filename()), key)?;
            if ride.bytes > 0 {
                latest.insert(key.device, ride);
            }
        }
        let devices = Devices::load(&directory, owner_key)?;
        Ok(Self {
            directory,
            token,
            latest,
            devices,
            _lock: lock,
        })
    }

    pub fn link(&self) -> String {
        format!("/f/{}", self.token)
    }
    pub fn permits(&self, token: Uuid) -> bool {
        self.token == token
    }

    pub fn rotate(&mut self) -> io::Result<String> {
        let token = Uuid::new_v4();
        write_json(&self.directory.join("family.json"), &token)?;
        self.token = token;
        Ok(self.link())
    }

    pub fn upload(&mut self, device: Uuid, id: Uuid, batch: Batch) -> Result<bool, UploadError> {
        let key = RideKey {
            id,
            started: batch.started,
            device,
        };
        let path = self.directory.join(key.filename());
        if let Some(ride) = self.latest.get_mut(&device).filter(|ride| ride.key == key) {
            return ride.append(&path, batch);
        }
        let mut ride = Ride::load(&path, key)?;
        ride.append(&path, batch)?;
        if self
            .latest
            .get(&device)
            .is_none_or(|current| current.key < key)
        {
            self.latest.insert(device, ride);
            return Ok(true);
        }
        Ok(false)
    }

    pub fn update(&self, cursor: &mut Cursor, multi: bool) -> Option<serde_json::Value> {
        let initial = !cursor.sent;
        cursor.sent = true;
        if multi {
            let rides: Vec<_> = self
                .latest
                .values()
                .filter_map(|ride| self.ride_update(cursor, ride))
                .collect();
            return (initial || !rides.is_empty()).then(|| serde_json::json!({ "rides": rides }));
        }
        match self.latest.values().max_by_key(|ride| ride.key) {
            Some(ride) => {
                cursor.rides.retain(|device, _| *device == ride.key.device);
                self.ride_update(cursor, ride)
                    .map(|ride| serde_json::json!({ "ride": ride }))
            }
            None => initial.then(|| serde_json::json!({ "ride": null })),
        }
    }

    fn ride_update<'a>(&'a self, cursor: &mut Cursor, ride: &'a Ride) -> Option<RideUpdate<'a>> {
        let name = self.devices.name(ride.key.device);
        let removed = self.devices.removed(ride.key.device);
        let prior = cursor
            .rides
            .get(&ride.key.device)
            .filter(|prior| prior.key == ride.key);
        if prior.is_some_and(|prior| {
            prior.count == ride.points.len()
                && prior.ended == ride.ended
                && prior.name == name
                && prior.removed == removed
        }) {
            return None;
        }
        let update = RideUpdate {
            id: ride.key.id,
            device: ride.key.device,
            name,
            default_name: !self.devices.has_name(ride.key.device),
            started: ride.key.started,
            ended: ride.ended,
            removed,
            reset: prior.is_none(),
            points: &ride.points[prior.map_or(0, |prior| prior.count)..],
        };
        cursor.rides.insert(
            ride.key.device,
            RideCursor {
                key: ride.key,
                count: ride.points.len(),
                ended: ride.ended,
                name: name.to_owned(),
                removed,
            },
        );
        Some(update)
    }
}

pub fn write_json(path: &Path, value: &impl Serialize) -> io::Result<()> {
    let temp = path.with_extension("tmp");
    let mut file = File::create(&temp)?;
    serde_json::to_writer(&mut file, value)?;
    file.sync_all()?;
    drop(file);
    fs::rename(temp, path)?;
    sync_directory(path.parent().unwrap())
}

#[cfg(unix)]
fn sync_directory(directory: &Path) -> io::Result<()> {
    File::open(directory)?.sync_all()
}

#[cfg(not(unix))]
fn sync_directory(_directory: &Path) -> io::Result<()> {
    Ok(())
}

#[derive(Default)]
pub struct Cursor {
    sent: bool,
    rides: BTreeMap<Uuid, RideCursor>,
}

struct RideCursor {
    key: RideKey,
    count: usize,
    ended: Option<u64>,
    name: String,
    removed: bool,
}

#[derive(Serialize)]
struct RideUpdate<'a> {
    id: Uuid,
    device: Uuid,
    name: &'a str,
    default_name: bool,
    started: u64,
    ended: Option<u64>,
    removed: bool,
    reset: bool,
    points: &'a [Point],
}
