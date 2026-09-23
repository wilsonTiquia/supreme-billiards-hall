import { test as base, expect, request, type APIRequestContext, type Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';

/*
 * Shared setup for the browser tests.
 *
 * Two rules about what belongs here:
 *
 *  - SETUP goes over the API, ASSERTIONS go through the page. Building a catalogue by clicking
 *    admin forms would test the admin forms in every spec and make all four fail together when
 *    one of them changes. Everything a spec is actually about is clicked.
 *
 *  - Nothing here touches anything but the scratch instance. The database name and the ports
 *    come from the same environment the guard in playwright.config.ts checked before this file
 *    was ever loaded.
 */

const API_PORT = process.env.E2E_API_PORT ?? '8081';
export const API_TARGET = `http://localhost:${API_PORT}`;

const DB_HOST = process.env.E2E_DB_HOST ?? 'localhost';
const DB_PORT = process.env.E2E_DB_PORT ?? '5432';
const DB_NAME = process.env.E2E_DB_NAME ?? 'supreme_e2e';
const DB_USERNAME = process.env.E2E_DB_USERNAME ?? 'supreme';
const DB_PASSWORD = process.env.E2E_DB_PASSWORD ?? 'supreme';
const PSQL = process.env.PSQL ?? '/Library/PostgreSQL/18/bin/psql';

/*
 * The two logins.
 *
 * `owner` exists because scripts/e2e-backend.sh boots the app with
 * SUPREME_BOOTSTRAP_ADMIN_PASSWORD set: V9 disabled every seeded credential, and
 * AdminPasswordBootstrap is the one supported way back in (HELP.md, "Launch-day: setting the
 * first login credentials"). The bootstrap does not set must_change_password, so the owner
 * lands usable.
 *
 * `counter` takes two steps, and the second one is not optional. An admin reset ALWAYS sets
 * must_change_password — AuthServiceImpl.resetPassword, so that the owner never holds a working
 * credential for someone else's account — and V10 then gates that account to nothing but
 * /auth/me, its own password and logging out. So the fixture sets a temporary password as the
 * owner, then signs in as the counter and replaces it, which is exactly the sequence HELP.md
 * describes. Skipping the second step lands every counter test on the "Set a new password"
 * wall instead of the floor, which is how this was found.
 */
export const OWNER = {
  username: 'owner',
  password: process.env.E2E_OWNER_PASSWORD ?? 'e2e-owner-password',
};
export const COUNTER = {
  username: 'counter',
  password: process.env.E2E_COUNTER_PASSWORD ?? 'e2e-counter-password',
};

/** The prices the whole project's arithmetic is quoted in — BACKEND-SPEC §4.1. */
export const BEER = { name: 'San Miguel Pale Pilsen', price: 90, cost: 62.5, received: 48 };
export const SISIG = { name: 'Sisig', price: 180, cost: 95, received: 20 };

interface Envelope<T> {
  data: T;
  message: string;
  success: boolean;
}

/** A logged-in API context. The session cookie lives in it, exactly as it does in the browser. */
export async function apiAs(who: { username: string; password: string }): Promise<APIRequestContext> {
  const context = await request.newContext({ baseURL: API_TARGET });
  const response = await context.post('/api/v1/auth/login', { data: who });
  if (!response.ok()) {
    throw new Error(`Could not log in as ${who.username}: ${response.status()} ${await response.text()}`);
  }
  return context;
}

async function unwrap<T>(response: { ok(): boolean; status(): number; text(): Promise<string>; json(): Promise<Envelope<T>> }): Promise<T> {
  if (!response.ok()) throw new Error(`${response.status()} — ${await response.text()}`);
  return (await response.json()).data;
}

export async function apiGet<T>(context: APIRequestContext, path: string): Promise<T> {
  return unwrap<T>(await context.get(path));
}

export async function apiPost<T>(context: APIRequestContext, path: string, data: unknown): Promise<T> {
  return unwrap<T>(await context.post(path, { data }));
}

/**
 * Moves a session's start backwards so it can be closed with real minutes on it.
 *
 * A FIXTURE CHEAT, and the only one in the suite. It is here because the trace cannot be driven
 * through the UI at all: OpenSessionRequestDTO carries no start time on purpose ("the server is
 * the only clock"), and POST /sessions/{id}/billed-minutes only ever reduces — charging more
 * than was played is refused as an overcharge, SessionServiceImpl. So ninety minutes of table
 * time is ninety minutes of waiting, or this.
 *
 * NOBODY CAN DO THIS FROM THE POS. Do not read it as a user action, and do not build anything
 * on the assumption that a session's clock can be moved: it cannot, which is the point.
 *
 * Billing truncates (90m59s is 90 minutes), so a close within a minute of this call bills
 * exactly the number asked for.
 */
export function backdateSession(sessionId: string, minutes: number): void {
  const sql = `
    UPDATE table_session SET opened_at = opened_at - interval '${minutes} minutes' WHERE id = '${sessionId}';
    UPDATE session_segment SET started_at = started_at - interval '${minutes} minutes' WHERE session_id = '${sessionId}';
  `;
  execFileSync(PSQL, ['-h', DB_HOST, '-p', DB_PORT, '-U', DB_USERNAME, '-d', DB_NAME, '-v', 'ON_ERROR_STOP=1', '-q', '-c', sql], {
    env: { ...process.env, PGPASSWORD: DB_PASSWORD },
    encoding: 'utf8',
  });
}

/** True when these credentials still work — the fixture is re-entrant, so it has to ask. */
async function canSignIn(who: { username: string; password: string }): Promise<boolean> {
  const context = await request.newContext({ baseURL: API_TARGET });
  try {
    return (await context.post('/api/v1/auth/login', { data: who })).ok();
  } finally {
    await context.dispose();
  }
}

/** What one run needs before anything can be sold: a usable counter login and stock on hand. */
export interface Hall {
  beerId: string;
  sisigId: string;
}

/**
 * Runs once for the whole run: turns the counter login on and stocks the hall.
 *
 * V2 seeds a branch, seven tables, rates, customer types and four categories — but NO products,
 * so nothing can be sold until something is created and received. The delivery is what gives the
 * products their unit_cost, which is where ₱157.50 of cost on the worked trace comes from.
 *
 * FIND-OR-CREATE, not create. Playwright restarts the worker after a failing test, and a
 * worker-scoped fixture runs again in the new one — so a plain create turned one real failure
 * into four identical "product already exists" failures with the actual cause scrolled off the
 * top. Setup that cannot run twice hides the first error.
 */
async function stockTheHall(): Promise<Hall> {
  const owner = await apiAs(OWNER);

  const users = await apiGet<Array<{ id: string; username: string; mustChangePassword: boolean }>>(
    owner,
    '/api/v1/users',
  );
  const counter = users.find((user) => user.username === COUNTER.username);
  if (!counter) throw new Error('The seeded counter user is missing from the scratch database.');

  if (counter.mustChangePassword !== false || !(await canSignIn(COUNTER))) {
    const temporary = 'e2e-temporary-password';
    await unwrap(
      await owner.put(`/api/v1/users/${counter.id}/password`, { data: { newPassword: temporary } }),
    );
    // The counter walks through its own forced change, because nobody else can do it for them.
    const fresh = await apiAs({ username: COUNTER.username, password: temporary });
    await unwrap(
      await fresh.put('/api/v1/auth/password', {
        data: { currentPassword: temporary, newPassword: COUNTER.password },
      }),
    );
    await fresh.dispose();
  }

  const categories = await apiGet<Array<{ id: string; name: string }>>(owner, '/api/v1/categories');
  const categoryNamed = (name: string) => categories.find((category) => category.name === name)?.id;

  const existing = await apiGet<Array<{ id: string; name: string }>>(owner, '/api/v1/products');
  const productNamed = async (name: string, categoryName: string, sellingPrice: number) => {
    const found = existing.find((product) => product.name === name);
    if (found) return found.id;
    const made = await apiPost<{ id: string }>(owner, '/api/v1/products', {
      name,
      categoryId: categoryNamed(categoryName),
      sellingPrice,
    });
    return made.id;
  };

  const beerId = await productNamed(BEER.name, 'Beer', BEER.price);
  const sisigId = await productNamed(SISIG.name, 'Food', SISIG.price);

  // Only on the first pass. A second delivery would move the weighted average cost and take
  // ₱157.50 off the worked trace with it.
  if (existing.length === 0) {
    await apiPost(owner, '/api/v1/stock/deliveries', {
      supplierName: 'E2E fixture',
      lines: [
        { productId: beerId, quantity: BEER.received, unitCost: BEER.cost },
        { productId: sisigId, quantity: SISIG.received, unitCost: SISIG.cost },
      ],
    });
  }

  await owner.dispose();
  return { beerId, sisigId };
}

export const test = base.extend<{ signIn: (who: typeof OWNER) => Promise<void> }, { hall: Hall }>({
  hall: [
    async ({}, use) => {
      await use(await stockTheHall());
    },
    { scope: 'worker' },
  ],

  /*
   * Signs in through the login form, because that is a screen too.
   *
   * The cookie is cleared first so this also works as "switch user": the login route redirects
   * an authenticated visitor straight back out, and half these tests are one person handing the
   * screen to another.
   */
  signIn: async ({ page }, use) => {
    await use(async (who) => {
      await page.context().clearCookies();
      await page.goto('/login');
      await page.getByLabel('Username').fill(who.username);
      await page.getByLabel('Password', { exact: true }).fill(who.password);
      await page.getByRole('button', { name: 'Sign in' }).click();
      await expect(page).not.toHaveURL(/\/login/);
    });
  },
});

/**
 * Opens a table from the floor and returns the session id, taken from the URL it lands on.
 *
 * The card is found by the big number it shows, which the floor pads to two digits — that
 * figure is what staff say out loud, and it is the only thing on the card that identifies it.
 * The modal title is then asserted before anything is started, so picking the wrong card fails
 * here rather than three assertions later on somebody else's table.
 */
export async function openTable(page: Page, tableNumber: number): Promise<string> {
  const padded = String(tableNumber).padStart(2, '0');
  await page.goto('/floor');
  await page
    .locator('button', { has: page.locator('.figure-table-number', { hasText: new RegExp(`^${padded}$`) }) })
    .click();
  await expect(page.getByRole('dialog', { name: new RegExp(`^Start Table ${tableNumber}\\b`) })).toBeVisible();
  await page.getByRole('button', { name: 'Start table' }).click();
  await page.waitForURL(/\/sessions\/[0-9a-f-]{36}/);
  return page.url().split('/sessions/')[1];
}

/** Adds one of a product to the open bill, by clicking its tile. */
export async function addToBill(page: Page, productName: string): Promise<void> {
  await page.getByRole('button', { name: new RegExp(productName) }).first().click();
}

export { expect };
