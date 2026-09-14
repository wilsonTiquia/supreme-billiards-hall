# Go-Live Checklist — the day the hall takes real money

Work through this in order. The ordering matters in two places, both marked **ORDER CRITICAL**.

Operational questions during the week — how to start it, what to do if it won't — are in
`docs/RUNBOOK.md`. This file is only about getting to the first real customer.

**The date in this file used to be Saturday 5 September 2026. That day has passed and the hall
has not gone live.** The system is still in testing: there is no `.golive` marker in the project
folder, `scripts/reset-for-testing.sh` still runs, and the database still carries whatever the
week's testing put in it. Nothing below assumes a date. It assumes the morning of whichever day
the owner decides to open, and it is written to be followed on that morning.

---

## Walked, or only written down

Every line in this file is one of two things, and the difference is the point of the document.

- **Walked** — somebody has done it on this machine and watched it work.
- **[NEVER WALKED]** — it exists in code, in tests, or in a procedure, and no one has performed it
  on the Mac at the venue. Three passing tests are not a walked procedure. A test proves the code
  behaves; it does not prove the person can do it at 11pm with the counter waiting, and it does not
  prove the surrounding machine — launchd, the environment, the file layout — lets them.

Items carrying that marker are the ones to do first, because they are the ones that can still
surprise you. **Delete the marker when you have walked it**, and not before.

---

## The visual check (5 minutes)

The last three gate items from the product-image work are unverified. Do them yourself; you need to
know this app anyway.

- [ ] Log in at `http://localhost:8080` as the **owner** account (the temporary password is in
      `scripts/reset-for-testing.sh`, on the POS machine — not in this file)
- [ ] Admin → Products → upload an image to one product, confirm the preview
- [ ] Log in as the **counter** account, open a table, look at the product grid
- [ ] **Judge the mixed grid.** One product with a photo, the rest with initials tiles. Does it look
      deliberate or broken? This is the normal state for months — if the placeholders read as errors,
      say so and it gets fixed.
- [ ] Confirm the counter has no upload control anywhere

---

## Break-glass, on the running system — do this before the day [NEVER WALKED]

**A forgotten administrator password is recovered at the database, and nobody has ever done it
here.** The procedure is in `HELP.md` ("Recovering a forgotten administrator password"), it is
covered by three tests, and it has never been run on the machine it exists for. It is the only way
back into a hall whose owner has forgotten their password, so the first time it is attempted must
not be the night it is needed.

**Walk it, with the counter closed.** It restarts the app, so it cannot be done over a live table.

- [ ] Pick a quiet hour. **Close the counter** — no open sessions, no unsettled bills mid-flight
- [ ] Take a backup first: `scripts/backup.sh`. You are about to overwrite a password hash by hand
- [ ] Follow `HELP.md` end to end against a **second, disposable admin account**, not the owner's.
      Create one in Admin → Staff for the purpose and archive it afterwards
- [ ] **Confirm the log line.** The app logs at INFO that it initialised the password. If that line
      is absent, the bootstrap did not fire and the account is now disabled with no way in — restore
      the backup rather than guessing
- [ ] Log in as that account with the new password, change it, then **unset
      `SUPREME_BOOTSTRAP_ADMIN_PASSWORD`** and restart again
- [ ] Archive the disposable account
- [ ] **Write down what actually happened**, including anything `HELP.md` got wrong, and fix
      `HELP.md` the same day

> **The trap this walkthrough is most likely to hit.** `HELP.md` step 2 says
> `export SUPREME_BOOTSTRAP_ADMIN_PASSWORD=...`. **That export will not reach the app** if the app
> is running under launchd, which it is: `scripts/start.sh` sets no environment beyond `PATH` and
> sources no env file, so a variable exported in your terminal is invisible to the launchd job. You
> must either run the app in the foreground from the same shell that holds the export (after
> `scripts/stop.sh`, which boots the job out of launchd so `KeepAlive` cannot restart it behind
> you), or set it with `launchctl setenv` before reloading the job. **This has not been verified
> either way** — it is the first thing the walkthrough will find out, and the answer belongs in
> `HELP.md` when it does.

---

## The catalogue (allow half a day, with the owner)

This is the step that always overruns. It needs the owner, not you alone.

**Read the ORDER CRITICAL section before you start**: the go-live reset wipes products, so where
this sits in the day decides whether you enter the catalogue once or twice.

- [ ] **Enter every product**: name, category, selling price, current stock count
- [ ] **Enter purchase costs** via a stock delivery, not by typing a cost — the moving average is
      computed from deliveries, and entering opening stock as a delivery is what makes margin correct
      from day one
- [ ] **Confirm the 7 tables and rates** match the real room — 4 standard at ₱4.00/min, 3 premium at
      ₱5.00/min. Add or rename tables if the room differs
