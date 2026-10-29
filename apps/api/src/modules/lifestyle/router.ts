import { Router } from 'express';
import { modules } from '@health/contracts';
import { requireSession, notImplemented } from '../../shared/http.js';

export const lifestyleRouter = Router();
lifestyleRouter.get('/', (_request, response) =>
  response.json({ data: modules.find((item) => item.id === 'lifestyle') }),
);

// All private/authoring routes intentionally stop at requireSession until M5 implements auth.
lifestyleRouter.get('/templates', requireSession, notImplemented);
lifestyleRouter.get('/plan', requireSession, notImplemented);
lifestyleRouter.put('/plan', requireSession, notImplemented);
lifestyleRouter.put('/completions/:activityId/:date', requireSession, notImplemented);
lifestyleRouter.get('/reminders', requireSession, notImplemented);
lifestyleRouter.post('/reminders', requireSession, notImplemented);
lifestyleRouter.patch('/reminders/:id', requireSession, notImplemented);
lifestyleRouter.delete('/reminders/:id', requireSession, notImplemented);
