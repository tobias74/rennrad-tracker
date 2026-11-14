import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { expect } from '@playwright/test';
import { test } from './tracker.fixture.mjs';

const bearer = key => ({ Authorization: `Bearer ${key}` });
const device = name => ({ name, id: randomUUID(), key: randomBytes(32).toString('hex') });
const enrollment = ({ name, key }) => ({
  name,
  challenge: createHash('sha256').update(key).digest('hex'),
});

async function openTwoLiveRides({ page, request, tracker }) {
  const { origin, ownerKey } = tracker;
  const first = device('Rennrad Nord');
  const second = device('Rennrad Süd');
  const register = await request.post(`${origin}/api/owner/devices`, {
    headers: bearer(ownerKey), data: enrollment(first),
  });
  expect(register.status()).toBe(204);

  const approve = await request.post(`${origin}/api/owner/pair`, {
    headers: bearer(first.key), data: { ...enrollment(second), expires: Date.now() + 300_000 },
  });
  expect(approve.status()).toBe(204);
  const claim = await request.post(`${origin}/api/pair/claim`, {
    headers: bearer(second.key), data: {},
  });
  expect(claim.status()).toBe(200);
  const { path } = await claim.json();
  expect(path).toMatch(/^\/f\/[0-9a-f-]{36}$/);

  const firstLink = await request.get(`${origin}/api/owner/link`, { headers: bearer(first.key) });
  await expect(firstLink).toBeOK();
  expect((await firstLink.json()).path).toBe(path);

  // Keep the basemap independent of the network. Leaflet and the ride stream are real.
  await page.route('https://tile.openstreetmap.org/**', route => route.fulfill({
    contentType: 'image/svg+xml',
    body: '<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256"/>',
  }));
  const now = Date.now();
  await page.clock.setFixedTime(now);
  const familyUrl = origin + path;
  await page.goto(familyUrl);
  await expect(page.getByRole('heading', { name: 'Noch keine Fahrt' })).toBeVisible();
  const navigations = [];
  page.on('framenavigated', frame => {
    if (frame === page.mainFrame()) navigations.push(frame.url());
  });

  const started = now - 8_000;
  async function upload({ id, key }, points, ended) {
    const response = await request.post(`${origin}/api/owner/rides/${id}`, {
      headers: bearer(key), data: { started, points, ended },
    });
    expect(response.status(), `Upload for ride ${id}`).toBe(204);
  }

  await Promise.all([
    upload(first, [
      { seq: 0, time: now - 6_000, lat: 48.14, lon: 11.55, speed: 8, battery: 81, altitude: 530 },
      { seq: 1, time: now - 4_000, lat: 48.143, lon: 11.552, speed: 9, battery: 80, altitude: 535 },
    ]),
    upload(second, [
      { seq: 0, time: now - 5_000, lat: 48.13, lon: 11.585, speed: 6, battery: 46, altitude: 510 },
      { seq: 1, time: now - 3_000, lat: 48.133, lon: 11.587, speed: 7, battery: 45, altitude: 515 },
    ]),
  ]);

  await expect(page.getByRole('status')).toHaveText('2 live');
  return { first, second, now, upload, familyUrl, navigations };
}

