# Nightwatch — the 06:00 "was last night closed?" alarm

At 06:00 Manila a systemd timer on the VPS runs nightwatch once. It asks the database whether
last night was closed, and if it was not, it sends the owner one Telegram message. Then it exits.

It is a separate program, not a `@Scheduled` method in the app, on purpose: if the app dies at
02:00, the night never gets closed **and** a check inside the app never runs. From the owner's
side that silence looks exactly like a quiet night. Nightwatch runs outside the app, so it can
still report when the app is down.

## What the messages mean

| Message | What happened | What to do |
|---|---|---|
| *the night of Tuesday 23 September was never closed. Nobody counted the drawer.* | No cash count exists for that night. Nobody did the end-of-day count, so nobody closed the day either. | Ask the closing staff. Count the drawer and close the day from Close day, today, before the 10:00 opening. |
| *the night of … was counted but never closed.* | The drawer was counted, but nobody pressed the final close. | Open Close day and finish it. The count is already saved. |
| *the POS did not answer at 06:00. The night of … was closed normally at 02:15.* | The app is down now, but the night was closed before it went down. Nothing was lost. | Open the site before 10:00. If it does not load, the server needs looking at (`docker compose ps` on the VPS). |
| *the POS did not answer at 06:00. The night of … may not have been closed.* | The app is down and the night was not closed. This message replaces the other two, because "not closed" means nothing when the system wasn't running. | Same as above, then close the night once the site is back. |
| *the 06:00 night check could not run. Check the server.* | Nightwatch itself failed: usually the database was unreachable. It exits non-zero and the unit shows as failed. | `journalctl -u supreme-nightwatch` on the VPS shows the error. |

**No message means the night was closed.** With more than one active branch, each message names
its branch.

One case nightwatch cannot catch: if the VPS itself is off, the timer never fires and nothing is
sent.

## How it decides

- A night is closed when its `cash_count` row has `closed_at` set (V3). There are three
  outcomes: no row, a row that was counted but not closed, and a closed row.
  It checks the night first and the app second, so an app-down message can say whether the
  night was closed.
- The night to check is `business_date_of(now())`, and the database computes it (V1). At 06:00
  that is the night that ended at 05:00. The program never computes a date or reads its own clock.
- Without a date argument it runs only between 05:00 and 10:00 Manila. Before 05:00 the night is
  still going. From 10:00 `business_date_of(now())` is *tonight*, which is open by definition.
  Outside that window it refuses and exits 2 without texting anyone. To check any night at any
  hour, name it: `nightwatch 2026-09-23`.
- Exit codes: `0` = it checked, and sent whatever needed sending. `1` = it could not check.
  `2` = it refused (wrong hour, or a bad argument). It never exits 0 when it could not check.

## Setup on the VPS (once)

All of these run **on the VPS**, in `/opt/supreme`, as `pos` unless a step says root.

### 1. Telegram bot and chat id

1. On any phone, open Telegram, message **@BotFather**, send `/newbot`, and pick a name. It
   replies with a token like `123456789:AA...`. That is `TELEGRAM_BOT_TOKEN`.
2. **The owner** opens the new bot and sends it any message ("hi"). A bot cannot message someone
   who has never written to it.
3. On the VPS: `curl -s "https://api.telegram.org/bot<TOKEN>/getUpdates"`. In the reply, find
   `"chat":{"id":123456789,...`. That number is `TELEGRAM_CHAT_ID`.

### 2. The read-only database role

Nightwatch logs in as `nightwatch_ro`, which can read `cash_count` and `branch` and nothing else.
It never logs in as `supreme`.

Pick a password (`openssl rand -hex 16`) and add all three values to `/opt/supreme/.env`:

```
NIGHTWATCH_DB_PASSWORD=<the password>
TELEGRAM_BOT_TOKEN=<from step 1>
TELEGRAM_CHAT_ID=<from step 1>
```

Then create the role. `supreme` is the superuser inside the db container. The script reads the
password from the environment, so it never appears on a command line or in the repo:

