import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  preserveOutput: 'failures-only',
  use: {
    ...devices['Pixel 7'],
    locale: 'de-DE',
    trace: 'retain-on-failure',
  },
});
