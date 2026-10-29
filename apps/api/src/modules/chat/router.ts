import { Router } from 'express';
import { modules } from '@health/contracts';
import { requireSession, notImplemented } from '../../shared/http.js';

export const chatRouter = Router();
chatRouter.get('/', (_request, response) =>
  response.json({ data: modules.find((item) => item.id === 'chat') }),
);

// All private/authoring routes intentionally stop at requireSession until M5 implements auth.
chatRouter.post('/messages', requireSession, notImplemented);
chatRouter.get('/sessions', requireSession, notImplemented);
chatRouter.get('/sessions/:id/messages', requireSession, notImplemented);
chatRouter.delete('/sessions/:id', requireSession, notImplemented);
chatRouter.post('/messages/:id/feedback', requireSession, notImplemented);
