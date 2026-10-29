# M6 Lộ trình sinh hoạt & nhắc nhở

Use cases: **UC-12, UC-13, UC-14**. Owner: unassigned.

## Navigation

- Frontend: `apps/web/src/modules/lifestyle/`
- Backend: `apps/api/src/modules/lifestyle/`
- Database: `database/migrations/006_lifestyle.sql`
- Shared contracts: `packages/contracts/src/index.ts`
- Routes and access expectations: `docs/API.md`

## Integration boundary

Uses M3 own profile/allergy data with M5 access controls. M2 approves templates. Plans select approved templates; no AI-generated menus or training plans. Reminder delivery and retries must be duplicate-safe.

## Extend this module

Keep UI in `Page.tsx` and module-local components/hooks. Put HTTP parsing in `router.ts`, business rules in `service.ts`, and add a `repository.ts` for persistence. Do not call SQL from UI or route handlers. Add tests beside the relevant workspace test suite. These are starter files, not completed use cases.

Use shared contracts for cross-module messages. Discuss contract/migration changes in a PR so the other owners can update their integration. Do not edit another module simply to avoid defining its interface.