- [ ] **Confirm the customer types** — `Regular` and `Friend of Owner`. Make sure the owner
      understands that a friend rate can be set to anything by any employee, and is logged
- [ ] **Set the standard change float** — Admin → Settings → Float. Ask the owner what he actually
      keeps in the drawer to make change. If it stays at ₱0 while a float is physically in the till,
      every night's variance is wrong by exactly that amount and the control gets ignored inside a
      week
- [ ] **Photos are optional.** Shoot the top ten sellers if there's time. The grid works without them
      and half-populated is fine — do not let this block the evening

---

## Dry run and staff

- [ ] **Full dry run yourself**: open a table, order, void with a reason, close, take payment, print
      nothing, close the business day with a cash count
- [ ] **Staff walkthrough with them driving**, not you demonstrating. Each person runs a full table
      start to finish at least twice
- [ ] Show them the **unsettled bills strip** and what it means — a bill there is money not collected
- [ ] Show them **pause** and when to use it (brownout, table fault, customer steps out)
- [ ] Explain **paper chits run in parallel for week one**, and why: if the system is wrong about a
      bill, the chit is how you find out
- [ ] Confirm they know the till lives at `http://localhost:8080` and nothing else
- [ ] **Verify a backup and a restore** after the catalogue is entered —
      `scripts/verify-backup.sh`. The last restore proof was against different data

---

## Stop the machine building itself — before the day [NEVER WALKED]

**Something rebuilds under the running till, unattended, and it is not fully explained.** Three
occurrences are recorded — 00:50, 01:54 and 02:24 on 8 September, the last of them nineteen minutes
before the commit it half-contained. It is harmless while the data is disposable. It stops being
harmless the moment the database holds real takings, because the failure mode is the one
`scripts/rebuild.sh` exists to prevent: the jar is replaced under a live JVM, the API keeps
answering, and the SPA stops loading — a till that looks alive and cannot be used.

What has been established:

- **IntelliJ IDEA CE has this project open with `MAKE_PROJECT_ON_SAVE` set to `true`** in
  `.idea/workspace.xml`. It builds on every save: 56 builds on 8 September, 146 on 9 September,
  firing about fifteen seconds apart while anyone is typing. Each one cleans the output
  directories under `target/`. One fired twenty seconds before the 00:50 restart.
- **It runs no Maven goal**, so it does not by itself produce the Spring Boot fat jar. The jar
  rewrite is therefore narrowed but **not explained**. Something else packages, or the restarts
  have a second cause.
- `.idea/` is gitignored, so this is machine-local state. A new machine will not inherit it — and
  will not inherit the containment either.

The containment is cheap and should not wait for the explanation:

- [ ] **Close IntelliJ on the POS machine**, or turn off Build project automatically
      (Settings → Build, Execution, Deployment → Compiler → Build project automatically)
- [ ] Note the jar's timestamp: `ls -l target/*.jar`
- [ ] Leave the till running untouched for an hour, then check it again. **If it moved, the cause
      is not the IDE** and it must be found before the hall takes money
- [ ] Whatever the answer, record it in `docs/RUNBOOK.md` — this is an operational fact about the
      machine, not a development note

---

## Rebuilding on new hardware [NEVER WALKED]

**A clone of this repository will not run the hall.** Two things are deliberately not in git and
one is not in the backup either, and that gap is only discovered on the day the Mac dies.

| What | Where it lives | In git? | In the backup? |
|---|---|---|---|
| Application source | this repository | yes | no |
| `scripts/` — start, stop, backup, restore, both resets | POS machine only | **no** (`scripts/*` is ignored; only `e2e-backend.sh` is exempt) | **no** |
| Database — every sale, every user, every password hash | PostgreSQL | no | yes (`supreme-*.dump`) |
| Product images and payment photos | `~/SupremeData` | no | yes (`data-*.tar.gz`) |
| `.golive` marker | project folder | no | **no** |

`scripts/` is excluded on purpose — those files hold the database password and the temporary
logins, and `.gitignore` says so. The consequence is that **the scripts exist in exactly one place
in the world**, and `scripts/backup.sh` backs up the database and `~/SupremeData`, not the project
folder.

- [ ] **Copy `scripts/` somewhere that is not the POS machine**, today. Not into this repository —
      the reason it is excluded has not changed. An encrypted archive alongside the offsite backups,
      or wherever the owner keeps the passwords from item 8 below
- [ ] **Write the rebuild procedure down and then walk it**, on a spare Mac or a fresh user account:
      install PostgreSQL and the JDK per `docs/SETUP.md`, clone the repo, restore `scripts/` from
      that copy, `scripts/restore.sh` the most recent dump, untar `~/SupremeData`, build once, run
      `scripts/install-autostart.sh` and `scripts/install-backup.sh`, confirm the till answers and
      the catalogue is there
