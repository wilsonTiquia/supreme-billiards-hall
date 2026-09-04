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
