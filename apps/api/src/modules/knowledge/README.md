# M2 Tra cứu & quản lý nội dung

Use cases: **UC-10, UC-11, UC-15, UC-17**. Owner: unassigned.

## Navigation

- Frontend: `apps/web/src/modules/knowledge/`
- Backend: `apps/api/src/modules/knowledge/`
- Database: `database/migrations/002_knowledge.sql`
- Shared contracts: `packages/contracts/src/index.ts`
- Routes and access expectations: `docs/API.md`

## Integration boundary

Provides approved-only retrieval to M4, approved rule review to M1, template review to M6, and anonymous feedback review for M4. Facilities are preloaded; no import-management UI is required. CONTENT_EXPERT and ADMIN may author articles/templates according to the supplied permissions matrix. Only CONTENT_EXPERT may approve or read feedback.

## Extend this module

Template authoring uses `template-drafts.ts`, `template-repository.ts`, and `template-router.ts`, storing data in `lifestyle.templates`. Both articles and templates support POST `/:id/submit`: full source/content validation followed by an atomic transition to PENDING_REVIEW for the validated revision. UC-17 must review that revision; authoring routes never approve content. M6 may inject `ApprovedTemplateProvider` using `createApprovedTemplateProvider(db)` to obtain verified approved templates without editorial identities. Personal plan selection and allergy filtering remain in M6. Required fields and goal labels are documented in `docs/API.md` for coordination.

UC-15 article authoring is implemented in `article-drafts.ts`, `repository.ts`, and `article-router.ts`. The management roles are CONTENT_EXPERT and ADMIN. `authoring-access.ts` accepts a server-configured M5 session resolver; without it, the application keeps returning AUTH_NOT_CONFIGURED. Private list/detail routes use `/authoring/articles`; public article search stays separate. Only DRAFT/RETURNED rows can be changed or deleted. Creation and edits cannot publish content or set verification dates. See `docs/API.md` for fields, M7 mapping and response codes.

Keep UI in `Page.tsx` and module-local components/hooks. Put HTTP parsing in `router.ts`, business rules in `service.ts`, and add a `repository.ts` for persistence. Do not call SQL from UI or route handlers. Add tests beside the relevant workspace test suite. These are starter files, not completed use cases.

Use shared contracts for cross-module messages. Discuss contract/migration changes in a PR so the other owners can update their integration. Do not edit another module simply to avoid defining its interface.
