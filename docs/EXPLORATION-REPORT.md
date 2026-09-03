# Exploration Report — existing backend, conventions and conflicts

Written before any Day 1 code was committed. Records the style derived from the pre-existing
code, the inventory of conflicts against `docs/schema.sql`, and what was finished, stubbed or
missing at that point. `CLAUDE.md` is the rulebook; this file is the evidence behind it.

State at time of writing: 27 Java files, 3 entities, no tests, MySQL datasource, `ddl-auto=update`.

---

## 1. Conventions observed

Derived from the **`Category`** slice end to end, cross-checked against **`Branch`** and
**`Product`**.

### Naming

| Element | Pattern | Evidence |
|---|---|---|
| Controller | `<Entity>Controller` | `controller/CategoryController.java` |
| Service | interface `<Entity>Service` + `impl/<Entity>ServiceImpl` | `service/CategoryService.java`, `service/impl/CategoryServiceImpl.java` |
| Mapper | `<Entity>Mapper`, MapStruct interface | `mapper/CategoryMapper.java` |
| Repository | `<Entity>Repository extends JpaRepository<E, ID>` | `repository/CategoryRepository.java` |
| DTO | `<Entity>RequestDTO` / `<Entity>ResponseDTO` — **`DTO` uppercase, not `Dto`** | `dto/category/CategoryRequestDTO.java:12` |
| Envelope | `APIResponse<T>` with static `success(data, message)` / `failure(message)` | `dto/APIResponse.java:17,21` |

One deliberate inconsistency inside the naming, preserved as-is: the *class* is
`CategoryResponseDTO` but the *mapper method* is `toResponseDto` (`mapper/CategoryMapper.java:13`).
Both spellings are load-bearing.

### Package layout — by layer, not by feature

```
controller/ · service/ · service/impl/ · mapper/ · repository/ · entity/ · exception/ · dto/
```

DTOs are the exception: they nest **per feature** under `dto/<feature>/` (`dto/category/`,
`dto/branch/`). `dto/APIResponse.java` sits at the `dto/` root because it is cross-cutting.
`dto/ProductDTO.java` also sat at the root, but that was the older un-split style, not the
convention.

A new entity `Foo` therefore adds 8 files: `controller/FooController.java`,
`service/FooService.java`, `service/impl/FooServiceImpl.java`, `mapper/FooMapper.java`,
`repository/FooRepository.java`, `entity/Foo.java`, `dto/foo/FooRequestDTO.java`,
`dto/foo/FooResponseDTO.java`.

### DTO request/response split

**Classes, not records.** All DTOs carry `@Data @NoArgsConstructor @AllArgsConstructor`
(`dto/category/CategoryRequestDTO.java:9-11`). Request DTOs carry validation and **no `id`**;
response DTOs carry `id` and no validation (`dto/category/CategoryResponseDTO.java:12`).
`dto/ProductDTO.java` violated this — one shared class used in both directions.

### Mapping — MapStruct, three methods

```java
@Mapper(componentModel = "spring")
public interface CategoryMapper {
    CategoryResponseDTO toResponseDto(Category category);
    Category toEntity(CategoryRequestDTO categoryRequestDTO);
    @Mapping(target = "id", ignore = true)
    void updateEntityFromDto(CategoryRequestDTO dto, @MappingTarget Category entity);
}
```

`updateEntityFromDto` always ignores `id`, and ignores any server-owned field too —
`mapper/BranchMapper.java:19-20` adds `@Mapping(target = "createdAt", ignore = true)`. No `@Named`,
`@AfterMapping` or `uses=` anywhere.

### Injection — explicit constructor, no Lombok

Every service and controller declares `private final` fields and writes the constructor **by hand**
(`service/impl/CategoryServiceImpl.java:19-25`, `controller/CategoryController.java:17-21`).
**No `@RequiredArgsConstructor`, no `@Autowired`, no field injection.** Consistent across all six
classes — the strongest convention in the repo and the easiest for a generator to break.

### Lombok usage — split by layer

- **Entities:** `@Getter @Setter @NoArgsConstructor` (`entity/Category.java:8-10`). Never `@Data`,
  never `@Builder`, never `@AllArgsConstructor`.
- **DTOs:** `@Data @NoArgsConstructor @AllArgsConstructor`.
- **Services / controllers:** none.

### Exception translation

Domain exceptions are bare `RuntimeException` subclasses in `exception/`, with message-building in
the constructor rather than at the throw site:

```java
public ResourceNotFoundException(String resource, Object identifier)   // ResourceNotFoundException.java:4
```

called as `new ResourceNotFoundException("Category", id)`
(`service/impl/CategoryServiceImpl.java:63`). `DuplicateResourceException` takes a pre-built
message instead.

