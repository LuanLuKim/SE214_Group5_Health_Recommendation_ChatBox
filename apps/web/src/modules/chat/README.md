# M4 Hội thoại với trợ lý AI

Use cases: **UC-06, UC-08, UC-09**. Owner: Đặng Việt Thành (`Vncnt-uit`).

## Navigation

- Frontend: `apps/web/src/modules/chat/`
- Backend: `backend/src/main/java/vn/edu/uit/se214/health/chat/`
- Database: `database/migrations/V004__chat.sql`
- Shared contracts: `packages/contracts/src/index.ts`
- Routes and access expectations: `docs/API.md`

## Integration boundary

Calls M1 before any generative AI; retrieves approved evidence from M2. M5 supplies session/consent/ownership. M2 reviews only an explicitly shared anonymous pair. No provider credentials or medical responses are configured in the scaffold.

## Extend this module

Keep UI in `Page.tsx` and module-local components/hooks. Backend code lives in the module package (Controller, Service, Repository); see its README.md. Do not call SQL from UI or route handlers. Add tests beside the relevant workspace test suite. These are starter files, not completed use cases.

Use shared contracts for cross-module messages. Discuss contract/migration changes in a PR so the other owners can update their integration. Do not edit another module simply to avoid defining its interface.
