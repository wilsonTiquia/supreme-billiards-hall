# Supreme Billiard Hall — POS Backend

Point-of-sale for a billiard hall in Parañaque. Staff open a pool table, a timer runs, food and
drinks attach to that table's bill, one payment closes it. The owner sees the night's revenue and
profit. One branch now, more later.

**Go-live: 5 September 2026.** The hall currently runs on memory and a cash count. Paper chits run
in parallel for the first week.

---

## 0. Before you write any code

This repository already contains a partially built backend written in a style the owner wants
preserved. **Read it first and derive the conventions from it. Do not impose conventions from
elsewhere.**

**The reference slice is `Category`** — read all of these before anything else:

```
controller/CategoryController.java      dto/category/CategoryRequestDTO.java
service/CategoryService.java            dto/category/CategoryResponseDTO.java
service/impl/CategoryServiceImpl.java   dto/APIResponse.java
mapper/CategoryMapper.java              exception/GlobalExceptionHandler.java
repository/CategoryRepository.java       entity/Category.java
```

Cross-check against the `Branch` and `Product` slices. Then state back, in a short list, the
conventions you observed before you write anything:

- Naming (`*Controller`, `*Service` vs `*ServiceImpl`, `*Dto` vs `*Response`/`*Request`, `*Mapper`)
- Package layout (by layer vs by feature) and where a new entity's files go
- How DTOs are separated for request vs response, and whether records or classes are used
- How mapping is done (MapStruct, manual, static factory) and in which direction
- Constructor injection vs field injection; `final` fields; Lombok usage and which annotations
- How exceptions are declared and translated to HTTP (the `Exception` package)
- Validation approach (`jakarta.validation` annotations, where they live)
- Transaction boundaries — which layer carries `@Transactional`
- Test layout and naming, if tests exist

If the existing code is inconsistent between files, ask which file is the reference rather than
picking for yourself.

**Every new feature must be indistinguishable in style from the existing code.** The owner reviews
by reading, and consistency is what makes review fast.

---

## 1. Where the design lives

- **`src/main/resources/db/migration/` is canonical.** The Flyway migrations, in order, are the
  schema. They are what has actually been executed against PostgreSQL 18 and what
  `ddl-auto=validate` checks the entities against on every start. If anything contradicts them —
  this file, a spec, `docs/schema.sql` — the migrations are right and the other thing is stale.
- **`docs/schema.sql` is NOT canonical.** It is the original design baseline: it became
  `V1__baseline.sql` and has not moved since. It is kept for its design commentary, which the
  migrations do not carry — read it for *why* the schema is shaped as it is, and read the
  migrations for *what* is in the database. Do not sync it forward and do not build from it.
  Two canonical files is the drift, not the fix.
- **`docs/BACKEND-SPEC.md`** — the API surface, business rules per endpoint, and build order.
- Entities mirror the migrations. Do not invent columns, and do not let Hibernate generate DDL.
  A schema change is a new migration; `V1__baseline.sql` and every applied migration are frozen,
  because Flyway checksums them and editing one breaks startup.

---

## 2. Reconciling with the existing code

The existing backend was written before this schema. Expect real conflicts.

### Already decided — do not ask, just do it

**Primary keys are `UUID` everywhere. This is settled.** If existing entities use `Long` with
`@GeneratedValue(strategy = IDENTITY)`, convert them. The reason: each branch will run its own
database, and sequential integers would collide the day those databases are merged for consolidated
reporting. There is no data to preserve yet, so this is a code change, not a migration.

How to generate them:

- The database columns already carry `DEFAULT uuidv7()`. Keep that — it is the safety net for any
  row inserted by SQL outside the application.
- In JPA, generate the value **application-side** so an entity has its ID before `persist()`. Use
  Hibernate's `@UuidGenerator` with a **time-ordered** style, not random. Time ordering is the whole
  point: random UUIDv4 keys scatter B-tree inserts and degrade index locality, which is the usual
  reason people claim "UUIDs are slow".
- Verify the ordering rather than trusting it: insert a few rows in sequence and assert the
  generated UUIDs sort in creation order. If the Hibernate version on this project does not offer a
  time-ordered style, say so and propose either `com.fasterxml.uuid:java-uuid-generator` (RFC 9562
  v7) or falling back to the database default — do not silently ship random UUIDs.
