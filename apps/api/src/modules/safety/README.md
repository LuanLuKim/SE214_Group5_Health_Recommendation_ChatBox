# M1 Safety Gate & quy tắc cảnh báo

Use cases: **UC-07, UC-16**. Owner: Bảo Quỳnh (`DontMadWhenBad`).

## Navigation

- Frontend: `apps/web/src/modules/safety/`
- Backend: `apps/api/src/modules/safety/`
- Database: `database/migrations/001_safety.sql`
- Shared contracts: `packages/contracts/src/index.ts`
- Routes and access expectations: `docs/API.md`

## Integration boundary

Consumes approved rule versions from M2 review. Exposes SafetyEvaluator to M4. An unavailable evaluator returns no care label; it must never invent a low-risk result.

## Current draft foundation

`createRuleDraftService` validates, creates, lists, reads and edits rule drafts through the
parameterized repository. Its input shape is `ruleDraftInputSchema` in shared contracts.
The migration `007_safety_drafts.sql` adds source name, clarification fields, specialty
IDs and update time. Only `DRAFT` and `RETURNED` records can be edited; an edit to a
returned rule resets it to `DRAFT`. No draft is evaluated or activated.

The HTTP authoring routes still stop at `requireSession` and return 503 until M5
provides trusted sessions, consent and expert authorization. M2 owns approval and
must define the approved version before the evaluator can use rules. The `all` and
`any` condition lists describe authored terms; their matching semantics must be
agreed with M4 before clinical evaluation is implemented.

## Extend this module

Keep UI in `Page.tsx` and module-local components/hooks. Put HTTP parsing in `router.ts`, business rules in `service.ts`, and add a `repository.ts` for persistence. Do not call SQL from UI or route handlers. Add tests beside the relevant workspace test suite. These are starter files, not completed use cases.

Use shared contracts for cross-module messages. Discuss contract/migration changes in a PR so the other owners can update their integration. Do not edit another module simply to avoid defining its interface.
