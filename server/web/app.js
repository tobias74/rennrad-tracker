'use strict';

const element = id => document.getElementById(id);
const { t } = trackerI18n;
let number;
let clock;
const GAP = 30_000;
const colors = ['#086544', '#2357b0', '#a84b0c', '#8a3b87', '#a22d4a', '#006d78'];
const map = L.map('map', { attributionControl: false, preferCanvas: true, zoomControl: false });
const zoom = L.control.zoom().addTo(map);
L.control.attribution({ prefix: false }).addTo(map);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 19,
  attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
}).addTo(map);

const rides = new Map();
let selected = null;
let following = true;
let connected = false;
let received = false;
let revoked = false;
let pickerSignature;
let renderedView;
let emptyMessage = ['connecting', 'loading'];

function translateMap() {
  number = new Intl.NumberFormat(trackerI18n.language, { maximumFractionDigits: 1 });
  clock = new Intl.DateTimeFormat(trackerI18n.language, { dateStyle: 'short', timeStyle: 'medium' });
  for (const [selector, key] of [['.leaflet-control-zoom-in', 'zoomIn'], ['.leaflet-control-zoom-out', 'zoomOut']]) {
    const button = zoom.getContainer().querySelector(selector);
    button.title = t(key);
    button.setAttribute('aria-label', t(key));
  }
  for (const ride of rides.values()) {
    const name = rideName(ride);
    if (ride.marker) Object.assign(ride.marker.options, { title: name, alt: name });
    labelMarker(ride);
  }
  if (!element('empty').hidden) showEmpty(...emptyMessage);
  updateFitLabel();
  if (revoked) status('ended', 'renewed');
  else renderStats();
}

function setText(node, text) {
  if (node.textContent !== text) node.textContent = text;
}

function status(state, key, values) {
  const badge = element('status');
  badge.dataset.state = state;
  setText(badge, t(key, values));
}

function showEmpty(title, description) {
  emptyMessage = [title, description];
  element('map').setAttribute('aria-hidden', 'true');
  element('empty-title').textContent = t(title);
  element('empty-description').textContent = t(description);
  element('empty').hidden = false;
  element('map-actions').hidden = true;
}

function setFollowing(enabled) {
  following = enabled;
  element('follow').setAttribute('aria-pressed', String(enabled));
}

function format(value, unit) {
  return value == null ? '–' : number.format(value) + '\u00a0' + unit;
}

function duration(ms) {
  const minutes = Math.floor(Math.max(0, ms) / 60_000);
  const hours = Math.floor(minutes / 60);
  return hours ? hours + ' h ' + minutes % 60 + ' min' : minutes + ' min';
}

function finished(ride) { return ride.ended != null || ride.removed; }
function ageOf(ride) { return ride?.last ? Math.max(0, Date.now() - ride.last.time) : null; }
function rideName(ride) { return ride.name || t('firstPhone'); }
function shortStatus(ride) {
  if (ride.removed) return 'removed';
  if (finished(ride)) return 'endedShort';
  if (ageOf(ride) >= GAP || (!ride.last && Date.now() - ride.started >= GAP)) return 'stale';
  return ride.last ? 'live' : 'waiting';
}

function visibleRides() {
  if (selected) return [rides.get(selected)].filter(Boolean);
  return [...rides.values()];
}

function detailRide() {
  return selected ? rides.get(selected) : rides.size === 1 ? rides.values().next().value : undefined;
}

function renderPicker() {
  const picker = element('ride-picker');
  picker.hidden = rides.size < 2 || revoked;
  if (picker.hidden) {
    element('ride-choices').replaceChildren();
    pickerSignature = undefined;
    return;
  }
  const ordered = [...rides.values()];
  const signature = JSON.stringify(ordered.map(ride => [ride.device, rideName(ride)]));
  if (signature !== pickerSignature) {
    pickerSignature = signature;
    const choices = ordered.map(ride => {
      const button = document.createElement('button');
      button.type = 'button';
      const dot = document.createElement('span');
      dot.className = 'ride-color';
      dot.style.backgroundColor = ride.color;
      dot.setAttribute('aria-hidden', 'true');
      const copy = document.createElement('span');
      const name = document.createElement('strong');
      name.textContent = rideName(ride);
      const state = document.createElement('small');
      copy.append(name, state);
      button.append(dot, copy);
      button.addEventListener('click', () => selectRide(ride.device));
      return button;
    });
    element('ride-choices').replaceChildren(...choices);
  }
  element('all-rides').setAttribute('aria-pressed', String(selected === null));
  ordered.forEach((ride, index) => {
    const button = element('ride-choices').children[index];
    button.setAttribute('aria-pressed', String(selected === ride.device));
    setText(button.querySelector('small'), t(shortStatus(ride)));
  });
}