```
cd /opt/supreme
set -a; . ./.env; set +a
docker compose exec -T -e NIGHTWATCH_DB_PASSWORD db \
  psql -U supreme -d supreme -f - < ops/nightwatch/sql/create-role.sql
```

Expected: `CREATE ROLE`, `ALTER ROLE` twice, `GRANT` three times. To change the password later:
`ALTER ROLE nightwatch_ro PASSWORD '...'` as supreme, then update `.env`.

**Prove the grant is tight.** This was run on the Mac on 24 Sep 2026. Repeat it on the box with
`docker compose exec db psql -U nightwatch_ro -d supreme`:

```
supreme=> select current_user, business_date_of(now());
 nightwatch_ro | 2026-09-23                       <- the one function it needs, no grant required
supreme=> select count(*) from payment;
ERROR:  permission denied for table payment
supreme=> insert into cash_count (branch_id, business_date) values (...);
ERROR:  cannot execute INSERT in a read-only transaction
```

That INSERT was stopped by the role's `default_transaction_read_only`, which is only a second
line of defence. Any session can switch it off, so the grant has to hold on its own. With the
read-only default turned off:

```
supreme=> set default_transaction_read_only = off;
supreme=> insert into cash_count (branch_id, business_date) values (...);
ERROR:  permission denied for table cash_count
supreme=> update cash_count set note = note;
ERROR:  permission denied for table cash_count
supreme=> delete from branch;
ERROR:  permission denied for table branch
supreme=> create table public.x (i int);
ERROR:  permission denied for schema public
```

### 3. The timer (as root)

The unit files run `docker compose run --rm --build -T nightwatch`. `--build` matters: nightwatch
is under the `ops` compose profile, so the Deploy button's `docker compose up -d --build`
**neither starts nor builds it**. Without `--build`, each run would reuse the image from the
first build forever. The build is cached, so it takes seconds. Check that the box's compose has
the flag: `docker compose run --help | grep -- --build` (the Mac's v5.5.1 does).

```
cp /opt/supreme/ops/nightwatch/systemd/supreme-nightwatch.{service,timer} /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now supreme-nightwatch.timer
systemctl list-timers supreme-nightwatch.timer     # NEXT should read 06:00 PST
```

`Persistent=false` is deliberate. If the box was off at 06:00, a catch-up run at, say, 15:00
would refuse anyway, because the default date would then be tonight.

## Running it by hand

It is the same program; running it by hand is the manual trigger.

**On the VPS**, as `pos` in `/opt/supreme`:

```
docker compose run --rm --build nightwatch              # last night; only between 05:00 and 10:00
docker compose run --rm --build nightwatch 2026-09-23   # any night, any hour
sudo systemctl start supreme-nightwatch.service         # exactly what the timer does
journalctl -u supreme-nightwatch -n 30                  # what the last run said
```

**On the Mac** (against the seeded demo database, never the hall's), in `~/SupremeBilliards/ops/nightwatch`:

```
npm ci
NIGHTWATCH_DB_URL=postgres://nightwatch_ro@localhost:5432/supreme \
NIGHTWATCH_DB_PASSWORD=... NIGHTWATCH_APP_URL=http://localhost:8080 \
node src/main.ts 2026-09-15
```

On the Mac, create the role as the installer's `postgres` superuser. `supreme` there has no
CREATEROLE:

```
NIGHTWATCH_DB_PASSWORD=... /Library/PostgreSQL/18/bin/psql -h localhost -U postgres -d supreme \
  -f ops/nightwatch/sql/create-role.sql
```

Leave `TELEGRAM_BOT_TOKEN` empty and it prints what it would have sent:

```
checking the night of Wednesday 23 September (business_date 2026-09-23), Manila time 04:16
app: http://host.docker.internal:8080/api/v1/time answered 401
telegram disabled (TELEGRAM_BOT_TOKEN empty), would send: Supreme Billiards: the night of Wednesday 23 September was never closed. Nobody counted the drawer.
```

`npm run typecheck` checks the types. There is no build step: Node 22 runs the `.ts` files
directly by stripping the types, which is why `tsconfig.json` sets `erasableSyntaxOnly`.
