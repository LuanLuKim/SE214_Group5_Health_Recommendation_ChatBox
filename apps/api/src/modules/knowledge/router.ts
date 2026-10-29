import { Router } from 'express';
import { modules } from '@health/contracts';
import { requireSession, notImplemented } from '../../shared/http.js';
import { createKnowledgeService } from './service.js';
import type { Config } from '../../config.js';
import type { Pool } from 'pg';
import { z } from 'zod';
import { fail } from '../../shared/http.js';

export function createKnowledgeRouter(config: Config, pool: Pool | null) {
  const knowledgeRouter = Router();
  const service = createKnowledgeService(config, pool);
  knowledgeRouter.get('/', (_request, response) =>
    response.json({ data: modules.find((item) => item.id === 'knowledge') }),
  );

  const querySchema = z.object({ q: z.string().trim().max(200).optional() });
  knowledgeRouter.get('/articles', async (_request, response) => {
    const query = querySchema.safeParse(_request.query);
    if (!query.success) {
      fail(response, 400, 'INVALID_QUERY', 'Từ khóa phải là văn bản tối đa 200 ký tự.');
      return;
    }
    const data = await service.listArticles(query.data.q ?? '');
    response.json({
      data,
      meta: {
        total: data.length,
        status: config.DATA_MODE === 'mock' ? 'SYNTHETIC_DATA' : 'DATABASE_RESULTS',
      },
    });
  });
  knowledgeRouter.get('/facilities', async (_request, response) => {
    const query = querySchema.safeParse(_request.query);
    if (!query.success) {
      fail(response, 400, 'INVALID_QUERY', 'Từ khóa phải là văn bản tối đa 200 ký tự.');
      return;
    }
    const data = await service.listFacilities(query.data.q ?? '');
    response.json({
      data,
      meta: {
        total: data.length,
        status: config.DATA_MODE === 'mock' ? 'SYNTHETIC_DATA' : 'DATABASE_RESULTS',
      },
    });
  });

  // All private/authoring routes intentionally stop at requireSession until M5 implements auth.
  knowledgeRouter.post('/articles', requireSession, notImplemented);
  knowledgeRouter.patch('/articles/:id', requireSession, notImplemented);
  knowledgeRouter.delete('/articles/:id', requireSession, notImplemented);
  knowledgeRouter.get('/reviews', requireSession, notImplemented);
  knowledgeRouter.post('/reviews/:id/decision', requireSession, notImplemented);
  knowledgeRouter.get('/feedback', requireSession, notImplemented);
  knowledgeRouter.patch('/feedback/:id', requireSession, notImplemented);
  return knowledgeRouter;
}