- Java type is `java.util.UUID`, column type `uuid`. Never store a UUID as `varchar`.
- Path variables are `UUID` in controllers. A malformed UUID must return **400**, not 500 — handle it
  in the global exception handler.

**The database moves from MySQL to PostgreSQL 18.** `application.properties` currently points at
`jdbc:mysql://localhost:3306/hotel_reservation` — a leftover from another project. Replace the
`mysql-connector-j` dependency with `org.postgresql:postgresql`, add Flyway, and repoint the
datasource. PostgreSQL is not optional here: the schema depends on generated columns, partial
indexes, exclusion constraints with `btree_gist`, native enum types, `jsonb` and `uuidv7()`. None of
that ports to MySQL.

**Timestamps become offset-aware.** Existing entities use `LocalDateTime` with `@CreationTimestamp`.
The schema uses `timestamptz`, so these become `OffsetDateTime` (or `Instant`). `LocalDateTime`
against `timestamptz` silently drops the offset — exactly the bug that makes a 02:00 sale land on
the wrong business day.

### Other conflicts to expect

- **Pool tables hold no session state.** If an existing entity has `startTime`/`endTime` on the
  table itself, that is the old model. Occupancy lives in `table_session`. Migrate it.
- Money is `BigDecimal`, never `double` or `float`.
- Soft deletes (`archived_at`), not hard deletes, anywhere in the catalog.

For anything **not** in the "already decided" list above: **report the conflict and propose the
change before making it.** Do not silently rewrite working code, and do not silently bend the schema
to fit the code. List what you would change and wait for a decision.

---

## 3. Stack — pinned, do not substitute

Already in the project — keep these, do not swap them:

| Concern | Choice |
|---|---|
| Language | Java 21 target (JDK 25 installed locally) |
| Framework | **Spring Boot 4.0.x**, currently **4.0.5** (note: `spring-boot-starter-webmvc`, not `-web`) |
| Build | Maven |
| Mapping | MapStruct + Lombok |
| Persistence | Spring Data JPA for CRUD; **native SQL for reports and aggregates** |

To be added:

| Concern | Choice |
|---|---|
| Database | **PostgreSQL 18** — replaces MySQL |
| Migrations | **Flyway** — `schema.sql` becomes `V1__baseline.sql`, seed is `V2__seed.sql` |
| Auth | Spring Security, server-side session cookie. Not JWT |
| PDF | OpenPDF, server-side |

**"Pinned" means the major and minor, not the patch.** A patch-level bump inside 4.0.x is
expected and does not need asking about — it is how a transitive CVE gets fixed, since the
vulnerable library is usually Spring Security or Tomcat and their versions come from Boot's
dependency management rather than from this `pom.xml`. The commit must name the CVE, the
before and after versions, and where they were confirmed; `git log pom.xml` is the record.

The table said 4.0.1 for a while after the project was already on 4.0.5, which cost a session
the time to flag the difference as an unauthorised substitution. Move the version here in the
same commit as the bump.

Changing the major or minor, or swapping anything else in either table, still needs asking.

`spring.jpa.hibernate.ddl-auto` **must become `validate`**. It is currently `update`, which lets
Hibernate silently reshape the schema — unacceptable for a database holding money, and it would
fight Flyway.

---

## 4. Layer rules

Whatever the existing naming turns out to be, these responsibilities hold:

- **Controller** — HTTP only. Validate input, call one service method, return a DTO. No business
  logic, no repository access, no entity ever crosses this boundary in either direction.
- **DTO** — the API contract. Separate request and response types. Never expose an entity directly;
  never accept one as a request body.
- **Entity** — JPA mapping of a schema table, nothing more. No business logic that spans entities.
- **Mapper** — entity ↔ DTO only. No database access, no business rules.
- **Repository** — data access. Derived queries for simple lookups; `@Query` with native SQL for
  reporting and anything involving `business_date`.
  - In `DAILY_REPORT_SQL` and `LOSSES_DETAIL_SQL`, **do not alias a CTE `d`, `p` or `prev_d`.**
    `params` is in scope from the outer query, so an unqualified `d` inside `to_jsonb()` binds to
    its *date column* rather than to your table alias — which merges a JSON string into the object
    and turns the whole of `losses` into an array. It fails at DTO deserialisation, not in SQL, so
    the error names Jackson and points nowhere near the cause.
