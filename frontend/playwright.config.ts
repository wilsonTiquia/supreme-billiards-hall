import { defineConfig, devices } from '@playwright/test';
import { execFileSync } from 'node:child_process';

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  THE GUARD. Read this before anything else in this file.
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Playwright drives a browser against a RUNNING app, and the app migrates and writes whatever
 * database it is pointed at. An E2E suite writes real bills, real payments and real stock
 * movements, and unlike the Java tests there is no transaction to roll them back — the browser
 * is a separate process going through HTTP.
 *
 * So the suite gets its own instance on 8081 and its own throwaway database, and refuses to run
 * against the till. That refusal is HERE, at module scope, because this file is evaluated before
 * Playwright spawns a web server or a browser — a throw on this line means no JVM ever boots and
 * so no Flyway migration is ever applied to the wrong database.
 *
 * Two of the four checks are not the obvious ones, and they are the two that matter:
 *
 *   - THE API TARGET, not just the base URL. The browser talks to the vite dev server, which
 *     proxies /api wherever VITE_API_TARGET says. A dev server on 5174 proxying to 8080 has a
 *     completely innocent base URL and writes real bills into the till. The base URL alone is
 *     not the check.
 *
 *   - THE PAYMENT ROWS. Every other check trusts a name, and naming discipline is exactly what
 *     failed the last three times. A database holding payments is somebody's real money whatever
 *     it is called. It is allowed only if it carries the marker scripts/e2e-backend.sh stamps on
 *     the databases it creates — which is how last night's scratch database, full of test
 *     payments, stays usable while the till's never becomes usable.
 *
 * docs/RUNBOOK.md — "Never point the browser tests at the trading database" — is the third face
 * of the hazard already recorded there for `mvnw test` and for the IDE Run button.
 */

const DB_HOST = process.env.E2E_DB_HOST ?? 'localhost';
const DB_PORT = process.env.E2E_DB_PORT ?? '5432';
const DB_NAME = process.env.E2E_DB_NAME ?? 'supreme_e2e';
const DB_USERNAME = process.env.E2E_DB_USERNAME ?? 'supreme';
const DB_PASSWORD = process.env.E2E_DB_PASSWORD ?? 'supreme';

const API_PORT = process.env.E2E_API_PORT ?? '8081';
const WEB_PORT = process.env.E2E_WEB_PORT ?? '5174';

const API_TARGET = `http://localhost:${API_PORT}`;
const BASE_URL = process.env.E2E_BASE_URL ?? `http://localhost:${WEB_PORT}`;

/** Kept in step with scripts/e2e-backend.sh, which writes it. */
const SCRATCH_MARKER = 'supreme-e2e-scratch: created by scripts/e2e-backend.sh, safe to destroy';

const PSQL = process.env.PSQL ?? '/Library/PostgreSQL/18/bin/psql';

function refuse(what: string): never {
  throw new Error(
    `\nREFUSING TO RUN THE BROWSER TESTS\n\n  ${what}\n\n` +
      'The suite runs against its own instance and its own throwaway database, never the till ' +
      'on 8080.\nSee docs/RUNBOOK.md — "Never point the browser tests at the trading database".\n',
  );
}

function portOf(url: string): string {
  try {
    return new URL(url).port;
  } catch {
    return refuse(`E2E_BASE_URL "${url}" is not a URL.`);
  }
}

/** One `psql -tAc`, trimmed. Null when psql cannot answer — a missing database, a missing table. */
function ask(database: string, sql: string): string | null {
  try {
    return execFileSync(
      PSQL,
      ['-h', DB_HOST, '-p', DB_PORT, '-U', DB_USERNAME, '-d', database, '-tAc', sql],
      { env: { ...process.env, PGPASSWORD: DB_PASSWORD }, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] },
    ).trim();
  } catch {
    return null;
  }
}

function guardAgainstTheTradingSystem(): void {
  // 1. The till's port, on either side of the proxy.
  if (portOf(BASE_URL) === '8080') refuse(`The base URL is ${BASE_URL}. Port 8080 is the till.`);
  if (API_PORT === '8080') refuse('E2E_API_PORT is 8080. That is the till.');

  // 2. The till's database, whatever else is pointed where.
  if (DB_NAME === 'supreme') refuse("E2E_DB_NAME is 'supreme'. That is the trading database.");
  if (!DB_NAME.endsWith('_e2e')) {
    refuse(`E2E_DB_NAME "${DB_NAME}" does not end in _e2e, so it is not a scratch database.`);
  }

  // 3. Somewhere else's machine is somebody else's data.
  if (!['localhost', '127.0.0.1', '::1'].includes(DB_HOST)) {
    refuse(`E2E_DB_HOST "${DB_HOST}" is not this machine.`);
  }

  // 4. The one that does not depend on a name being right. A database that already exists and
  //    holds payments is refused unless we are the ones who made it.
  const exists = ask('postgres', `SELECT 1 FROM pg_database WHERE datname = '${DB_NAME}'`);
  if (exists !== '1') return; // Nothing there yet; the backend script will create it.

  const marker = ask(
    'postgres',
    `SELECT coalesce(shobj_description(oid, 'pg_database'), '') FROM pg_database WHERE datname = '${DB_NAME}'`,
  );
  if (marker === SCRATCH_MARKER) return;

  const payments = ask(DB_NAME, 'SELECT count(*) FROM payment');
  if (payments !== null && Number(payments) > 0) {
    refuse(
      `Database "${DB_NAME}" holds ${payments} payment rows and carries no scratch marker. ` +
        'That is somebody\'s real data.',
    );
  }
}

guardAgainstTheTradingSystem();

export default defineConfig({
  testDir: './e2e',
  // These four paths cross the whole system — vouchers, stock, sessions, payment — and they
  // share one database. Running them at once means one test's bill lands in another's report.
  workers: 1,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: [['list']],
  timeout: 60_000,
  expect: { timeout: 10_000 },

  use: {
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    // The counter is a desktop in a dark room, not a phone.
    viewport: { width: 1440, height: 900 },
  },

  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],

  /*
   * Both halves of the scratch instance, owned by the run.
   *
   * The database is created inside scripts/e2e-backend.sh rather than in a globalSetup, because
   * Playwright starts webServer before globalSetup — so a globalSetup that built the database
   * would run after the JVM had already migrated whatever was there.
   *
   * reuseExistingServer is false in every mode, not just CI: silently attaching to a stray
   * process on 8081 is how you end up testing something you did not start.
   */
  webServer: [
    {
      command: '../scripts/e2e-backend.sh',
      // /api/v1/time answers 401 unauthenticated, which is a healthy answer: the app is up and
      // asking us to log in. Verified against this version rather than assumed — isURLAvailable
      // accepts 200..403, so 401 is "up" and a 404 would NOT be. Do not point this at a path
      // that 404s.
      url: `${API_TARGET}/api/v1/time`,
      reuseExistingServer: false,
      timeout: 180_000,
      stdout: 'pipe',
      stderr: 'pipe',
      env: {
        E2E_API_PORT: API_PORT,
        E2E_DB_HOST: DB_HOST,
        E2E_DB_PORT: DB_PORT,
        E2E_DB_NAME: DB_NAME,
        E2E_DB_USERNAME: DB_USERNAME,
        E2E_DB_PASSWORD: DB_PASSWORD,
      },
    },
    {
      command: `npm run dev -- --port ${WEB_PORT} --strictPort`,
      url: BASE_URL,
      reuseExistingServer: false,
      timeout: 60_000,
      env: { VITE_API_TARGET: API_TARGET },
    },
  ],
});
