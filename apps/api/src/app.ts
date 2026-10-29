import express from 'express';
import cors from 'cors';
import helmet from 'helmet';
import { rateLimit } from 'express-rate-limit';
import pg from 'pg';
import { modules } from '@health/contracts';
import type { Config } from './config.js';
import { requestContext, errorHandler, fail } from './shared/http.js';
import { safetyRouter } from './modules/safety/router.js';
import { createKnowledgeRouter } from './modules/knowledge/router.js';
import { healthRouter } from './modules/health/router.js';
import { chatRouter } from './modules/chat/router.js';
import { accountsRouter } from './modules/accounts/router.js';
import { lifestyleRouter } from './modules/lifestyle/router.js';

export function createApp(config: Config) {
  const app = express();
  const pool =
    config.DATA_MODE === 'postgres' && config.DATABASE_URL
      ? new pg.Pool({
          connectionString: config.DATABASE_URL,
          max: 3,
          connectionTimeoutMillis: 5000,
          query_timeout: 5000,
        })
      : null;
  pool?.on('error', () => console.error('Database connection unavailable.'));
  app.locals.pool = pool;
  app.disable('x-powered-by');
  app.use(requestContext);
  app.use(helmet());
  app.use(cors({ origin: config.WEB_ORIGIN, credentials: false }));
  app.use(
    rateLimit({
      windowMs: 60_000,
      limit: 120,
      standardHeaders: 'draft-8',
      legacyHeaders: false,
      handler: (_request, response) => {
        fail(response, 429, 'RATE_LIMITED', 'Quá nhiều yêu cầu. Vui lòng thử lại sau.');
      },
    }),
  );
  app.use(express.json({ limit: '32kb' }));
  app.get('/healthz', (_request, response) =>
    response.json({ data: { status: 'ok', service: 'health-assistant-api', mode: 'scaffold' } }),
  );
  app.get('/api/v1/modules', (_request, response) => response.json({ data: modules }));
  app.use('/api/v1/safety', safetyRouter);
  app.use('/api/v1/knowledge', createKnowledgeRouter(config, pool));
  app.use('/api/v1/health', healthRouter);
  app.use('/api/v1/chat', chatRouter);
  app.use('/api/v1/accounts', accountsRouter);
  app.use('/api/v1/lifestyle', lifestyleRouter);
  app.use((_request, response) => {
    fail(response, 404, 'NOT_FOUND', 'Không tìm thấy đường dẫn.');
  });
  app.use(errorHandler);
  return app;
}
