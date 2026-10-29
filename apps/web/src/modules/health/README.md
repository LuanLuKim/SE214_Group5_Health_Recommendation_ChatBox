# M3 Hồ sơ, chỉ số & thống kê

Use cases: **UC-04, UC-05, UC-19**. Owner: unassigned.

## Navigation

- Frontend: `apps/web/src/modules/health/`
- Backend: `apps/api/src/modules/health/`
- Database: `database/migrations/003_health.sql`
- Shared contracts: `packages/contracts/src/index.ts`
- Routes and access expectations: `docs/API.md`

## Integration boundary

M5 supplies session/consent/ownership enforcement. Own-data profiles and metrics are distinct from ADMIN-only aggregate statistics (UC-19). Never expose health data in admin statistics.

## Extend this module

Keep UI in `Page.tsx` and module-local components/hooks. Put HTTP parsing in `router.ts`, business rules in `service.ts`, and add a `repository.ts` for persistence. Do not call SQL from UI or route handlers. Add tests beside the relevant workspace test suite. These are starter files, not completed use cases.

Use shared contracts for cross-module messages. Discuss contract/migration changes in a PR so the other owners can update their integration. Do not edit another module simply to avoid defining its interface.