- [ ] **Recreate `.golive`** as part of that procedure — see the next section for why it will not be
      there and why that matters more than it looks

> Until that walk has happened, the recovery time after a hardware failure is unknown. That is the
> honest state of it: there is a backup, it is verified, and nobody has ever rebuilt from it.

---

## The `.golive` marker does not survive a rebuild — decide this before the day

`scripts/reset-for-golive.sh` writes a `.golive` file in the project folder as its last act, and
`scripts/reset-for-testing.sh` reads that file and refuses to run. That is the only thing standing
between a one-keypress database wipe and a hall's real takings.

**The marker is a file in the project folder, and the project folder is not backed up, not in git,
and not restored.** Rebuild on new hardware and the marker is gone — while the database, restored
from the dump, is full of real money. `reset-for-testing.sh` would run happily, and it asks for one
keypress.

**Recommendation: move the flag into the database, and make the refusal evidence-based.** The
marker should live in the thing it protects. A file beside the code protects the code; what is at
risk is the database, and the database is the one thing that does travel to new hardware. If
`reset-for-golive.sh` writes a `branch_setting` row — `golive_at`, with the timestamp — and
`reset-for-testing.sh` refuses when it finds one, then the protection is restored by the same
`restore.sh` that restores the takings, automatically, with nothing for anyone to remember. The
project already uses exactly this reasoning in `frontend/playwright.config.ts`, which refuses to
run against a database holding `payment` rows and carrying no scratch marker, on the grounds that
it is "the only check that does not depend on somebody having named things correctly". Keep the
file check too — it is instant and needs no database — but make the row the authoritative one.

**That change is not built.** Until it is, the marker has to be recreated by hand, and a step
somebody has to remember is exactly the kind of protection that fails.

- [ ] **Decide** whether to build the database-row check before opening. It is a small change to two
      scripts and one `INSERT`
- [ ] If not built: add "recreate `.golive`" to the rebuild procedure above, and **test the refusal
      after any restore** — run `scripts/reset-for-testing.sh` and confirm it refuses, then answer
      no. Never confirm it. If it does not refuse, stop and fix that before anything else

---

## The morning of — ORDER CRITICAL

Run these **in this order**. Two orderings matter and neither is obvious:

1. **All testing finishes before either reset.** `mvn test` writes real rows into whatever database
   it is pointed at, and the concurrency tests commit on purpose, so a suite run after the reset
   re-pollutes the takings. See `docs/RUNBOOK.md`, "Never run the test suite against the trading
   database".
2. **Both resets wipe the database, so the catalogue is entered after the second one, not between
   them.** Entering it in between means entering it twice.

- [ ] 1. **Finish all testing and all final clicking.** No more `mvn test`, no more dry runs, no more
       demonstrating to staff. Everything that puts a row in the database is now behind you
- [ ] 2. **`scripts/reset-for-testing.sh`** — clears the accumulated test data and puts the system
       back to a clean, known state. One keypress. This is the last time this script will ever run
- [ ] 3. **`scripts/reset-for-golive.sh`** — wipes again, **prompts for the real passwords**, and
       **arms the protection** by writing `.golive`. After this, `reset-for-testing.sh` refuses
       for ever
- [ ] 4. **Confirm the protection is armed**: run `scripts/reset-for-testing.sh` and confirm it
       refuses and names the marker. Then answer no. **Never confirm it**
- [ ] 5. **Enter the catalogue** — products, costs via deliveries, tables, rates, customer types.
       The reset wiped them. This is the half-day; it is on the critical path and it is on opening
       day, which is why the callout below asks you to decide the shape of the morning in advance
- [ ] 6. **Re-enter the standard float** — the reset returns it to ₱0. Admin → Settings → Float
- [ ] 7. Confirm the new passwords work for both `owner` and `counter`
- [ ] 8. Confirm 7 tables, correct rates, 0 bills, 0 payments
- [ ] 9. Run one backup and confirm it lands in `~/SupremeBackups` **and** iCloud
- [ ] 10. Reboot the Mac one final time and confirm the till comes back by itself
- [ ] 11. Write the new passwords somewhere the owner can reach them and you are not the single
        point of failure
- [ ] 12. **Set a real `DB_PASSWORD` in the environment.** Production must not run on the
        `${DB_PASSWORD:supreme}` fallback in `application.properties`. Set `DB_URL`, `DB_USERNAME`
        and `DB_PASSWORD` where the app reads them. **Do not add `SPRING_PROFILES_ACTIVE=prod`
        here** — see the hosting section below for why it would break the till.
        **This is not one change.** `scripts/backup.sh`, `scripts/restore.sh` and
        `scripts/verify-backup.sh` each hardcode `supreme`. A rotation that changes the database
        role and the environment but misses those three leaves the till running normally while the
        nightly backup fails silently — worse than the weak password it replaced. Change the role,
        the environment and all three scripts together, then **re-run item 9** and confirm the
        backup still lands in both places
