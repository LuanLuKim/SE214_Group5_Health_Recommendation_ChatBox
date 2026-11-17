import { Router } from 'express';
import { createArticleDraftService } from './article-drafts.js';
import { requireContentAuthor, type ContentAuthenticator } from './authoring-access.js';
import type { KnowledgeDatabase } from './repository.js';
import { createAuthoringHandler, validateSubmissionBody } from './authoring-http.js';

export function createArticleAuthoringRouter(
  db: KnowledgeDatabase | null,
  authenticate?: ContentAuthenticator,
) {
  const router = Router();
  const guard = requireContentAuthor(authenticate);
  const service = db ? createArticleDraftService(db) : null;
  const handle = createAuthoringHandler(service);

  router.get(
    '/authoring/articles',
    guard,
    handle(async (_request, response, actor, drafts) => {
      const data = await drafts.list(actor);
      response.json({ data, meta: { status: 'DATABASE_RESULTS', total: data.length } });
    }),
  );
  router.get(
    '/authoring/articles/:id',
    guard,
    handle(async (request, response, actor, drafts) => {
      response.json({ data: await drafts.get(actor, request.params.id as string) });
    }),
  );
  router.post(
    '/articles',
    guard,
    handle(async (request, response, actor, drafts) => {
      const data = await drafts.create(actor, request.body);
      response.status(201).json({ data });
    }),
  );
  router.patch(
    '/articles/:id',
    guard,
    handle(async (request, response, actor, drafts) => {
      response.json({
        data: await drafts.update(actor, request.params.id as string, request.body),
      });
    }),
  );
  router.delete(
    '/articles/:id',
    guard,
    handle(async (request, response, actor, drafts) => {
      await drafts.delete(actor, request.params.id as string);
      response.status(204).end();
    }),
  );
  router.post(
    '/articles/:id/submit',
    guard,
    handle(async (request, response, actor, drafts) => {
      validateSubmissionBody(request.body);
      response.json({ data: await drafts.submit(actor, request.params.id as string) });
    }),
  );
  return router;
}
