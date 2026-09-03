# Kickoff prompt — paste this into Claude Code in the IntelliJ project

Paste everything below the line as your first message, after copying the handoff files in.

---

Read `CLAUDE.md`, `docs/BACKEND-SPEC.md` and `docs/schema.sql` before doing anything else.

This repo already has a partially built backend. The owner wants its existing style preserved
exactly — that is the point of this exercise, because he reviews by reading.

Do this first, and only this:

1. Explore the existing codebase. Read one complete vertical slice end to end — controller, DTO,
   entity, mapper, repository, service, exception handling — and treat it as the reference
   implementation.
2. Report back with:
   - The conventions you observed, concretely, using file names as evidence — naming, package
     layout, DTO request/response split, mapping approach, injection style, Lombok usage,
     exception translation, validation, where `@Transactional` sits, test layout.
   - Which file you consider the canonical reference for a new vertical slice, and why.
   - Every place the existing code conflicts with `docs/schema.sql`, and the scale of each change
     in files touched. Note: `UUID` primary keys and moving session state off the pool table entity
     are already decided — list them as work to do, not as questions.
   - What is finished, what is stubbed, and what is missing.
3. Then propose a plan for Day 1 of `BACKEND-SPEC.md` §3 and stop.

Do not write or modify any code until I approve the plan. Do not run `ddl-auto`. If the existing
code and the schema disagree on anything outside the already-decided list, tell me — do not pick for
yourself.
