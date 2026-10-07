# QA Part 2 PR-06 — Products

Implemented from the PR-06 brief in `QA-PART2-PR-PLAN.md` and the Products references on pages 12–14 of `qapart2.pdf`. Before screenshots were captured from main at `160ead0251ec46dd6c436b55444953d8b8359ba3`, before changing product components. Dependency PR-01 is merged as [#11](https://github.com/wilsonTiquia/supreme-billiards-hall/pull/11), commit `6e4c1e061bdb028454073012613662ecd5cea8ff`, verified as an ancestor of main.

## Behavior

- Category sections retain server category order and product order, with an explicit Uncategorized section. Search, category and Include archived filters work together. The existing admin setup endpoint supplies archived category names. Failed category reads leave products visible, with a warning and a fallback label.
- Edit and Archive use the shared accessible overflow menu; archive still confirms. Add stock and Restore remain visible. Archived text retains contrast; status text and strike-through distinguish it. Cost and margin remain on the admin-only catalog.
- New product has a local optional picture preview, replacement/removal before saving, and JPEG/PNG/WebP and 2 MB feedback. Cancel sends no product or image writes.
- Creation and optional opening stock remain one existing server transaction. Only after that succeeds is the picture uploaded to the returned ID. An upload failure shows explicit partial success and retries only the image. Dismissing the upload dialog retains a Review picture banner while this page remains mounted. Finish without picture keeps the saved product; Edit can upload a picture later, including after leaving/reloading the page. No browser-persisted file draft is promised.
- Existing image replacement/removal, archive/restore and Add stock use their existing endpoints. No API contract, schema, accounting, purchase-cost policy or role changes. PR-07's stock-entry redesign is excluded.

## Actual validation — 7 October 2026 (Asia/Manila)

From `frontend/`:

```sh
npm run typecheck
npm run build
```

Both passed. Build transformed 209 modules. Existing environment warnings remain: npm 11.11.1 warns about Node 20.13.1, and Vite reports a JS chunk over 500 kB. No dependencies were added.

From the repository root, using a newly created PostgreSQL 18 scratch database:

```sh
PGPASSWORD=supreme /Library/PostgreSQL/18/bin/createdb -h localhost -U supreme pr06_scratch
DB_URL=jdbc:postgresql://localhost:5432/pr06_scratch DB_USERNAME=supreme DB_PASSWORD=supreme ./mvnw resources:copy-resources@copy-frontend test
PGPASSWORD=supreme /Library/PostgreSQL/18/bin/dropdb -h localhost -U supreme pr06_scratch
```

Result: **Tests run: 250, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS**. Scratch database dropped afterward.

From `frontend/`, with the repository's guarded scratch server workflow:

```sh
E2E_DB_NAME=pr06_e2e E2E_API_PORT=8086 E2E_WEB_PORT=5176 PR06_CAPTURE=after npm run e2e -- pr06-products.spec.ts pr06-screenshots.spec.ts stock-qa.spec.ts employee-sees-no-cost.spec.ts
```

Result: **15 passed (55.9s)**. Chromium coverage includes:

- Category, search and archive filters and keyboard menu focus at 1280/400px in light/dark themes.
- No-image creation; file type/size rejection; preview replacement/removal; cancel without writes.
- Real create/upload/download, fractional opening stock, edit image replace/remove, archive/restore, and preservation of an archived category on edit.
- Held upload, server failure, dismissal/reopening and retry against the same ID. Independent API reads assert one product, one opening-stock movement, unchanged quantity/cost and a saved image.
- Creation error with draft retained, followed by image failure and Finish without picture.
- Loading, empty, fetch error, 150 products and long labels, including menu reachability and no horizontal page overflow.
- Existing stock workflow in both widths/themes and employee cost/profit isolation.

The image-retry regression was tested against a deliberate local defect: retry created a second product with opening stock before uploading its image. The unchanged test failed on **Expected: 1; Received: 2** product-creation requests. The defect was removed; the full 15-check run above passed afterward. This mutation was never committed.

A final focus improvement places keyboard focus on the saved-product message while the image uploads. After that change, typecheck/build passed again and the following focused run passed **1 test (18.2s)**, including the focus assertion and the saved-ID/ledger checks:

```sh
E2E_DB_NAME=pr06_e2e E2E_API_PORT=8086 E2E_WEB_PORT=5176 npm run e2e -- pr06-products.spec.ts --grep 'image failure retries'
```

Before capture: the same screenshot spec with `PR06_CAPTURE=before` on unchanged product components: **1 passed (43.5s)**. Read-only catalog fixtures make before/after images comparable; mutation tests use the real scratch API, with only explicit failure responses intercepted. The preview is a tiny white PNG test fixture, not a catalog photograph.

`git diff --check` passed. The complete unrelated browser suite and `npm run test:reports` were not run; this PR does not change reports. No trading database, deployment or merge was used.

## Screenshots

| View | Before | After | Selected picture |
|---|---|---|---|
| 1280px light catalog | [Before](before-light-1280-catalog.png) | [After](after-light-1280-catalog.png) | — |
| 1280px dark catalog | [Before](before-dark-1280-catalog.png) | [After](after-dark-1280-catalog.png) | — |
| 400px light catalog | [Before](before-light-400-catalog.png) | [After](after-light-400-catalog.png) | — |
| 400px dark catalog | [Before](before-dark-400-catalog.png) | [After](after-dark-400-catalog.png) | — |
| 1280px light creation | [Before](before-light-1280-new.png) | [After](after-light-1280-new.png) | [Preview](after-light-1280-preview.png) |
| 1280px dark creation | [Before](before-dark-1280-new.png) | [After](after-dark-1280-new.png) | [Preview](after-dark-1280-preview.png) |
| 400px light creation | [Before](before-light-400-new.png) | [After](after-light-400-new.png) | [Preview](after-light-400-preview.png) |
| 400px dark creation | [Before](before-dark-400-new.png) | [After](after-dark-400-new.png) | [Preview](after-dark-400-preview.png) |

[Upload failure and retry](after-upload-failure.png). The creation dialog scrolls vertically when its selected-picture preview and opening-stock fields exceed the viewport. Screenshots were visually inspected for readability, wrapping, target spacing and clipping.
