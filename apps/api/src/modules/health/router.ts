import { Router } from 'express';
import { modules } from '@health/contracts';
import { requireSession, notImplemented } from '../../shared/http.js';

export const healthRouter = Router();
healthRouter.get('/', (_request, response) =>
  response.json({ data: modules.find((item) => item.id === 'health') }),
);

// All private/authoring routes intentionally stop at requireSession until M5 implements auth.
healthRouter.get('/profile', requireSession, notImplemented);
healthRouter.put('/profile', requireSession, notImplemented);
healthRouter.delete('/profile', requireSession, notImplemented);
healthRouter.get('/metrics', requireSession, notImplemented);
healthRouter.put('/metrics/:date', requireSession, notImplemented);
healthRouter.delete('/metrics/:date', requireSession, notImplemented);
healthRouter.get('/statistics', requireSession, notImplemented);
