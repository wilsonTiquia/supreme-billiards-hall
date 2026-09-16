# Shell QA — PR 1 of 6

Source: [Tikwa QA.pdf](../../Tikwa%20QA.pdf), all 19 pages reviewed. This PR closes
pages 1–4 and the shared-toast item on page 14, scheduled by the owner for PR 1.
The other findings remain for the five later PRs, each starting after the previous merge.

## Closed findings

- **Page 1:** Login password visibility button; logo aligned to the form's left edge.
- **Pages 1–2:** Viewport-height sidebar, uniform navigation links and labelled sections;
  collapse control next to the logo. Two columns preserve 44px targets and keep all 13
  admin destinations visible at 1280×720. Both expanded and compact menus measured
  `clientHeight = scrollHeight = 720`.
- **Page 3:** Admin-only Floor/Admin segmented navigation; removes the old workspace links.
  Theme and collapse controls share the top row. Employee login has no workspace control.
- **Pages 3–4:** Name links to `/account`; Sign out is one click there, without a modal.
  Change password remains on that page. Account keeps the originating workspace and theme;
  the workspace is retained through reload. `/account/password` continues to work.
- **Page 14 (included in PR 1):** Stock successes use the app-wide toast, including
  “Give-away recorded.” Success waits for the backend. Errors stay on the page and clear
  previous success. Toasts can be dismissed, expire after eight seconds, pause on interaction,
  and clear at sign-out. Future screens can call `useToast().notify(message)`.

## Verification

- `npm --prefix frontend run typecheck` — passed.
- `npm --prefix frontend run build` — passed; existing >500 kB bundle advisory remains.
- `node --test frontend/tests/*.test.mjs` — 4 passed, 1 optional API-fixture test skipped.
- `DB_URL=jdbc:postgresql://localhost:5432/supreme_shell_358202ac_scratch ./mvnw resources:copy-resources@copy-frontend test`
  — 203 tests, 0 failures, 0 errors, 0 skipped. Temporary database created for this run and dropped afterward.
- Browser checks used Vite on 5174, a separate backend on 8181, and database
  `supreme_shell_20260916_e2e`. Inventory writes were confined to that disposable database.
- Owner and employee sign-in; show/hide password; workspace switching; account navigation;
  account reload; one-click sign-out; compact menu; drawer focus and Escape; success toast,
  keyboard focus pause, manual dismissal, and clearing success after a rejected zero-quantity action.
- Existing password-change form reused; no real account password was changed in the browser.
  The full Playwright E2E suite was not run; the interactions above were checked through the browser.

## Screenshots

Desktop is **1280×720**; phone is **400×900**. The sidebar is shared throughout the app;
Dashboard and Floor show its admin and counter variants. Phone navigation is shown open.
Stock captures are scrolled to the give-away form and its toast. All figures are test data.
Before screenshots are in the source PDF on the pages listed above.

### Login

| Light, 1280px | Dark, 1280px |
| --- | --- |
| ![Login light desktop](login-light-1280.png) | ![Login dark desktop](login-dark-1280.png) |

| Light, 400px | Dark, 400px |
| --- | --- |
| ![Login light phone](login-light-400.png) | ![Login dark phone](login-dark-400.png) |

### Admin navigation

| Light, 1280px | Dark, 1280px |
| --- | --- |
| ![Admin navigation light desktop](admin-light-1280.png) | ![Admin navigation dark desktop](admin-dark-1280.png) |

| Light, 400px | Dark, 400px |
| --- | --- |
| ![Admin navigation light phone](admin-light-400.png) | ![Admin navigation dark phone](admin-dark-400.png) |

### Floor navigation (owner)

| Light, 1280px | Dark, 1280px |
| --- | --- |
| ![Floor navigation (owner) light desktop](floor-light-1280.png) | ![Floor navigation (owner) dark desktop](floor-dark-1280.png) |

| Light, 400px | Dark, 400px |
| --- | --- |
| ![Floor navigation (owner) light phone](floor-light-400.png) | ![Floor navigation (owner) dark phone](floor-dark-400.png) |

### Employee navigation

| Light, 1280px | Dark, 1280px |
| --- | --- |
| ![Employee navigation light desktop](employee-light-1280.png) | ![Employee navigation dark desktop](employee-dark-1280.png) |

| Light, 400px | Dark, 400px |
| --- | --- |
| ![Employee navigation light phone](employee-light-400.png) | ![Employee navigation dark phone](employee-dark-400.png) |

### Account

| Light, 1280px | Dark, 1280px |
| --- | --- |
| ![Account light desktop](account-light-1280.png) | ![Account dark desktop](account-dark-1280.png) |

| Light, 400px | Dark, 400px |
| --- | --- |
| ![Account light phone](account-light-400.png) | ![Account dark phone](account-dark-400.png) |

### Stock toast

| Light, 1280px | Dark, 1280px |
| --- | --- |
| ![Stock toast light desktop](stock-light-1280.png) | ![Stock toast dark desktop](stock-dark-1280.png) |

| Light, 400px | Dark, 400px |
| --- | --- |
| ![Stock toast light phone](stock-light-400.png) | ![Stock toast dark phone](stock-dark-400.png) |

### Compact desktop menu

| Light | Dark |
| --- | --- |
| ![Compact light](compact-light-1280.png) | ![Compact dark](compact-dark-1280.png) |
