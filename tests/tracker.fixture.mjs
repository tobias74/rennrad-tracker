import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { expect, test as base } from '@playwright/test';

const binaryName = process.platform === 'win32' ? 'rennrad-tracker.exe' : 'rennrad-tracker';
const executable = fileURLToPath(new URL(`../server/target/debug/${binaryName}`, import.meta.url));
const listeningAddress = /läuft auf (http:\/\/127\.0\.0\.1:\d+)/;

export const test = base.extend({
  tracker: async ({}, use, testInfo) => {
    const ownerKey = randomBytes(32).toString('hex');
    const server = spawn(executable, {
      env: {
        ...process.env,
        TRACKER_BIND: '127.0.0.1:0',
        TRACKER_DATA: testInfo.outputPath('tracker-data'),
        TRACKER_OWNER_KEY: ownerKey,
      },
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true,
    });
    let stdout = '';
    let stderr = '';
    let launchError;
    server.stdout.setEncoding('utf8').on('data', chunk => { stdout += chunk; });
    server.stderr.setEncoding('utf8').on('data', chunk => { stderr += chunk; });
    server.once('error', error => { launchError = error; });
    const closed = new Promise(resolve => server.once('close', resolve));

    try {
      await expect.poll(() => {
        if (launchError) throw launchError;
        if (server.exitCode !== null) throw new Error(`Rust server exited with ${server.exitCode}: ${stderr}`);
        return stdout.match(listeningAddress)?.[1];
      }, { message: 'The local Rust server should start', timeout: 10_000 }).toBeDefined();
      await use({ origin: stdout.match(listeningAddress)[1], ownerKey });
    } finally {
      server.kill();
      await closed;
    }
  },
});
