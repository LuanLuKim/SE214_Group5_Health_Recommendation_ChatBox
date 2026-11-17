import type { Request, RequestHandler } from 'express';
import { roleSchema } from '@health/contracts';
import { z } from 'zod';
import { fail, requireSession } from '../../shared/http.js';

const actorSchema = z.object({ userId: z.uuid(), roles: z.array(roleSchema) });
export type ContentActor = z.infer<typeof actorSchema>;

// M5 supplies a resolver for validated, active sessions. Never read identity from headers here.
export type ContentAuthenticator = (request: Request) => Promise<ContentActor | null>;

export class AuthoringError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

export function assertContentAuthor(actor: ContentActor): void {
  if (!actorSchema.safeParse(actor).success) {
    throw new AuthoringError(401, 'UNAUTHENTICATED', 'Vui lòng đăng nhập.');
  }
  if (!actor.roles.some((role) => role === 'CONTENT_EXPERT' || role === 'ADMIN')) {
    throw new AuthoringError(403, 'FORBIDDEN', 'Bạn không có quyền quản lý nội dung.');
  }
}

export function requireContentAuthor(authenticate?: ContentAuthenticator): RequestHandler {
  if (!authenticate) return requireSession;
  return async (request, response, next) => {
    const actor = await authenticate(request);
    if (!actor) {
      fail(response, 401, 'UNAUTHENTICATED', 'Vui lòng đăng nhập.');
      return;
    }
    try {
      assertContentAuthor(actor);
    } catch (error) {
      if (!(error instanceof AuthoringError)) throw error;
      fail(response, error.status, error.code, error.message);
      return;
    }
    response.locals.contentActor = actor;
    next();
  };
}
