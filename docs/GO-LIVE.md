# Go-Live Checklist — Saturday 5 September 2026

Work through this in order. The ordering matters in one place, marked **ORDER CRITICAL**.

Operational questions during the week — how to start it, what to do if it won't — are in
`docs/RUNBOOK.md`. This file is only about getting to opening night.

---

## Now — the visual check (5 minutes)

The last three gate items from the product-image work are unverified. Do them yourself; you need to
know this app anyway.

- [ ] Log in at `http://localhost:8080` as `owner` / `TEMPORARY-owner-1`
- [ ] Admin → Products → upload an image to one product, confirm the preview
- [ ] Log in as `counter` / `TEMPORARY-counter-1`, open a table, look at the product grid
- [ ] **Judge the mixed grid.** One product with a photo, the rest with initials tiles. Does it look
      deliberate or broken? This is the normal state for months — if the placeholders read as errors,
      say so and it gets fixed.
- [ ] Confirm the counter has no upload control anywhere

---

## Thursday — the catalogue (allow half a day, with the owner)

This is the step that always overruns. It needs the owner, not you alone.

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

## Friday — dry run and staff

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

## Saturday, before opening — ORDER CRITICAL

Run these **in this order**. The reset must be last because `mvn test` writes real rows into the
database, so any testing after the reset re-pollutes it.

1. - [ ] Finish all testing. No more `mvn test` after this point
2. - [ ] `scripts/reset-for-golive.sh` — wipes test data and **prompts for the real passwords**
3. - [ ] Re-enter the catalogue **or** restore it — the reset wipes products too. Decide in advance
        which: either enter the catalogue after the reset on Saturday, or accept re-entering it
3b. - [ ] **Re-enter the standard float** — the reset returns it to ₱0. Admin → Settings → Float
4. - [ ] Confirm the new passwords work for both `owner` and `counter`
5. - [ ] Confirm 7 tables, correct rates, 0 bills, 0 payments
6. - [ ] Run one backup and confirm it lands in `~/SupremeBackups` **and** iCloud
7. - [ ] Reboot the Mac one final time and confirm the till comes back by itself
8. - [ ] Write the new passwords somewhere the owner can reach them and you are not the single point
        of failure

> **Decide Thursday, not Saturday:** entering the catalogue before the reset means entering it twice.
> Entering it after means doing it on opening day. Given the reset also sets the passwords, the
> cleanest order is: reset Saturday morning → enter catalogue → open. Budget the time for it.

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

---

## Known limitations, so they are not discovered as surprises

- **One payment per bill.** A merged bill must be settled by a single method. Watch for quick sales
  appearing at checkout time beside a large bill — that is staff working around it
- **No table transfer, no merge.** Both were deliberately cut. A customer moving tables means closing
  one session and opening another
- **No refunds** against a settled bill
- **Employees can set any friend rate**, with no limit and no approval. Fully logged
- **A stock correction cannot be undone** — post another correction; the ledger is append-only
- **No PDF export yet.** Read the dashboard, or take a screenshot

---

## If something goes wrong on the night

`docs/RUNBOOK.md`, and the honest fallback: **paper chits, finish the night, fix it Sunday.** The
system existing does not make it worth losing a Saturday's takings to.
