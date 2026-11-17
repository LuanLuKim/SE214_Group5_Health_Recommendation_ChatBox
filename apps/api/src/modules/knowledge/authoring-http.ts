import type { Request, Response, RequestHandler } from 'express';
import { z } from 'zod';
import { fail } from '../../shared/http.js';
import { AuthoringError, type ContentActor } from './authoring-access.js';

export function createAuthoringHandler<Service>(service: Service | null) {
  return (
      action: (
        request: Request,
        response: Response,
        actor: ContentActor,
        service: Service,
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
          fail(response, 400, 'INVALID_INPUT', 'Nội dung hoặc nguồn tham khảo không hợp lệ.');
        } else if (error instanceof AuthoringError) {
          fail(response, error.status, error.code, error.message);
        } else {
          next(error);
        }
      }
    };
}

export function validateSubmissionBody(body: unknown): void {
  z.strictObject({}).parse(body ?? {});
}
