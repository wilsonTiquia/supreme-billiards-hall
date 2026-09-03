# Frontend kickoff — paste into a FRESH Claude Code session

Open a new session in the project root (`~/SupremeBilliards`) and paste everything
below the line.

---

You are building the React frontend for the POS backend in this repo. The backend is finished,
tested and running — 170 Java files, 51 endpoints, all verified over HTTP.

Read these first, in order, and write nothing until you have:

1. `frontend/CLAUDE.md` — the rules. The five in §2 are where mistakes cost money.
2. `docs/API-CONTRACT.md` — every route and shape, extracted from the running controllers.
3. `docs/FRONTEND-SPEC.md` — screens, design tokens, build order.

Then report back with:

- The five rules from `frontend/CLAUDE.md` §2 in your own words, so I know they landed.
- The project structure you propose for `frontend/` — folders, where API types live, where the
  API client lives, how features are organised.
- The shape of your API client: how it unwraps the envelope, how it types errors, how it carries
  the session cookie, and how a 401 anywhere routes to login.
- Anything in the API contract you find ambiguous or contradictory. It was verified against the
  running server, but it was written by a different session and I would rather you flag a doubt
  than code around it.

Then propose Phase A from `docs/FRONTEND-SPEC.md` §4 and stop.

Do not write code until I approve. Do not add dependencies beyond the pinned stack without asking.
Do not start Phase B until Phase A's gate passes in a real browser.

The backend runs with `mvn spring-boot:run` on port 8080. Start it and hit `/api/v1/time` to
confirm you can reach it before you build anything against it.
