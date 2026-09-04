# Supreme Billiard Hall POS — Runbook

For whoever is looking after the system. You do not need to know anything about programming to
use this page.

**The Mac at the counter runs the POS. It must stay switched on and logged in.** If it is off,
asleep or logged out, the POS does not work and the nightly backup does not run.

---

## Every night

**Opening**

1. Wake the Mac. Do not log out.
2. Double-click **`scripts/start.sh`**.
3. Wait for it to say **READY**.
4. Open `http://localhost:8080` and log in. That one address is the whole till — the screens
   and the data both come from it. There is no second program to start.

**Closing**

1. Make sure every table is closed on screen. The system will not let you close the day with a
   table still running, and it will tell you which one.
2. Count **everything in the drawer**, float included, and enter that one total. The system knows
   what the float should be and what was taken in cash, works out what the drawer *should* hold,
   and shows the difference.
3. **Take the night's takings out and leave the standard float in.** This is the one step the
   system depends on and cannot check.

   The count assumes the drawer starts each night with the float and nothing else. If the whole
   drawer is left in overnight, the next night starts with the float *plus* last night's takings
   still sitting there — so that count reads over by exactly the amount that was left, and so does
   every count after it until someone takes the money out. The figures will look wrong for days and
   the reason will be a week behind you.

   Count the takings out, put them wherever the cash goes, and leave the float. If the float
   itself has to change, change it in **Admin → Settings**, not by leaving extra money in the
   drawer.
4. Close the business day.
5. **Leave the Mac on.** The backup runs at 05:30 while you are asleep.

You do not need to stop the POS overnight. Only stop it if you are told to, or if you are
restoring a backup.

---

## Is it healthy?

Four things, thirty seconds:

