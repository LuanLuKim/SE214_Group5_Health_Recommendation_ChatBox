# M1 Rule Drafts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let M1 validate and persist warning-rule drafts while private routes remain protected until M5 provides real sessions.

**Architecture:** Define draft input with Zod in the shared contract. A safety service owns create, list, get, and edit rules; a repository owns parameterized SQL. Add a migration for fields missing from the initial safety table. Keep HTTP routes behind `requireSession` until M5 supplies trusted identity and expert authorization.

**Tech Stack:** TypeScript, Zod, Express, PostgreSQL, PGlite tests, React.

**Spec:** `docs/MODULES.md`, `docs/requirements/PROJECT_CONTEXT.md` (UC-16, rule review boundary).

## Global Constraints

- Only approved rules may be used for care direction; drafts are inactive.
- M2 owns approval; M5 owns real session and role checks.
- Never infer a low-risk result when no approved rules exist.
- Add a migration rather than modifying an applied migration.

## Review Focus

- Invalid or empty draft fields must fail validation.
- Edits to approved rules must not mutate active content.
- Forged role headers must not open private routes.
- Database query failure must not look like successful persistence.
- Synthetic draft data must not be treated as approved medical guidance.

---

### Task 1: Define draft shape and persistence

**Files:** `packages/contracts/src/index.ts`, `database/migrations/007_safety_drafts.sql`, `apps/api/src/modules/safety/repository.ts`, `apps/api/tests/safety.test.ts`

- [x] Write a failing test for a valid and invalid draft and observe the expected failure.
- [x] Add the contract and database migration, then rerun the focused test.
- [x] Write a failing PGlite test for create, list, get and draft-only update.
- [x] Implement parameterized repository queries and rerun focused tests.

### Task 2: Add M1 authoring service

**Files:** `apps/api/src/modules/safety/service.ts`, `apps/api/tests/safety.test.ts`

- [x] Write failing tests for draft creation and edit restrictions.
- [x] Implement business validation and persistence orchestration.
- [ ] Run focused tests, then the full test suite.

### Task 3: Prepare frontend and integration boundary

**Files:** `apps/web/src/modules/safety/Page.tsx`, `apps/api/src/modules/safety/router.ts`, `docs/API.md`

- [ ] Expose draft route behavior only after trusted session and expert guard exist.
- [x] Document route requirements and current protected behavior for M5/M2 handoff.
- [ ] Run `npm.cmd run check` and inspect the diff.
