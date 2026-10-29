import { Router } from 'express';
import { modules } from '@health/contracts';
import { requireSession, notImplemented } from '../../shared/http.js';

export const safetyRouter = Router();
safetyRouter.get('/', (_request, response) =>
  response.json({ data: modules.find((item) => item.id === 'safety') }),
);

// All private/authoring routes intentionally stop at requireSession until M5 implements auth.
safetyRouter.post('/evaluate', requireSession, notImplemented);
safetyRouter.get('/rules', requireSession, notImplemented);
safetyRouter.post('/rules', requireSession, notImplemented);
safetyRouter.patch('/rules/:id', requireSession, notImplemented);
