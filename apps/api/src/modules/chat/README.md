# M4 Hội thoại với trợ lý AI

Use cases: **UC-06, UC-08, UC-09**. Owner: unassigned.

## Navigation

- Frontend: `apps/web/src/modules/chat/`
- Backend: `apps/api/src/modules/chat/`
- Database: `database/migrations/004_chat.sql`
- Shared contracts: `packages/contracts/src/index.ts`
- Routes and access expectations: `docs/API.md`

## Integration boundary

Calls M1 before any generative AI; retrieves approved evidence from M2. M5 supplies session/consent/ownership. M2 reviews only an explicitly shared anonymous pair. No provider credentials or medical responses are configured in the scaffold.

## Extend this module

Keep UI in `Page.tsx` and module-local components/hooks. Put HTTP parsing in `router.ts`, business rules in `service.ts`, and add a `repository.ts` for persistence. Do not call SQL from UI or route handlers. Add tests beside the relevant workspace test suite. These are starter files, not completed use cases.

Use shared contracts for cross-module messages. Discuss contract/migration changes in a PR so the other owners can update their integration. Do not edit another module simply to avoid defining its interface.