- **Service** — all business logic and all transaction boundaries. This is where invariants live.
- **Exception** — domain exceptions, translated centrally to HTTP status codes.

### Mapping traps in this schema

This schema uses Postgres features that JPA does not map naively. Each of these fails at runtime,
not at compile time, so get them right the first time:

- **Generated columns** — `bill.business_date`, `payment.business_date`, `stock_movement.business_date`,
  `audit_log.business_date`, `bill_line.line_total`, `bill_line.line_cost`, `cash_count.variance`.
  Map these `@Column(insertable = false, updatable = false)`. The database computes them; an insert
  that tries to write one will fail. Re-read the entity after insert if you need the value in the
  same transaction.
  - **If anything UPDATEs a column the generated value derives from, the mapping needs
    `@Generated(event = { EventType.INSERT, EventType.UPDATE })`, not `INSERT` alone.** With only
    the insert half, the entity keeps the pre-update value for the rest of the transaction while
    the row in the database says something else. This bit twice — `bill.business_date` (when
    `closed_at` is stamped) and `bill_line.line_total` (when `overrideBilledMinutes` rewrites
    `unit_price`) — and both hid until two operations shared one transaction, because every other
    reader arrived in a later request and got a fresh row. Both are fixed. The rest were swept and
    are genuinely insert-only: `expense`, `payment` and `stock_movement` are all `business_date` on
    rows nothing updates. Do not re-audit them; do check any NEW generated column against this.
  - **Any mapping annotation that tells JPA a write does not happen will discard that write in
    silence, and the gap only surfaces when two operations meet on one row.** There is no error,
    no log line and no failing test until something reads the value in the same transaction that
    wrote it, or a second write depends on the first — which is why all three of these were found
    in use rather than in review: `@Generated(INSERT)` alone on `bill.business_date` and on
    `bill_line.line_total`, then `updatable = false` on `cash_count.counted_at`, where a recount
    could not restamp the row and so re-read as stale for ever. Treat `insertable = false`,
    `updatable = false` and a narrow `@Generated` event set as claims about the whole lifecycle of
    the column, and check them against every path that writes it, not just the one you are adding.
    `@CreationTimestamp` is the same kind of claim and behaves the same way: it keeps the column
    out of every UPDATE, so removing `updatable = false` beside it changes nothing. A column the
    service restamps is stamped by the service, the way `closed_at`, `unsettled_at`, `settled_at`,
    `discount_at` and `redeemed_at` already are. The other `@CreationTimestamp` columns were swept
    and are genuinely write-once — `created_at`, `occurred_at`, `taken_at`, `received_at`,
    `issued_at`, `opened_at` — and nothing restamps any of them. Do not re-audit them.
  - **A test that asserts a write PERSISTED must cross a transaction boundary — a second request,
    or a `flush()` and `clear()` before the read.** This is the other half of the rule above, and
    without it the first half cannot be tested. Reading back through the same persistence context
    returns the in-memory entity, which holds the value the mapping discarded, so the assertion
    passes whether or not the column was written. That is not a weak test, it is one that
    *structurally cannot fail*, which is worse than no test: it reports the bug as fixed.
    `cash_count.counted_at` is the case that proves it — the acceptance test asserted the recount
    cleared the stale flag, went green, and the same sequence against the deployed jar left the
    business day permanently uncloseable, because there the close was a second request that read
    the row from the database.
- **Postgres enum types** — there are seven (`user_role`, `session_status`, `bill_status`,
  `bill_line_kind`, `payment_method`, `session_close_kind`, `stock_reason`). A plain
  `@Enumerated(EnumType.STRING)` maps to `varchar` and will not bind against a native enum column.
  In Hibernate 6 use `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`. Verify with an actual insert.
- **jsonb columns** — `branch_setting.value`, `audit_log.before`/`after`,
  `bill_merge_event.before_snapshot`/`after_snapshot`, `receipt.payload`. Use
  `@JdbcTypeCode(SqlTypes.JSON)`.
- **`bill.version`** is the optimistic lock. Map it `@Version` and let Hibernate manage it — do not
  increment it by hand.
