import { Router, type Request, type Response, type RequestHandler } from 'express';
import { z } from 'zod';
import { fail } from '../../shared/http.js';
import { createArticleDraftService } from './article-drafts.js';
import {
  AuthoringError,
  requireContentAuthor,
  type ContentActor,
  type ContentAuthenticator,
} from './authoring-access.js';
import type { KnowledgeDatabase } from './repository.js';

export function createArticleAuthoringRouter(
  db: KnowledgeDatabase | null,
  authenticate?: ContentAuthenticator,
) {
  const router = Router();
  const guard = requireContentAuthor(authenticate);
  const service = db ? createArticleDraftService(db) : null;
  const handle =
    (
      action: (
        request: Request,
        response: Response,
        actor: ContentActor,
        drafts: NonNullable<typeof service>,
      ) => Promise<void>,
    ): RequestHandler =>
    async (request, response, next) => {
      if (!service) {
        fail(response, 503, 'DATABASE_NOT_CONFIGURED', 'Chưa cấu hình cơ sở dữ liệu nội dung.');
        return;
      }
      try {
        await action(request, response, response.locals.contentActor as ContentActor, service);
      } catch (error) {
        if (error instanceof z.ZodError) {
          fail(
            response,
            400,
            'INVALID_INPUT',
            'Nội dung bài viết hoặc nguồn tham khảo không hợp lệ.',
          );
        } else if (error instanceof AuthoringError) {
          fail(response, error.status, error.code, error.message);
        } else {
          next(error);
        }
      }
    };

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
  return router;
}