function renderStats() {
  if (revoked) return;
  const ride = detailRide();
  const last = ride?.last;
  const age = ageOf(ride);
  const live = [...rides.values()].filter(item => shortStatus(item) === 'live').length;
  if (!connected) status(received ? 'offline' : 'waiting', received ? 'offline' : 'connecting');
  else if (!rides.size) status('waiting', 'noRide');
  else if (!ride) status(live ? 'live' : 'waiting', live ? 'liveCount' : 'rideCount', { count: live || rides.size });
  else if (ride.removed) status('ended', 'removed');
  else if (finished(ride)) status('ended', 'ended');
  else if (shortStatus(ride) === 'stale') status('stale', 'stale');
  else if (!last) status('waiting', 'waiting');
  else status('live', 'live');

  for (const item of rides.values()) {
    item.marker?.getElement()?.classList.toggle('stale', !connected || shortStatus(item) !== 'live');
  }
  renderPicker();
  element('telemetry').hidden = !ride && rides.size > 1;
  setText(element('speed'), format(last?.speed == null ? null : Math.round(last.speed * 3.6), 'km/h'));
  setText(element('battery'), format(last?.battery, '%'));
  setText(element('age'), age == null ? '–' : age < 60_000
    ? t('secondsAgo', { seconds: Math.floor(age / 1000) }) : t('durationAgo', { duration: duration(age) }));
  setText(element('distance'), format(last ? ride.distance / 1000 : null, 'km'));
  setText(element('duration'), ride ? duration((ride.ended ?? (ride.removed ? last?.time ?? ride.started : Date.now())) - ride.started) : '–');
  setText(element('altitude'), format(last?.altitude == null ? null : Math.round(last.altitude), 'm'));
  setText(element('accuracy'), format(last?.accuracy == null ? null : Math.round(last.accuracy), 'm'));
  setText(element('bearing'), last?.bearing == null ? '–' : Math.round(last.bearing) % 360 + '°');
  setText(element('timestamp'), last ? clock.format(last.time) : '–');
}

function updateRide(incoming) {
  let ride = rides.get(incoming.device);
  if (!ride || incoming.reset || incoming.id !== ride.id || incoming.started !== ride.started) {
    ride?.layers.remove();
    ride = {
      layers: L.featureGroup(), route: L.featureGroup(), color: ride?.color ?? colors[rides.size % colors.length],
      distance: 0, device: incoming.device,
    };
    ride.route.addTo(ride.layers);
    rides.set(incoming.device, ride);
  }
  ride.id = incoming.id;
  ride.name = incoming.default_name ? null : incoming.name;
  ride.started = incoming.started;
  ride.ended = incoming.ended;
  ride.removed = incoming.removed;

  const changed = new Set();
  for (const point of incoming.points) {
    const position = L.latLng(point.lat, point.lon);
    if (!ride.last || point.time - ride.last.time >= GAP) {
      ride.segment = { line: L.polyline([], { color: ride.color, weight: 5, opacity: .95 }).addTo(ride.route), positions: [] };
    } else {
      ride.distance += position.distanceTo([ride.last.lat, ride.last.lon]);
    }
    ride.segment.positions.push(position);
    changed.add(ride.segment);
    ride.last = point;
  }
  for (const part of changed) part.line.setLatLngs(part.positions);
  if (!incoming.points.length) return;

  const last = ride.last;
  const position = [last.lat, last.lon];
  const arrow = document.createElement('span');
  if (last.bearing != null) {
    arrow.className = 'direction';
    arrow.style.transform = 'rotate(' + last.bearing + 'deg)';
  }
  const icon = L.divIcon({ className: 'position-icon', html: arrow, iconSize: [26, 26] });
  if (ride.marker) ride.marker.setLatLng(position).setIcon(icon);
  else {
    ride.marker = L.marker(position, { icon, title: rideName(ride), alt: rideName(ride) }).addTo(ride.layers);
    ride.marker.on('click', () => selectRide(ride.device));
  }
  const markerElement = ride.marker.getElement();
  if (markerElement) markerElement.style.backgroundColor = ride.color;
  if (last.accuracy != null) {
    if (ride.accuracy) ride.accuracy.setLatLng(position).setRadius(last.accuracy);
    else ride.accuracy = L.circle(position, { radius: last.accuracy, color: ride.color, weight: 1, fillOpacity: .12, interactive: false }).addTo(ride.layers);
  } else {
    ride.accuracy?.remove();
    ride.accuracy = undefined;
  }
}

