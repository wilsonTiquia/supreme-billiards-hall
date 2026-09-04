# Status — 3 September 2026

Go-live is **Saturday 5 September**. The build is complete and deployed. What remains is people work.

New session? Read `CLAUDE.md`, `frontend/CLAUDE.md`, `docs/API-CONTRACT.md` and `docs/GO-LIVE.md`.
Every decision made during the build is recorded in those, deliberately.

---

## What exists

Full POS, built and running on `http://localhost:8080` from this machine.

- Backend: Spring Boot 4.0.1, Java 21, PostgreSQL 18, Flyway v8, 51+ endpoints, 35 tests green
- Frontend: React + Vite + TypeScript + Tailwind, built into the jar, no dev server in production
- Deployment: launchd-supervised, survives a reboot unattended, `KeepAlive` on
- Backups: hourly during opening hours + nightly full, encrypted, `~/SupremeBackups` and iCloud,
  restore proven from both
- Scripts: `start.sh`, `stop.sh`, `rebuild.sh`, `backup.sh`, `restore.sh`, `verify-backup.sh`,
  `reset-for-testing.sh`, `reset-for-golive.sh`

Current logins are **temporary** and are two accounts, `owner` (ADMIN) and `counter` (EMPLOYEE).
The passwords are the ones `scripts/reset-for-testing.sh` writes; read them there, on the POS
machine. They are not printed here because `reset-for-golive.sh` replaces them on Saturday, and
a password written into a tracked document is both wrong the moment it is rotated and published
to everyone with repo access until then.

---

## In flight

**Responsive layout** — making every screen work at 375 / 768 / 1024 / 1440 / 2240 with all controls
reachable and ≥44px. The counter monitor must not regress; the owner's phone matters because he has
Tailscale access specifically to check takings from home.

---

## Still to do, in order

### Thursday — catalogue, with the owner (allow half a day)
- Every product: name, category, selling price, opening stock
- Opening stock entered as a **stock delivery**, not a typed cost — that is what makes margin correct
- Confirm 7 tables and rates (4 standard ₱4.00/min, 3 premium ₱5.00/min)
- **Set the standard change float** (Admin → Settings). Ask what he keeps in the till for change.
  If left at ₱0 while a float is physically in the drawer, every night's variance is wrong by that
  amount and the control gets ignored within a week
- Tell him about the three giveaway routes — friend rate, give-away, reduced time. None has a limit
  or an approval step. The dashboard losses section is the only control on any of them
- Product photos optional; the grid works without them

### Friday — rehearsal
- Full dry run yourself, end to end
- Staff walkthrough **with them driving**, twice each
- Show them the unsettled-bills strip, pause, and that paper chits run alongside for week one
- `scripts/verify-backup.sh` now that real data exists

### Saturday, before opening — ORDER CRITICAL
1. Stop all testing. No more `mvn test` — it writes real rows
2. `scripts/reset-for-golive.sh` — wipes test data, prompts for **real passwords**, arms `.golive`
3. Re-enter the catalogue (the reset wipes products)
4. Re-enter the standard float (the reset returns it to ₱0)
5. Confirm new passwords, 7 tables, 0 bills, 0 payments
6. One backup, confirm it reaches both locations
7. Final reboot, confirm the till returns unattended
8. Write the passwords somewhere the owner can reach them

> Decide in advance: entering the catalogue before the reset means entering it twice. The clean order
> is reset first, then catalogue. Budget the time.

---

## Known limitations — say these out loud, do not let them be discovered

- **One payment per bill.** A merged bill settles by a single method
- **No table transfer, no merge.** Moving a customer means closing one session and opening another
- **No refunds** against a settled bill
- **Any employee can set any friend rate, discount any time, give away any stock** — no limits, no
  approvals, all logged
- **A stock correction cannot be undone** — post another; the ledger is append-only
- **No PDF export**
- **`.golive` must never be deleted.** It is the only thing stopping `reset-for-testing.sh` from
  wiping a trading database

---

## Week one

- Reconcile paper chits against the system nightly until a full week agrees. Any disagreement is the
  most valuable information available
- Watch the friend-rate and give-away totals nightly for the first month
- Watch the cash variance — a pattern of small shortfalls matters more than one large one
- Note every hesitation or fumble by staff. That list is the real design brief for any polish pass,
  and it is better evidence than anything decided in advance

## If it breaks on the night

`docs/RUNBOOK.md`. And the honest fallback: **paper chits, finish the night, fix it Sunday.**
