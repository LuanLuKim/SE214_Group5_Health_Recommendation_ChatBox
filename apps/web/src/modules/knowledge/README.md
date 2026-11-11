# M2 Tra cứu & quản lý nội dung

Use cases: **UC-10, UC-11, UC-15, UC-17**. Owner: Trường Giang (`Ziang05`).

## Navigation

- Frontend: `apps/web/src/modules/knowledge/`
- Backend: `backend/src/main/java/vn/edu/uit/se214/health/knowledge/`
- Database: `database/migrations/V002__knowledge.sql`
- Shared contracts: `packages/contracts/src/index.ts`
- Routes and access expectations: `docs/API.md`

## Integration boundary

Provides approved-only retrieval to M4, approved rule review to M1, template review to M6, and anonymous feedback review for M4. Facilities are preloaded; no import-management UI is required. Only CONTENT_EXPERT may approve or read feedback. Admin content authoring remains an SRS ambiguity.

## Extend this module

Keep UI in `Page.tsx` and module-local components/hooks. Backend code lives in the module package (Controller, Service, Repository); see its README.md. Do not call SQL from UI or route handlers. Add tests beside the relevant workspace test suite. These are starter files, not completed use cases.

Use shared contracts for cross-module messages. Discuss contract/migration changes in a PR so the other owners can update their integration. Do not edit another module simply to avoid defining its interface.