function labelMarker(ride) {
  const marker = ride.marker?.getElement();
  if (!marker) return;
  marker.style.backgroundColor = ride.color;
  marker.title = rideName(ride);
  marker.setAttribute('aria-label', rideName(ride));
}

function updateFitLabel() {
  element('fit').textContent = t(visibleRides().length > 1 ? 'routes' : 'route');
}

function fit(items) {
  const bounds = L.latLngBounds([]);
  for (const ride of items) {
    const routeBounds = ride.route.getBounds();
    if (routeBounds.isValid()) bounds.extend(routeBounds);
  }
  if (bounds.isValid()) map.fitBounds(bounds, { padding: [36, 36], maxZoom: 16, animate: false });
}

function renderMap(reset = false) {
  const items = visibleRides();
  const positioned = items.filter(ride => ride.last);
  for (const ride of rides.values()) {
    if (positioned.includes(ride)) ride.layers.addTo(map);
    else ride.layers.remove();
    labelMarker(ride);
  }
  if (!positioned.length) {
    renderedView = undefined;
    if (!items.length) showEmpty('emptyTitle', 'emptyText');
    else if (items.every(finished)) showEmpty('noRoute', 'noPoints');
    else showEmpty('waiting', 'firstFix');
    return;
  }
  element('map').removeAttribute('aria-hidden');
  element('empty').hidden = true;
  element('map-actions').hidden = false;
  element('follow').hidden = items.length !== 1;
  updateFitLabel();

  const signature = positioned.map(ride => [ride.device, ride.id, ride.started].join(':')).join(',');
  const firstView = reset || signature !== renderedView;
  renderedView = signature;
  if (items.length > 1) {
    if (firstView || following) fit(positioned);
  } else {
    const ride = positioned[0];
    if (firstView && finished(ride)) {
      fit(positioned);
      setFollowing(false);
    } else if (firstView) map.setView([ride.last.lat, ride.last.lon], 15, { animate: false });
    else if (following) map.panTo([ride.last.lat, ride.last.lon], { animate: false });
  }
}

function selectRide(device) {
  selected = device;
  setFollowing(true);
  renderMap(true);
  renderStats();
}

map.on('dragstart', () => setFollowing(false));
element('all-rides').addEventListener('click', () => selectRide(null));
element('follow').addEventListener('click', () => {
  const last = detailRide()?.last;
  if (!last) return;
  setFollowing(true);
  map.setView([last.lat, last.lon], Math.max(map.getZoom(), 15));
});
element('fit').addEventListener('click', () => {
  setFollowing(false);
  fit(visibleRides());
});
new ResizeObserver(() => map.invalidateSize({ pan: false })).observe(element('map'));
document.addEventListener('tracker-language', translateMap);
translateMap();

const token = location.pathname.split('/').at(-1);
const source = new EventSource('/api/family/' + encodeURIComponent(token) + '/rides');
source.onopen = () => { connected = true; renderStats(); };
source.onerror = () => {
  connected = false;
  if (!received) showEmpty('reconnecting', 'reconnectText');
  renderStats();
};
source.addEventListener('rides', event => {
  received = true;
  for (const ride of JSON.parse(event.data).rides) updateRide(ride);
  renderMap();
  renderStats();
});
source.addEventListener('revoked', () => {
  source.close();
  for (const ride of rides.values()) ride.layers.remove();
  rides.clear();
  selected = null;
  renderStats();
  revoked = true;
  status('ended', 'renewed');
  showEmpty('unavailable', 'askLink');
});
setInterval(renderStats, 1000);
