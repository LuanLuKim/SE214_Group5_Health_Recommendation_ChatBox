# M1 Safety Gate & quy tắc cảnh báo

Use cases: **UC-07, UC-16**. Owner: Bảo Quỳnh (`DontMadWhenBad`).

## Navigation

- Frontend: `apps/web/src/modules/safety/`
- Backend: `backend/src/main/java/vn/edu/uit/se214/health/safety/`
- Database: `database/migrations/V001__safety.sql`
- Shared contracts: `packages/contracts/src/index.ts`
- Routes and access expectations: `docs/API.md`

## Integration boundary

Consumes approved rule versions from M2 review. Exposes SafetyEvaluator to M4. An unavailable evaluator returns no care label; it must never invent a low-risk result.

## Extend this module

Keep UI in `Page.tsx` and module-local components/hooks. Backend code lives in the module package (Controller, Service, Repository); see its README.md. Do not call SQL from UI or route handlers. Add tests beside the relevant workspace test suite. These are starter files, not completed use cases.

Use shared contracts for cross-module messages. Discuss contract/migration changes in a PR so the other owners can update their integration. Do not edit another module simply to avoid defining its interface.

## UC-16 screens

- `RulesWorkspace.tsx`: list filtered by review state, rule detail with "Gửi duyệt", and the draft form. Approval itself belongs to the shared UC-17 queue (M2).
- `rules.ts`: pure logic (which actions a state allows, form validation with `ruleDraftInputSchema`, what to do with each API error code). Test it in `apps/web/tests/safety.test.ts`.
- `labels.ts`: the only place for Vietnamese care-level and review-state labels.
- `client.ts`: `RulesClient` has two implementations. The HTTP one calls `/api/v1/safety/rules`. The in-memory mock is used only in `npm run dev`, because the real routes return 503 until M5 provides sessions. Turn the mock off with `VITE_SAFETY_RULES_MOCK=false`. A production build always uses the API.