| Check | How | Good |
|---|---|---|
| POS is running | Open `http://localhost:8080` | The login page appears |
| The screens are being served | The login page has the Supreme logo and two boxes | If you get a bare error instead, the app started but the screens did not build — see the log |
| Database is running | Double-click `scripts/start.sh` | Says "already running", or starts it |
| Last night's backup exists | Open the **SupremeBackups** folder in your home folder | A file named `supreme-<last night's date>.dump` |
| It also reached the cloud | Open **iCloud Drive → SupremeBackups** | The same filename is there |

**The backup check is the one people forget.** If there is no file from last night, something is
wrong even though the POS looks fine. See *The backup did not run* below.

---

## It will not start

Work down this list. Stop when it works.

**1. It says the database is not running.**

Open Terminal and paste this line, then press Enter and type your Mac password:

```
sudo launchctl kickstart -k system/com.edb.launchd.postgresql-18
```

Wait ten seconds, then run `scripts/start.sh` again.

**2. It says something is already using port 8080.**

Restart the Mac. Then run `scripts/start.sh`.

**3. It says the POS did not finish starting.**

Run `scripts/stop.sh`, then `scripts/start.sh` once more. If it fails the same way twice, the log
file it names on screen has the reason — send that file when you call for help.

**4. Nothing works and the hall is open.**

**Fall back to paper.** Write down table start times and orders. Nothing is lost: everything can be
entered afterwards. This is why the paper chits run in parallel for the first week.

---

## The till restarted by itself

The Mac is set to restart the POS automatically if it ever stops. That is deliberate — it means a
problem at 11pm on a Saturday fixes itself instead of ending the night. But there are two very
different situations behind it, and they need different responses.

**Normal — a blip.** The screen goes blank or says it cannot reach the till, and about **ten
seconds** later it works again on its own. Nothing is lost: open sessions, bills and payments are
all in the database, not in the app's memory. Carry on serving. Mention it the next morning so it
can be looked at, but it is not an emergency.

**Not normal — it keeps dropping.** It comes back, then dies again, over and over through service,
roughly every half minute. That is a crash loop — the Mac deliberately slows the retries down to
one every 30 seconds so a broken app cannot thrash. The system is not going to recover on its own.

Do this:

1. Run `scripts/stop.sh`. That takes it out of the automatic restart properly, instead of leaving
   it thrashing.
2. **Fall back to paper** and keep serving.
3. Call. Say "the till is in a restart loop" and send today's log file.

### Telling the two apart

Each automatic start writes one timestamped line. To count them, paste this into Terminal:

```
grep "launchd start" ~/SupremeBackups/pos-launchd.log | tail -20
```

One or two lines a day, at opening time, is normal — that is the Mac logging in. **Many lines
grouped within a few minutes is a crash loop**, and the reason will be in
`~/SupremeBilliards/logs/app-<date>.log`.

---

## The backup did not run

The backup runs at 05:30 every night, but only if the Mac is on and logged in.

**Run one right now, by hand:** double-click **`scripts/backup.sh`**. It takes a few seconds and
prints what it did.

**If it has been failing for more than a night**, run this in Terminal to see whether the schedule
is still installed:

```
launchctl list | grep supreme
```

If nothing comes back, reinstall it:

```
~/SupremeBilliards/scripts/install-backup.sh
```

---

## Restore last night

Do this only if data has been lost or corrupted. **It replaces everything currently in the system
with the backup's contents.** Anything recorded since that backup is gone.

1. **Stop the POS.** Double-click `scripts/stop.sh`.
2. Run `scripts/restore.sh` from Terminal:
   ```
   ~/SupremeBilliards/scripts/restore.sh
   ```
   It shows you which backup it is about to use and makes you type `yes`.
3. It prints the last few business days and their takings. Check those look right.
4. Start the POS again: `scripts/start.sh`.

To restore a specific night rather than the most recent, pass the file:

```
~/SupremeBilliards/scripts/restore.sh ~/SupremeBackups/supreme-20260830-053000.dump
```

### Check the backups are real

Once a month, and after anything changes, run:

```
~/SupremeBilliards/scripts/verify-backup.sh
```

This restores the most recent backup into a **throwaway** copy, prints the figures that came back,
and deletes the copy. It never touches the live system. An untested backup is a guess.

It has been run, and it passes: a real backup was restored and the reference night came back as
gross ₱630.00, cost ₱157.50, profit ₱472.50 — the same figures the live system holds.

---

## Starting the database over

There are two scripts that erase the database, and the difference between them matters.

| | `reset-for-testing.sh` | `reset-for-golive.sh` |
|---|---|---|
| For | This week, while you try things out | Once, the day the hall opens |
| Asks | `y` | The phrase `DESTROY n PAYMENTS`, typed out |
| Passwords | Puts the temporary ones straight back | Asks you for the two real ones, twice each |
| After Saturday | **Refuses to run** | The only one left |

### Testing — this week only

```
~/SupremeBilliards/scripts/reset-for-testing.sh
```

Wipes the database, rebuilds it from the migrations, and puts the two temporary logins back —
`owner` (ADMIN) and `counter` (EMPLOYEE). The passwords it writes are in the script itself,
which lives only on the POS machine. They are deliberately not repeated here: this file is
tracked, so anything written in it is published to everyone with repo access and is wrong the
moment `reset-for-golive.sh` rotates it.

One keypress to confirm, no password prompts. That is deliberate — you will run it many times
in an evening, and anything slower just means you stop using it and test on dirty data instead.

**This script is dead after Saturday, by design.** The go-live reset writes a `.golive` marker
in the project folder as its last act, and this script reads that marker and refuses. A script
that wipes a database on one keypress has no business anywhere near real takings, and the catch
does not depend on anyone remembering — it is the file's job.

If you see it refuse, that is correct. Use `restore.sh` to recover a broken database.

### Go-live — once, and then never again

```
~/SupremeBilliards/scripts/reset-for-golive.sh
```

This is the act that starts the hall. It erases every test session, bill and payment, asks you
to set the two real passwords, and writes the `.golive` marker.

It names how many payments it is about to destroy and makes you type the phrase out, so it
cannot be run by accident once the hall is trading. **Do not delete `.golive` afterwards.**

---

## Where everything lives

| What | Where |
|---|---|
| The POS itself | `~/SupremeBilliards` |
| Backups (main copy) | `~/SupremeBackups` |
| Backups (cloud copy) | iCloud Drive → `SupremeBackups` |
| Payment photos | `~/SupremeData/payment-photos` |
| Product pictures | `~/SupremeData/product-images` |
| Today's log | `~/SupremeBilliards/logs/app-<date>.log` |
| Backup log | `~/SupremeBackups/backup.log` |
| Start-up / restart log | `~/SupremeBackups/pos-launchd.log` |

Both live under `~/SupremeData`, **not** inside the project folder, on purpose: they must survive
the software being rebuilt or reinstalled. The nightly backup archives that whole folder, so
anything the app saves there is covered without the script having to be changed again.

### What the nightly backup contains

| File | What is in it |
|---|---|
| `supreme-<stamp>.dump` | The database — every bill, session, payment and product |
| `data-<stamp>.tar.gz` | `~/SupremeData` entire: payment photos **and** product pictures |

Archives named `photos-<stamp>.tar.gz` are from before product pictures existed and hold payment
photos only. They still age out on the same 14-day retention.

---

## About the two copies

Every backup is written twice:

1. **`~/SupremeBackups`** on this Mac — always really on the disk.
2. **iCloud Drive → `SupremeBackups`** — the copy that survives this Mac being stolen, dropped or
   wiped.

**One thing to know about the iCloud copy.** This Mac has *Optimize Mac Storage* switched on, so
when the disk gets full macOS may remove the local copy of an iCloud file and leave a placeholder
that downloads again when you open it. In Finder those show a small cloud icon.

That is fine, and it is the reason the main copy is **not** in iCloud. But it means:

- If you ever restore from the iCloud copy, **be online**, and let the file finish downloading
  before you run the restore. A placeholder is not a backup until it has come down.
- To force a copy back onto the disk, right-click it in Finder and choose **Download Now**.

If you would rather never deal with that, turn *Optimize Mac Storage* off in
**System Settings → [your name] → iCloud → iCloud Drive**. The backups are small — a few hundred
kilobytes a night — so keeping them all on disk costs almost nothing.

---

## After a power cut

The Mac loses power, comes back, and the till needs to be working before the first customer.

**What happens on its own:** PostgreSQL restarts by itself — it is installed as a system
service. The POS does **not**, unless the auto-start has been installed (below). Without it,
the database is up and the folder is fine, but nothing is listening and the browser shows
nothing. That looks like a broken system and is not.

**What to do if the POS is not running:**

1. Log in to the Mac if it is sitting at the login screen.
2. Double-click `scripts/start.sh`, or run it from Terminal.
3. Wait for it to say **READY**. It takes about a minute.

**To make it start by itself,** run this once:

```
bash scripts/install-autostart.sh
```

Two things it needs, and it will tell you if either is missing:

- **The project must not live in Desktop, Documents or Downloads.** macOS refuses startup jobs
  any access to those folders — the job would fail silently at every boot. The installer checks
  this and refuses rather than installing something that never works. Move the folder first,
  for example to `~/SupremeBilliards`, then run the installer again.
- **The Mac must log in on its own.** System Settings → Users & Groups → Automatic login. If it
  stops at the login screen after a power cut, nothing starts until a human types the password.

**Set these two together, not one or the other:**

| Setting | Where | Why |
|---|---|---|
| Automatic login **on** | Users & Groups | So a power cut brings the till back without anybody typing a password at 6pm |
| Require password after the screen saver | Lock Screen | So the Mac locks itself while unattended during service |

Automatic login alone leaves the desktop open to anyone who walks behind the counter. The screen
lock closes that without stopping the machine from recovering on its own. The POS itself always
asks for a password regardless, so what is at stake here is the desktop, not the till.

Its log is `~/SupremeBackups/pos-launchd.log`. Test it without rebooting:

```
launchctl kickstart -k gui/$(id -u)/com.supreme.billiards.pos
```

---

## Starting the hall from clean — once, and never again

`scripts/reset-for-golive.sh` erases the entire database and rebuilds it empty: no bills, no
payments, no stock movements, seven pool tables, two logins. It exists for one moment — the day
you go live, after testing and before the first real customer. It also sets proper passwords,
replacing the shipped `owner123` / `counter123`.

**Once the hall is trading, this script must never be run again.** There is no undo. It refuses
to run when the database holds payments unless you type an exact phrase naming how many you are
about to destroy, so it cannot go off by accident — but do not rely on that. Treat it the way
you would treat a match in a stockroom.

If you ever need to get back to a known state *after* go-live, that is a **restore from backup**
(see "Restore last night"), not a reset.

What it asks for: the confirmation phrase, then the two new passwords, twice each. Write them
down before you start — there is no way to recover them afterwards, only to set new ones.

**Run it last.** The automated tests write real rows into this database — a handful of bills
and payments survive every `mvn test` run, because the concurrency tests deliberately commit
outside a transaction. So if anyone runs the tests after the reset, the hall is no longer
starting from empty. The order on go-live day is: finish all testing, *then* reset, then open.

---

## Who to call, and what to say

Have these ready before you call — they turn a twenty-minute conversation into a two-minute one:

1. **What you were doing** when it went wrong ("closing table 3", "taking a GCash payment").
2. **What it said on screen**, word for word. A photo of the screen is ideal.
3. **What time it happened**, roughly.
4. **The log file**: `logs/app-<today>.log` inside the project folder. If the auto-start was
   involved, `~/SupremeBackups/pos-launchd.log` as well.
5. **Whether the backup from last night exists** in `~/SupremeBackups`.

If the hall is open and the POS is down: **go to paper, and call afterwards.** The system can
always catch up. A queue at the counter cannot.

---

## What this system will not let you do

These are deliberate, not faults. If staff report them as bugs, they are working as intended.

- **Open two sessions on the same table.** The second attempt is refused.
- **Close the day with a table still running.** It names the table.
- **Close the day without counting the drawer.**
- **Void a line without typing a reason.**
- **Take a payment for the wrong amount.** It must match the bill exactly. Cash overpayment is
  entered as the amount handed over, and the system works out the change.
- **Charge twice for one bill.** A double-click or a retry records one payment, not two.
- **Delete anything.** Voided items stay on the bill, marked. Stock corrections add a correcting
  entry rather than editing the old one. This is what makes the audit trail trustworthy.

Selling an item you have run out of **is** allowed — it warns and records the sale. A wrong stock
count must never stop a paying customer.
