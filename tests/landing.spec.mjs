import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { expect } from '@playwright/test';
import { test } from './tracker.fixture.mjs';

test('Startseite öffnet die Familienkarte über Link und Kennung', async ({ page, request, tracker }) => {
  const { origin, ownerKey } = tracker;
  const response = await request.get(`${origin}/api/owner/link`, { headers: { Authorization: `Bearer ${ownerKey}` } });
  await expect(response).toBeOK();
  const { path } = await response.json();
  await page.goto(origin);
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Deine Ausfahrt.');

  const input = page.getByRole('textbox', { name: 'Familienlink oder Familienkennung' });
  await input.fill(`https://example.com${path}`);
  await page.getByRole('button', { name: 'Live-Karte öffnen' }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page).toHaveURL(`${origin}/`);

  await page.getByRole('button', { name: 'EN', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('Enter a valid family link or the full family ID.');
  const englishInput = page.getByRole('textbox', { name: 'Family link or family ID' });
  await englishInput.fill(`${origin}${path}`);
  await expect(page.getByRole('alert')).toBeHidden();
  await englishInput.press('Enter');
  await expect(page).toHaveURL(`${origin}${path}?lang=en`);
  await expect(page.getByRole('heading', { name: 'No rides yet' })).toBeVisible();

  await page.getByRole('button', { name: 'DE', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Noch keine Fahrt' })).toBeVisible();
  await page.getByRole('link', { name: 'Rennrad-Tracker' }).click();
  await input.fill(path.slice(3));
  await input.press('Enter');
  await expect(page).toHaveURL(`${origin}${path}?lang=de`);
  await expect(page.getByRole('heading', { name: 'Noch keine Fahrt' })).toBeVisible();

  const revoked = await page.goto(`${origin}/f/${randomUUID()}?lang=en`);
  expect(revoked.status()).toBe(404);
  await expect(page.getByRole('heading', { name: 'Family link unavailable' })).toBeVisible();
});

test('Rechtsseiten liefern die übernommenen Rechtstexte vollständig aus', async ({ page, request, tracker }) => {
  const { origin } = tracker;
  for (const [url, file] of [
    ['/impressum', 'imprint.de.html'], ['/en/imprint', 'imprint.en.html'],
    ['/datenschutz', 'privacy.de.html'], ['/en/privacy', 'privacy.en.html'],
  ]) {
    const response = await request.get(origin + url);
    await expect(response).toBeOK();
    const fragment = await readFile(new URL(`../server/web/legal/${file}`, import.meta.url), 'utf8');
    expect(await response.text()).toContain(`<div class="legal-copy">${fragment}</div>`);
  }
  await page.goto(origin);
  await page.getByRole('link', { name: 'Datenschutz' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Datenschutzerklärung' })).toBeVisible();
  await page.getByRole('link', { name: 'EN', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Privacy Policy' })).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
});
