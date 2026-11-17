import { Router } from 'express';
import { requireContentAuthor, type ContentAuthenticator } from './authoring-access.js';
import { createAuthoringHandler, validateSubmissionBody } from './authoring-http.js';
import type { KnowledgeDatabase } from './repository.js';
import { createTemplateDraftService } from './template-drafts.js';

export function createTemplateAuthoringRouter(
  db: KnowledgeDatabase | null,
  authenticate?: ContentAuthenticator,
) {
  const router = Router();
  const guard = requireContentAuthor(authenticate);
  const handle = createAuthoringHandler(db ? createTemplateDraftService(db) : null);
  router.get(
    '/authoring/templates',
    guard,
    handle(async (_request, response, actor, drafts) => {
      const data = await drafts.list(actor);
      response.json({ data, meta: { status: 'DATABASE_RESULTS', total: data.length } });
    }),
  );
  router.get(
    '/authoring/templates/:id',
    guard,
    handle(async (request, response, actor, drafts) => {
      response.json({ data: await drafts.get(actor, request.params.id as string) });
    }),
  );
  router.post(
    '/templates',
    guard,
    handle(async (request, response, actor, drafts) => {
      response.status(201).json({ data: await drafts.create(actor, request.body) });
    }),
  );
  router.patch(
    '/templates/:id',
    guard,
    handle(async (request, response, actor, drafts) => {
      response.json({
        data: await drafts.update(actor, request.params.id as string, request.body),
      });
    }),
  );
  router.delete(
    '/templates/:id',
    guard,
    handle(async (request, response, actor, drafts) => {
      await drafts.delete(actor, request.params.id as string);
      response.status(204).end();
    }),
  );
  router.post(
    '/templates/:id/submit',
    guard,
    handle(async (request, response, actor, drafts) => {
      validateSubmissionBody(request.body);
      response.json({ data: await drafts.submit(actor, request.params.id as string) });
    }),
  );
  return router;
}