test('zwei Fahrten am selben Familienlink live verfolgen und einzeln öffnen', async ({ page, request, tracker }) => {
  const { first, second, now, upload, familyUrl, navigations } = await openTwoLiveRides({ page, request, tracker });
  const picker = page.getByRole('navigation', { name: 'Fahrten' });
  const all = picker.getByRole('button', { name: 'Alle Fahrten' });
  const north = picker.getByRole('button', { name: 'Rennrad Nord Live' });
  const south = picker.getByRole('button', { name: 'Rennrad Süd Live' });
  const map = page.getByRole('region', { name: 'Karte der Fahrten' });
  const northMarker = map.getByRole('button', { name: 'Rennrad Nord', exact: true });
  const southMarker = map.getByRole('button', { name: 'Rennrad Süd', exact: true });
  const telemetry = page.getByRole('region', { name: 'Fahrtdaten' });

  await expect(all).toHaveAttribute('aria-pressed', 'true');
  await expect(northMarker).toBeInViewport();
  await expect(southMarker).toBeInViewport();
  await expect(telemetry).toBeHidden();
  await expect.poll(() => map.locator('canvas').evaluate(canvas => {
    const context = canvas.getContext('2d');
    const { data } = context.getImageData(0, 0, canvas.width, canvas.height);
    // Only the route strokes are this opaque. Check the green and blue tracks.
    let green = false;
    let blue = false;
    for (let i = 0; i < data.length; i += 4) {
      if (data[i + 3] < 230) continue;
      green ||= data[i + 1] - data[i + 2] > 20;
      blue ||= data[i + 2] - data[i + 1] > 20;
      if (green && blue) return true;
    }
    return false;
  }), { message: 'Both route colors should be drawn on the map' }).toBe(true);

  await north.click();
  await expect(north).toHaveAttribute('aria-pressed', 'true');
  await expect(southMarker).toBeHidden();
  await expect(telemetry.locator('#speed')).toHaveText('32 km/h');
  await expect(telemetry.locator('#battery')).toHaveText('80 %');
  await telemetry.getByText('Fahrtdetails', { exact: true }).click();
  await expect(telemetry.locator('#altitude')).toHaveText('535 m');

  await Promise.all([
    upload(first, [{ seq: 2, time: now, lat: 48.146, lon: 11.554, speed: 10, battery: 79, altitude: 540 }]),
    upload(second, [{ seq: 2, time: now, lat: 48.135, lon: 11.589, speed: 8, battery: 44, altitude: 520 }]),
  ]);
  await expect(telemetry.locator('#speed')).toHaveText('36 km/h');
  await expect(telemetry.locator('#battery')).toHaveText('79 %');
  await expect(north).toHaveAttribute('aria-pressed', 'true');

  await south.click();
  await expect(south).toHaveAttribute('aria-pressed', 'true');
  await expect(northMarker).toBeHidden();
  await expect(southMarker).toBeInViewport();
  await expect(telemetry.locator('#speed')).toHaveText('29 km/h');
  await expect(telemetry.locator('#battery')).toHaveText('44 %');
  await expect(telemetry.locator('#altitude')).toHaveText('520 m');

  await all.click();
  await expect(northMarker).toBeInViewport();
  await expect(southMarker).toBeInViewport();
  await expect(page.getByRole('status')).toHaveText('2 live');
  await expect(page).toHaveURL(familyUrl);
  expect(navigations).toEqual([]);
});

test('eine Fahrt endet und die andere bleibt am selben Familienlink live', async ({ page, request, tracker }) => {
  const { first, second, now, upload, familyUrl, navigations } = await openTwoLiveRides({ page, request, tracker });
  const status = page.getByRole('status');
  const picker = page.getByRole('navigation', { name: 'Fahrten' });
  const finished = picker.getByRole('button', { name: 'Rennrad Nord Beendet', exact: true });
  const live = picker.getByRole('button', { name: 'Rennrad Süd Live', exact: true });
  const telemetry = page.getByRole('region', { name: 'Fahrtdaten' });

  await upload(first, [], now - 2_000);
  await expect(status).toHaveText('1 live');
  await expect(finished).toBeVisible();
  await expect(live).toBeVisible();

  await finished.click();
  await expect(status).toHaveText('Fahrt beendet');
  await expect(telemetry.locator('#speed')).toHaveText('32 km/h');
  await expect(telemetry.locator('#battery')).toHaveText('80 %');

  await live.click();
  await expect(status).toHaveText('Live');
  await expect(telemetry.locator('#speed')).toHaveText('25 km/h');
  await upload(second, [{ seq: 2, time: now, lat: 48.135, lon: 11.589, speed: 10, battery: 44 }]);
  await expect(telemetry.locator('#speed')).toHaveText('36 km/h');
  await expect(telemetry.locator('#battery')).toHaveText('44 %');
  await expect(status).toHaveText('Live');
  await expect(finished).toBeVisible();

  await picker.getByRole('button', { name: 'Alle Fahrten' }).click();
  await expect(status).toHaveText('1 live');
  const map = page.getByRole('region', { name: 'Karte der Fahrten' });
  await expect(map.getByRole('button', { name: 'Rennrad Nord', exact: true })).toBeInViewport();
  await expect(map.getByRole('button', { name: 'Rennrad Süd', exact: true })).toBeInViewport();
  await expect(page).toHaveURL(familyUrl);
  expect(navigations).toEqual([]);

  await page.getByRole('button', { name: 'EN', exact: true }).click();
  const englishPicker = page.getByRole('navigation', { name: 'Rides' });
  await englishPicker.getByRole('button', { name: 'Rennrad Nord Ended', exact: true }).click();
  await expect(status).toHaveText('Ride ended');
  await englishPicker.getByRole('button', { name: 'Rennrad Süd Live', exact: true }).click();
  await expect(page.getByRole('region', { name: 'Ride data' }).locator('#speed')).toHaveText('36 km/h');
  expect(navigations).toEqual([`${familyUrl}?lang=en`]);
});