- [ ] 13. **Confirm `listen_addresses = 'localhost'` in `postgresql.conf`.** Postgres was installed
        listening on every interface, which put 5432 on the venue network behind a password that is
        also written in `docs/SETUP.md`. Verify with
        `netstat -an | grep '\.5432' | grep LISTEN` — it must show `127.0.0.1.5432` and must not
        show `*.5432`

> **Decide the shape of the morning in advance, not on the morning.** The catalogue cannot be
> entered before the resets, because both wipe it. So opening day contains a half-day of data entry
> with the owner, and that is the single biggest risk to the schedule. Either start very early, or
> agree to open later in the day, or accept entering the catalogue twice — once for the dry runs and
> again after the reset. All three are defensible; discovering the problem at 9am on the day is not.

---

## Only when this moves off localhost — the hosting-day step

**Not for opening.** The venue install runs on `http://localhost:8080` over plain HTTP, and the
`prod` profile is the wrong thing there: `server.servlet.session.cookie.secure=true` tells the
browser to send the session cookie only over HTTPS, so on plain HTTP it is never sent back and
nobody can stay logged in. On localhost the profile is a liability, not a hardening. That is why
`scripts/start.sh` deliberately does not set it.

Do all of this on the day the app first answers on a public address, not before:

- [ ] Put a TLS-terminating reverse proxy in front of it. The app itself only ever speaks HTTP
- [ ] Start it with `SPRING_PROFILES_ACTIVE=prod`. That turns on `Secure`, `SameSite=Strict` and
      `HttpOnly` on the session cookie, and `server.forward-headers-strategy=framework` so the
      login lockout sees the real client address instead of the proxy's
- [ ] **Confirm the profile actually took**, in the log: it must say
      `The following 1 profile is active: "prod"`. If it says `No active profile set, falling back
      to 1 default profile: "default"`, none of the above is on and the deployment is not hardened
- [ ] Satisfy the two requirements `application-prod.properties` states, because trusting
      `X-Forwarded-For` is only safe under both: the proxy must **overwrite** that header rather
      than append to a client-supplied one, and the app's own port must not be reachable except
      through the proxy. Verify the first by checking what the app records for a request that
      arrives carrying a forged `X-Forwarded-For`, not by reading the proxy's documentation
- [ ] Cap the request body at the proxy too (`client_max_body_size` in nginx). The app enforces
      `supreme.request.max-body-bytes`, but only the proxy can stop the bytes before they reach
      the JVM
- [ ] Decide where payment photos live. `supreme.payment-photo.path` is a local directory that
      `scripts/backup.sh` tars into `~/SupremeBackups`; neither the directory nor that backup
      exists on a hosted box, and on an ephemeral filesystem the photos vanish on the next deploy
      while `payment.photo_path` still points at them

---

## Opening night

- [ ] You are on site
- [ ] Paper chits alongside, every table
- [ ] At close: run the cash count, close the business day
- [ ] **Reconcile chits against the system.** Any disagreement is the most valuable information
      you will get all week — write it down before you forget

---

## Week one

- [ ] Reconcile chits nightly until a full week agrees
- [ ] Watch the **friend-rate total** on the dashboard every night for the first month. There is no
      floor and no approval — detection is the only control
- [ ] Watch the **cash variance**. A pattern of small shortfalls matters more than one large one
- [ ] Note every moment a staff member hesitates or fumbles. That list is the real design brief for
      the polish pass — better evidence than any redesign done in advance
- [ ] **Check the jar's timestamp once a day** until the unattended-rebuild question above is
      closed. It takes five seconds and the alternative is finding out from a dead till

---

## Known limitations, so they are not discovered as surprises

- **One payment per bill.** A merged bill must be settled by a single method. Watch for quick sales
  appearing at checkout time beside a large bill — that is staff working around it
- **No table transfer, no merge.** Both were deliberately cut. A customer moving tables means closing
  one session and opening another
- **No refunds** against a settled bill
- **Employees can set any friend rate**, with no limit and no approval. Fully logged
- **A stock correction cannot be undone** — post another correction; the ledger is append-only
- **No PDF export from the dashboard.** The period report (Admin → Reports) prints cleanly —
  Print → Save as PDF in the browser gives the month as a PDF, navigation and controls left out,
  with page breaks before the expenses and products sections. The nightly dashboard still has no
  print layout: read it, or take a screenshot
- **Recovery from a dead Mac is untested**, and the scripts that would perform it exist in one
  place. See "Rebuilding on new hardware" above

---

## If something goes wrong on the night

`docs/RUNBOOK.md`, and the honest fallback: **paper chits, finish the night, fix it Sunday.** The
system existing does not make it worth losing a Saturday's takings to.
