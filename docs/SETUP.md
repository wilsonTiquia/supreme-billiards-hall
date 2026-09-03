# Setup — what to do, in order

Your project: `~/SupremeBilliards`
Your toolchain: JDK 25, Maven 3.9.14, PostgreSQL 18 running on port 5432. **No Docker** — so we use
the Postgres you already have installed rather than a container.

---

## Step 1 — Copy the handoff files into the project

Paste this whole block into a terminal:

```bash
PROJ=~/SupremeBilliards
HANDOFF=~/Desktop/supreme-billiards/handoff

mkdir -p "$PROJ/docs"
cp "$HANDOFF/CLAUDE.md"         "$PROJ/CLAUDE.md"
cp "$HANDOFF/BACKEND-SPEC.md"   "$PROJ/docs/BACKEND-SPEC.md"
cp "$HANDOFF/schema.sql"        "$PROJ/docs/schema.sql"
cp "$HANDOFF/SETUP.md"          "$PROJ/docs/SETUP.md"

ls -la "$PROJ/CLAUDE.md" "$PROJ/docs"
```

`CLAUDE.md` **must** sit at the project root. That is what makes Claude Code load it automatically
in every session.

---

## Step 2 — Create the database

Your PostgreSQL 18 is already running. Create a user and database for this project.

```bash
/Library/PostgreSQL/18/bin/psql -U postgres -h localhost -c "CREATE USER supreme WITH PASSWORD 'supreme';"
/Library/PostgreSQL/18/bin/psql -U postgres -h localhost -c "CREATE DATABASE supreme OWNER supreme;"
/Library/PostgreSQL/18/bin/psql -U postgres -h localhost -d supreme -c "CREATE EXTENSION IF NOT EXISTS btree_gist;"
```

It will prompt for the `postgres` superuser password — the one you set when you installed
PostgreSQL. The `btree_gist` extension must be created by the superuser, which is why it is a
separate line.

**Verify it worked:**

```bash
/Library/PostgreSQL/18/bin/psql -U supreme -h localhost -d supreme -c "SELECT version();"
```

If that prints a version banner, the database is ready.

### Optional — prove the schema loads before writing any Java

Worth 30 seconds. It confirms the SQL is good against *your* server, not just mine:

```bash
/Library/PostgreSQL/18/bin/psql -U supreme -h localhost -d supreme \
  -v ON_ERROR_STOP=1 -f ~/SupremeBilliards/docs/schema.sql
```

Expect no errors. Then **drop it again** so Flyway can own the schema properly:

```bash
/Library/PostgreSQL/18/bin/psql -U postgres -h localhost -c "DROP DATABASE supreme;"
/Library/PostgreSQL/18/bin/psql -U postgres -h localhost -c "CREATE DATABASE supreme OWNER supreme;"
/Library/PostgreSQL/18/bin/psql -U postgres -h localhost -d supreme -c "CREATE EXTENSION IF NOT EXISTS btree_gist;"
```

---

## Step 3 — Open Claude Code in the project

In IntelliJ, open a terminal at the project root (or use the Claude Code plugin):

```bash
cd ~/SupremeBilliards
claude
```

Confirm it picked up the instructions — it should mention `CLAUDE.md` in its context. If unsure,
ask it: *"what does CLAUDE.md say the reference slice is?"* It should answer `Category`.

---

## Step 4 — First message

Paste the contents of `KICKOFF-PROMPT.md`. It tells Claude to explore, report back on your
conventions, and **stop without writing code**.

Do not skip this. Left to itself an agent starts generating on turn one, and you end up reviewing
40 files written in a style you did not choose.

---

## Step 5 — Review the report, then approve

You are checking three things:

1. **Did it read your style correctly?** It should come back with: Lombok `@Getter/@Setter/
   `@NoArgsConstructor` on entities, MapStruct mappers, `Service` interface + `impl/ServiceImpl`,
   DTOs split `RequestDTO`/`ResponseDTO` in per-feature subfolders, `APIResponse` as the envelope,
   `GlobalExceptionHandler` with `ResourceNotFoundException` / `DuplicateResourceException`.
   If any of that is wrong, correct it now — before it writes anything.

2. **Does its conflict list match this one?** Expected:
   - MySQL → PostgreSQL (driver, datasource, `hotel_reservation` leftover URL)
   - `ddl-auto=update` → `validate`
   - Flyway added; `schema.sql` split into `V1__baseline.sql` + `V2__seed.sql`
   - `Long` IDs → `UUID` across Branch, Category, Product
   - `LocalDateTime` → `OffsetDateTime`
   - Branch/Category/Product entities reshaped to match the schema columns
   - 17 of the 20 tables do not exist yet

3. **How many files does the UUID conversion touch?** Tell me that number — it is the main thing
   that could move the day-one estimate.

Then approve Day 1 from `docs/BACKEND-SPEC.md` §3.

---

## Step 6 — Configure the datasource

Claude will do this, but so you know what "right" looks like — `application.properties` should end
up approximately:

```properties
spring.application.name=billiards-hall-system

spring.datasource.url=jdbc:postgresql://localhost:5432/supreme
spring.datasource.username=supreme
spring.datasource.password=supreme

spring.jpa.hibernate.ddl-auto=validate
spring.jpa.properties.hibernate.jdbc.time_zone=UTC

spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
```

`ddl-auto=validate` is the important line. If you ever see it back at `update`, that is a bug —
Hibernate reshaping a schema that Flyway owns is how you lose a constraint without noticing.

`time_zone=UTC` matters too: it keeps JDBC from applying the JVM's local zone to `timestamptz`,
which would corrupt the business-day boundary.

---

## Step 7 — Run it

```bash
cd ~/SupremeBilliards
mvn spring-boot:run
```

First run applies the Flyway migrations. Confirm the schema landed:

```bash
/Library/PostgreSQL/18/bin/psql -U supreme -h localhost -d supreme \
  -c "\dt" -c "SELECT name, table_number FROM pool_table ORDER BY table_number;"
```

You should see 20 tables and the 7 seeded pool tables.

---

## Things worth knowing

- **The `hotel_reservation` database name** in your current config is a leftover from another
  project. Nothing points at it after this change.
- **Devtools is on the classpath**, so the app restarts on recompile. Useful, but it can surprise
  you mid-request — if you see odd behaviour during a long checkout test, that is the first suspect.
- **You have JDK 25 but the pom targets 21.** That is fine and worth keeping — 21 is the LTS the
  deployment box will run.
- **Add your run commands to `CLAUDE.md`** once they settle. There is a placeholder-free gap at the
  bottom; a short "Commands" section stops Claude asking every session.