`GlobalExceptionHandler` is a `@RestControllerAdvice` where **every handler returns
`ResponseEntity<APIResponse<Object>>`** built with the raw constructor
`new APIResponse<>(null, msg, false)` rather than `APIResponse.failure(msg)` — inconsistent with the
DTO's own helper, but it is the established pattern in all six handlers. Mapping:
404 / 409 / 400 / 409 / 400 / 400.

The malformed-UUID → 400 requirement in `CLAUDE.md` §2 was **already satisfied** before Day 1:
`handleTypeMismatch` (`exception/GlobalExceptionHandler.java:70-76`) catches
`MethodArgumentTypeMismatchException` and returns 400 "Invalid ID format". It works unchanged once
path variables become `UUID`.

### Validation

`jakarta.validation` annotations live **on the request DTO only**
(`dto/category/CategoryRequestDTO.java:14-15`), never on the entity. Controllers apply
`@Valid @RequestBody` (`controller/CategoryController.java:33`). Messages are full sentences:
`"Category name is required"`.

### `@Transactional`

On the **`ServiceImpl` method**, paired with `@Override`, using
`org.springframework.transaction.annotation.Transactional` — `@Transactional(readOnly = true)` for
reads, bare `@Transactional` for writes (`service/impl/CategoryServiceImpl.java:28-29, 46-47`).
Never on the interface, never on the controller, never on the class.

### Tests

**No `src/test` directory existed.** The pom already carried the Boot 4 test starters
(`spring-boot-starter-data-jpa-test`, `-security-test`, `-webmvc-test`, `pom.xml:78-92`), so the
layout was unestablished.

---

## 2. Canonical reference for a new vertical slice

**`service/impl/CategoryServiceImpl.java`**, with `controller/CategoryController.java` as its
partner.

- It is the only service where all four conventions co-occur correctly: hand-written constructor
  injection, `@Transactional` on every method with the right `readOnly` flag,
  duplicate-check-then-throw `DuplicateResourceException`, and
  `orElseThrow(() -> new ResourceNotFoundException(...))`.
- `BranchServiceImpl` is the same shape almost line for line. That agreement between two
  independent files is what makes it a convention rather than one file's habit.
- `ProductServiceImpl` disagrees with both (raw `RuntimeException` at lines 54 and 63, no
  `@Transactional`, `Collectors.toList()` instead of `.toList()`). It is the *oldest* file, not the
  reference.

### Where `Category` and `Branch` disagreed

1. **Created-resource status.** `CategoryController.java:36` returned `200 OK` on POST;
   `BranchController.java:45` returned `201 CREATED`. → **Resolved: follow `Category`, 200 OK.**
2. **`@Repository` annotation.** Present on `BranchRepository.java:10` and
   `ProductRepository.java:10`, absent on `CategoryRepository.java:9`. → **Resolved: follow
   `Category`, omit it.**
3. **Search endpoint exposure.** `BranchController.java:33` exposed `GET /search`;
   `CategoryService.java:11` declared `searchCategoryByName` with no controller route reaching it.

---

## 3. Conflicts with `docs/schema.sql`

### 3a. Decided up front — work items, not questions

| # | Change | Files touched |
|---|---|---|
| 1 | **`Long` → `java.util.UUID` primary keys** | **18** — 3 entities, 3 repositories (`JpaRepository<E,Long>`), 3 service interfaces, 3 impls, 3 controllers (`@PathVariable`), 3 response DTOs. `GlobalExceptionHandler` needed **no** change. 0 new files. |
| 2 | **Session state off `pool_table`** | **0 existing files.** No `PoolTable` entity existed. Purely net-new work, not a migration. `pool_table` (schema:324) holds no time columns; occupancy is `table_session` (schema:471). |
| 3 | **MySQL → PostgreSQL 18** | **2** — `pom.xml:64-69`, `application.properties:4`. |
| 4 | **`ddl-auto=update` → `validate`** | **1** — `application.properties:7`. |
| 5 | **Flyway added, `schema.sql` split** | **1 edit + 2 new** — `pom.xml`, plus `V1__baseline.sql` (schema.sql 1–950) and `V2__seed.sql` (953–1014). |
| 6 | **`LocalDateTime` → `OffsetDateTime`** | **2** — `entity/Branch.java:32` (the only timestamp field in the codebase) and `dto/branch/BranchResponseDTO.java:18`. |

**UUID generation — verified, not assumed.** Hibernate 7.2.4 resolves on this project. Unzipping
`hibernate-core-7.2.4.Final.jar` shows `UuidGenerator$Style` declaring
`AUTO, RANDOM, TIME, VERSION_6, VERSION_7`, backed by `org/hibernate/id/uuid/UuidVersion7Strategy`.
So `@UuidGenerator(style = Style.VERSION_7)` gives true RFC 9562 v7, application-side, with **no
extra dependency** and no fallback to the database default.

