# Supreme Billiards — point of sale

A till for a billiard hall in Parañaque. Staff open a table, a clock runs, food and drink attach
to that table's bill, one payment closes it. The owner gets a morning dashboard and a monthly
report that says what the hall made after every cost. Live at **https://billiards.frasi.tech**.

Spring Boot 4 · Java 21 · PostgreSQL 18 · React 19 + TypeScript · Docker Compose behind Caddy ·
GitHub Actions CI with a protected `main` and a one-click deploy.

## What it does

- **Tables and time.** Per-minute billing, computed on the server from timestamps — nothing is
  held in memory, so a restart, a browser refresh or a power cut loses no time. Pause, friend
  rates, promos, flat rates, all logged with who did it.
- **Bills.** Products snapshot their price and cost onto the line at the moment of sale, so
  re-pricing a beer tomorrow never changes last month's profit. Voids are kept and excluded,
  never deleted.
- **Stock.** An append-only ledger. Deliveries carry a unit cost that feeds a moving average;
  corrections are compensating rows. `qty_on_hand` is a cache maintained in the same transaction.
- **Money control.** One payment per bill with an idempotency key, optimistic locking on
  checkout, a per-address login lockout, a nightly cash count with a generated variance column,
  unsettled bills that stay visible until collected.
- **Reports.** A night at a glance, and any date range against the equivalent period before it:
  sales, cost of goods, expenses, what was left, break-even per night, day-of-week, hour-of-day,
  table earnings per occupied hour, product margins, everything given away.
- **Roles.** Employees never receive a cost or profit figure from the API — enforced in the
  DTOs, not hidden in the UI.

The business day runs 10:00–05:00 Manila and is computed by the database (`business_date_of()`),
so a sale at 2am belongs to the night still running.

## Architecture

```
browser ──https──▶ Caddy ──▶ Spring Boot app ──▶ PostgreSQL 18
                   (TLS)      (no public port)    (no public port)
```

Three containers on one VPS (`compose.yaml`). Caddy holds the certificate and stamps the real
client address; the app trusts that header only because it is unreachable any other way. The
database and the uploads folder are the only state. `docs/DEPLOY-VPS.md` is the runbook, walked.

## How changes ship

```
feat/<slug> ──▶ pull request ──▶ CI: 203 backend tests on postgres:18 + frontend build
            ──▶ review, squash-merge (blocked until CI is green) ──▶ Deploy button ──▶ VPS
```

`CLAUDE.md` §9 makes every Claude Code session follow this without being told. `docs/WORKFLOW.md`
is the human version. Deploy is deliberately a button, not a hook on merge: a till takes money,
and "the code is right" and "now is a good time" are different decisions.

## Running it

```bash
# database
createdb -U supreme supreme && psql -U supreme -d supreme -c 'CREATE EXTENSION btree_gist'

# backend (builds the SPA too)
./mvnw package -DskipTests && java -jar target/*-SNAPSHOT.jar      # http://localhost:8080

# tests — always on a scratch database, never the one the till uses
createdb -U supreme supreme_scratch
DB_URL=jdbc:postgresql://localhost:5432/supreme_scratch ./mvnw test
dropdb -U supreme supreme_scratch

# frontend on its own, proxying /api to 8080
cd frontend && npm ci && npm run dev
```

First login on an empty database: set `SUPREME_BOOTSTRAP_ADMIN_PASSWORD` in the environment for
one boot (`HELP.md`, break-glass). `docs/SETUP.md` has the full local setup.

## Where things are

| | |
|---|---|
| `src/main/java/.../controller` `service` `repository` `dto` `entity` | Backend, one vertical slice per feature |
| `src/main/resources/db/migration` | Flyway, V1–V20. The schema is the spec; JPA runs `validate` |
| `frontend/src/features` | The screens: floor, session, checkout, end of day, admin |
| `docs/API-CONTRACT.md` | Every endpoint and its shape |
| `docs/RUNBOOK.md` | Day to day: it will not start, the backup did not run, after a power cut |
| `docs/GO-LIVE.md` | The checklist for opening day, walked vs not walked |
| `docs/DEPLOY-VPS.md` | The server: first boot, backups, restore drill, starting over |
| `docs/WORKFLOW.md` | Branch → PR → CI → merge → deploy, for a human |
| `CLAUDE.md` | The rules every coding session follows: style, correctness, branches |

## Working style

Built with Claude Code as the second pair of hands. Every feature enters as a written brief,
comes back as a plan, is checked against the code before approval, and lands through a pull
request that a person reviews. The rules that make that safe — money in `BigDecimal`, the server
as the only clock, tests that are made to fail once before they are trusted — are written down in
`CLAUDE.md` so they survive the session that learned them.
