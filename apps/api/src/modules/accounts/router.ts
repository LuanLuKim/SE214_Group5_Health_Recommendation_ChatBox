import { Router } from 'express';
import { modules } from '@health/contracts';
import { requireSession, notImplemented } from '../../shared/http.js';

export const accountsRouter = Router();
accountsRouter.get('/', (_request, response) =>
  response.json({ data: modules.find((item) => item.id === 'accounts') }),
);
accountsRouter.post('/register', notImplemented);
accountsRouter.post('/login', notImplemented);
accountsRouter.post('/forgot-password', notImplemented);
accountsRouter.post('/reset-password', notImplemented);
accountsRouter.post('/confirm-deletion', notImplemented);

// All private/authoring routes intentionally stop at requireSession until M5 implements auth.
accountsRouter.post('/logout', requireSession, notImplemented);
accountsRouter.get('/me', requireSession, notImplemented);
accountsRouter.put('/me', requireSession, notImplemented);
accountsRouter.post('/me/change-password', requireSession, notImplemented);
accountsRouter.post('/me/deletion-request', requireSession, notImplemented);
accountsRouter.get('/users', requireSession, notImplemented);
accountsRouter.patch('/users/:id/status', requireSession, notImplemented);
accountsRouter.put('/users/:id/roles', requireSession, notImplemented);
