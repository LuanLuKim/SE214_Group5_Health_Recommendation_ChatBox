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
