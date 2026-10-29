# M5 Tài khoản & phân quyền

Use cases: **UC-01, UC-02, UC-03, UC-18**. Owner: unassigned.

## Navigation

- Frontend: `apps/web/src/modules/accounts/`
- Backend: `apps/api/src/modules/accounts/`
- Database: `database/migrations/005_accounts.sql`
- Shared contracts: `packages/contracts/src/index.ts`
- Routes and access expectations: `docs/API.md`

## Integration boundary

Owns the shared session, role, consent and ownership boundary for all modules. Replace the fail-closed shared requireSession before enabling private operations. New registrations must never choose ADMIN/CONTENT_EXPERT roles. UC-18 belongs here, while UC-19 statistics belongs to M3.

## Extend this module

Keep UI in `Page.tsx` and module-local components/hooks. Put HTTP parsing in `router.ts`, business rules in `service.ts`, and add a `repository.ts` for persistence. Do not call SQL from UI or route handlers. Add tests beside the relevant workspace test suite. These are starter files, not completed use cases.

Use shared contracts for cross-module messages. Discuss contract/migration changes in a PR so the other owners can update their integration. Do not edit another module simply to avoid defining its interface.