- **Append-only tables** — `stock_movement`, `audit_log` and `receipt` have triggers that reject
  UPDATE and DELETE. Never map a cascade or `orphanRemoval` that could touch them; a cascade delete
  from a parent will blow up at runtime with a `restrict_violation`.
- **Composite foreign keys** carry `branch_id`. Where an association uses one, map the branch column
  as read-only on the child to avoid Hibernate trying to write it twice.

---

## 5. Non-negotiable correctness rules

These are the rules that make this a POS rather than a CRUD app. Violating one is a bug even if the
tests pass.

### Money
- `BigDecimal` everywhere, `numeric(12,2)` in the database. Rates are `numeric(10,4)`.
- **Prices and costs are snapshotted onto `bill_line` at the moment of sale** (`unit_price`,
  `unit_cost`, `description`). Never compute a historical total by joining to `product`. Re-pricing a
  product tomorrow must not change last month's reported profit.
- The same rule applies to table time: `session_segment` stores the rate it was billed at.

### Time
- All timestamps `timestamptz`, stored UTC.
- **The server is the only clock.** The client renders a display counter from a server-supplied
  timestamp; the amount charged is always recomputed server-side at checkout from segments minus
  pauses. Never trust a duration or an amount sent by the browser.
- Business day runs **10:00 → 05:00 Asia/Manila**. `business_date` is a generated column computed by
  the database via `business_date_of()`. Never compute it in Java.
- Billing is **exact minute, no minimum, no rounding**.

### Invariants the service layer must enforce
- One pool table can host at most one open session (the database enforces this too — let the
  constraint violation surface as a clean 409, do not pre-check and race).
- A void requires a reason, and the line is **retained** and excluded from totals — never deleted.
- Stock moves only via `stock_movement`. It is append-only; a trigger blocks UPDATE and DELETE.
  Corrections are new compensating rows. `product.qty_on_hand` is a cache updated in the *same
  transaction* as the movement.
- Selling below zero stock is **allowed with a warning**, not blocked.
- One payment per bill (unique constraint). Checkout takes an idempotency key.
- `bill.version` is an optimistic lock — a stale checkout must fail, not overwrite.
- Every void, rate override, stock correction and merge writes an `audit_log` row with the actor.

### Branch scoping
Every query is scoped to the caller's branch by default. Implement this once in a base repository or
a query filter so a forgotten `where branch_id = ?` cannot silently leak another branch's data. The
schema also enforces it with composite foreign keys.

### Roles
Only `EMPLOYEE` and `ADMIN`. **Employees must never see `purchase_price`, `avg_cost`, `unit_cost`,
`total_cost` or any profit figure** — enforce at the DTO level, not by hiding it in the UI.

---

## 6. Working style

- Build one vertical slice at a time, end to end, and make it work before starting the next.
- Prefer editing existing files over creating parallel ones.
- Do not add dependencies without saying why.
- Do not write speculative abstractions, generic base services, or interfaces with one
  implementation unless the existing code already does that.
- No `TODO` stubs that silently return null or empty — if something is not built yet, say so.
- Keep comments to the density the existing code uses. Comment *why*, not *what*.

### Tests that cannot fail

A green suite is what every decision in this project rests on, so a test that reports a bug as
fixed costs more than no test at all. Four have been found here — `cash_count.counted_at` read
back through the same persistence context, a lockout exemption satisfied by `recordSuccess`
having already cleared the key, a report CTE exercised only with an empty result, an image
fixture whose payload never had to be an image — and every one was found by accident, while
doing something else. **They are two shapes, not four bugs.**

**1. An assertion whose expected and actual values come from the same source proves the source
is self-consistent, not that the behaviour is right.** The same persistence context, so the
read-back returns what the code set in memory rather than what reached the row. The same clock,
so `business_date` compared against `business_date_of(now())` holds whatever the column does.
The same role, so a cost field checked only as an EMPLOYEE would be hidden either way. The same
wrong-password class, so attempting `"anything-at-all"` against a disabled account proves
nothing the account being ordinary would not also prove. Ask of every assertion: *what would
have to be broken for this to go red?* If the answer is "nothing that could plausibly break",
the assertion is decoration. The mapping-specific version of this rule is in §4 — cross a
transaction boundary before asserting a write persisted — and it is one case of the general one.