### 3b. Conflicts resolved by assumption (see §5)

- **(i)** `Category` entity → `@Table(name = "categories")` vs schema table `product_category`.
  Same for `Branch`→`branches`, `Product`→`products`: entities plural, schema singular.
- **(ii)** API base path `/api/v1/...` (`CategoryController.java:14`) vs `BACKEND-SPEC.md` §2
  *"All routes under `/api`"*.
- **(iii)** The `Branch` CRUD slice has no home in `BACKEND-SPEC.md` §2, and its entity contradicts
  the schema: no `code` (`NOT NULL UNIQUE`, schema:117), no `next_receipt_no` (schema:124), and
  `existsByName`/`existsByNameAndIdNot` (`BranchRepository.java:16-19`) enforce a name-uniqueness
  constraint **that does not exist** — `branch` is unique on `code`. `GlobalExceptionHandler.java:46`
  also maps a constraint name `branches_name_unique` that does not exist.
- **(iv)** `Product.purchasePrice` (`entity/Product.java:30`) is **not** `product.avg_cost`. One is
  a static figure you type in; the other is a moving weighted average maintained by the stock
  ledger. A semantic conflict, not a rename. Full divergence: `price` → `selling_price
  numeric(12,2)`; `category int` → `category_id uuid` under a composite FK on
  `(branch_id, category_id)` (schema:294); missing `sku`, `qty_on_hand`, `is_active`, `archived_at`,
  `created_at`, `updated_at`, `branch_id`; declared `precision = 10` where the schema says
  `numeric(12,2)`.
- **(v)** Hard delete (`CategoryServiceImpl.java:85`, `BranchServiceImpl.java:83` call
  `deleteById`) vs `archived_at`. Every uniqueness index in the schema is **partial on
  `archived_at IS NULL`** (schema:232, 273, 306, 337).
- **(vi)** `existsByName` (`CategoryRepository.java:13`) is case-sensitive and global; the schema
  index is `(branch_id, lower(name)) WHERE archived_at IS NULL`.
- **(vii)** **No entity carried `branch_id`.** Every schema table does, `NOT NULL`, with composite
  FKs on children.
- **(viii)** **`updated_at` has no database trigger.** The only three triggers in the schema are
  `forbid_mutation()` on `audit_log`, `receipt` and `stock_movement`. `updated_at NOT NULL DEFAULT
  now()` is set on insert and **never maintained on update by the database** — the application must
  do it. Looks like a database concern and silently isn't.
- **(ix)** Entities declared `@Column(length = 100)` → `varchar(100)`; the schema uses `text`
  everywhere. A `ddl-auto=validate` failure at startup.
- **(x)** `dto/ProductDTO.java:16` carried `purchasePrice` on the response path and
  `ProductController.java:31` returned it unconditionally — against `CLAUDE.md` §5, which requires
  employees to see no cost field at all, enforced at the DTO level.

---

## 4. Finished / stubbed / missing at time of writing

### Finished (as CRUD against the *old* schema — none of it matched the new one)

- `Category` slice — all 8 files, internally consistent.
- `Branch` slice — all 8 files, plus a search endpoint.
- `APIResponse`, `GlobalExceptionHandler`, `ResourceNotFoundException`, `DuplicateResourceException`.
- Build wiring: MapStruct + Lombok annotation processors ordered correctly (`pom.xml:104-117`),
  Boot 4.0.1, Java 21 target.

### Stubbed

- **`CategoryServiceImpl.searchCategoryByName`** (lines 39-44) — takes `name`, **ignores it**,
  returns `findAll()`. A silent wrong-answer stub, unreachable from any controller.
- `deleteCategory` (line 83) and `deleteBranch` (line 81) fetched the entity into an unused local
  before `deleteById` — dead code, though it did produce the correct 404.

### Broken, not merely stubbed

- **`ProductController.searchProducts` returned HTTP 400 on success** —
  `ResponseEntity.status(HttpStatus.BAD_REQUEST).body(APIResponse.success(...))`
  (`ProductController.java:25`). Payload said success, status said failure.
- `ProductController` create/update/delete returned **raw DTOs and `void`**, bypassing `APIResponse`
  (lines 40, 45, 52), with no `@Valid`.
- `ProductServiceImpl` threw bare `RuntimeException` (lines 54, 63) → **500 instead of 404**, since
  `GlobalExceptionHandler` has no handler for it. No `@Transactional` in the class.
- `entity/Product.java:5` imported `jakarta.validation.constraints.Max` and never used it;
  `CategoryRepository` imported `Branch` unused; `BranchRepository` imported `Product` unused.

### Missing

- **17 of the 20 schema tables had no entity**: `app_user`, `branch_setting`, `audit_log`,
  `customer_type`, `pool_table`, `pool_table_rate`, `bill`, `table_session`, `session_segment`,
  `session_pause`, `bill_line`, `bill_merge_event`, `payment`, `receipt`, `stock_delivery`,
  `stock_movement`, `cash_count`.
- Spring Security entirely — no config, no `UserDetailsService`, no session cookie, no `/auth/*`, no
  roles. `spring-boot-starter-security` was **not** in `pom.xml` (only the test starter was).
- Flyway, the Postgres driver, `db/migration/`, branch scoping, audit logging, the seven Postgres
  enum mappings, the jsonb mappings, generated-column mappings, `@Version` on `bill`, `/time`,
  OpenPDF, and all tests.

**Build note:** `org/flywaydb` and `org/postgresql/postgresql` were absent from `~/.m2`, so the
first build after the pom change required network. `spring-boot-starter-security:4.0.1` was cached.

---

## 5. Assumptions taken

Recorded here because they were taken rather than answered, and any of them can be reversed.

| # | Assumption | Reversal cost |
|---|---|---|
| i | Keep the class name `Category` and map it with `@Table(name = "product_category")`, rather than renaming the slice to `ProductCategory`. Preserves the reference slice's name and the spec's `/categories` route. | 8 files renamed |
| ii | Keep the existing `/api/v1/...` base path. `BACKEND-SPEC.md` §2 paths are read as relative to it. Style preservation outranks the literal path in the spec. | 1 line per controller |
| iii | Keep the `/branches` CRUD slice and reconcile it to the schema (`code` added and made the uniqueness key, `nextReceiptNo` added), admin-only. | 8 files |
| iv | `purchase_price` is superseded by `avg_cost`; nothing depends on the old semantics. | — |
| v | Soft delete via `archivedAt` everywhere in the catalog, finders filter `archivedAt IS NULL`. | — |
| vi | Uniqueness checks become branch-scoped, case-insensitive, and ignore archived rows. | — |
| vii | Branch scoping in a base repository (`BranchScopedRepository`), opt-out. | — |
| viii | `updated_at` maintained by the application with `@UpdateTimestamp`. | — |
| ix | String columns mapped with `columnDefinition = "text"` to satisfy `ddl-auto=validate`. | — |
| x | Cost visibility enforced by two response types — `ProductResponseDTO` (no cost fields at all) and `ProductAdminResponseDTO` extending it. The service chooses by role, so the controller stays logic-free. | — |
| xi | POST returns `200 OK`, following `Category`, not `201 CREATED` as in `Branch`. | 1 line per controller |
| xii | Seed passwords for the placeholder bcrypt hashes in `V2__seed.sql`. | 2 lines |
| xiii | ~~Global admin resolves to the single active branch.~~ **Rejected by the owner and replaced.** The admin's active branch now lives in the HTTP session: defaulted when exactly one active branch exists, and a `BranchNotSelectedException` → 409 when more than one exists and none is selected. Never inferred. | done |

Assumption **xiii** was rejected on review and rebuilt: silently resolving to a plausible branch is
the opposite of what the rest of the schema does, and the failure mode is an admin reading the wrong
hall's figures with nothing in the response to say so. See `security/BranchContext.java`.

---

## 6. Two Spring Boot 4 surprises worth recording

Both cost a debugging cycle on Day 1 and will bite again in any new module.

1. **Autoconfiguration is split per technology.** `org.flywaydb:flyway-core` on its own does
   nothing — Flyway silently never runs, and Hibernate then fails `validate` on a missing table.
   The autoconfiguration lives in `spring-boot-starter-flyway`. Expect the same shape for any other
   integration added later.
2. **Postgres enum types need naming twice.** `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` binds
   parameters correctly, but an enum written as a *literal* inside a JPQL query is rendered by
   Hibernate as `'OPEN'::SessionStatus` — the Java class name, not `session_status` — and
   `columnDefinition` does not reach the literal formatter. Bind enums as query parameters
   instead; see `TableSessionRepository.findAllLive`. Fails only at runtime, on the query, not
   at startup.
3. **Jackson 3 (`tools.jackson`) is the primary mapper**, with Jackson 2 still on the classpath.
   Injecting `com.fasterxml.jackson.databind.ObjectMapper` fails to resolve a bean. Separately,
   Hibernate 7.2 writes `jsonb` through its *own* Jackson 2 mapper, which has no java.time support
   by default — an `OffsetDateTime` inside an `audit_log` snapshot throws at flush and rolls back
   the change it was recording. Configured in `config/HibernateJsonConfig.java`.