**2. A test name that claims more than its assertions is a false record of coverage, and it is
read far more often than the body is.** `...AndTheOriginalSurvives` asserted only the new
figure. `...StillLogsInFromADifferentAddress` never performed a login. `...GivesTheAdminMessage`
checked the status and not the message. Nobody re-reads the body once the name looks right, so
the gap is permanent. Either assert what the name says, or rename it to what it asserts.

**3. How much a can't-fail test costs depends on what else guards the behaviour, not only on
what the test proves.** These are not all equally urgent, and ranking them by how little they
prove gets the order wrong. `BillDiscountTest` read the cleared discount columns back through
the persistence context — the wrong source, exactly shape 1 — and it does not much matter,
because `bill_discount_together_chk` requires all four to be null-or-set together, so a
half-written clear is rejected by the database and surfaces at the write. The assertion was
worth correcting; it was never the thing standing between the hall and a bad row. Contrast
`bill.business_date`, where nothing but the mapping was watching, the regression landed twice,
and no test could see it either time. **Ask what fails if the test is wrong** — a schema
constraint behind it makes a weak assertion cheap, and nothing behind it makes one expensive.

**Prove it can fail before you call it done.** Break the behaviour on purpose, watch the test go
red for the reason you expect, restore it, watch it go green. Not the assertion — the
*behaviour*: narrow the `@Generated`, mark the column `updatable = false`, drop the `before`
snapshot. A test whose first ever run is green has not been tested.

## 7. Frontend

The React SPA lives in `frontend/` and has its own **`frontend/CLAUDE.md`** — read that instead of
this file when working there. Sections 4 and 5 above (layer rules, correctness rules) are backend
concerns and do not apply to components.

Two rules cross the boundary and hold everywhere:
- **The server is the only clock.** The browser never computes a billable duration or an amount.
- **Employees never see cost or profit.** The API omits those fields by role; the UI must not
  assume they exist.

The API contract is `docs/API-CONTRACT.md`; the screen spec is `docs/FRONTEND-SPEC.md`.

---

## 8. Ask, don't assume

Stop and ask when: the schema and existing code genuinely conflict; a business rule is ambiguous;
a change would touch more than a handful of files; or a dependency would be added.

Report honestly. If something does not work, say so with the error. Never claim a feature is done
when it is stubbed.

---

## 9. Branches and pull requests

Nothing lands on `main` except through a pull request that CI has passed, and the VPS is
updated by one deliberate click, never by a push. Branch protection enforces the first half;
`.github/workflows/deploy.yml` being `workflow_dispatch`-only enforces the second. The human
version of this page is `docs/WORKFLOW.md`.

- **Branch from `main`** as `feat/<slug>`, `fix/<slug>` or `chore/<slug>`. Never commit on
  `main`; if you find yourself there, `git switch -c` first and the commits move with you.
- **Commit messages as the repo already writes them**: one imperative line, the reasoning in
  the body. `git log` is the reference; match it.
- **Run the suite against a scratch database before opening the PR, never against 8080.**
  The invocation is in `docs/RUNBOOK.md`, "Never run the test suite against the trading
  database": `createdb supreme_scratch`, `DB_URL=... ./mvnw test`, `dropdb`. For a frontend
  change, `npm run typecheck && npm run build` in `frontend/` as well — that is what CI runs.
  On a fresh clone (or after `mvn clean`) build the SPA first and run
  `./mvnw resources:copy-resources@copy-frontend test`: the suite serves the fallback page,
  and without `target/classes/static/index.html` the gate test errors with `FileNotFound`.
- **Open the PR with `gh pr create`** and fill in `.github/pull_request_template.md`. The
  "How it was tested" heading holds the exact command and the count it printed (currently
  `Tests run: 203, Failures: 0`), and says what was *not* run. A PR whose body claims a test
  that did not happen is the same defect as a test name that claims more than it asserts (§6).
- **Never merge your own PR.** Report the URL and the CI result and stop. The owner reviews on
  GitHub and merges with **squash**, so one PR is one commit on `main`. If CI is red, fix it on
  the branch and push; the PR page keeps the history.
- **Deploy is the Actions button** — Actions → Deploy → Run workflow → type `deploy`. Never a
  `git pull` on the VPS, never a push from it, never `docker compose up` by hand there. The
  button runs `deploy/update.sh` through a key that can run nothing else, then waits for
  `/api/v1/time` to answer 401.
