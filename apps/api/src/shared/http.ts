import { randomUUID } from 'node:crypto';
import type { ErrorRequestHandler, RequestHandler, Response } from 'express';

export function fail(response: Response, status: number, code: string, message: string) {
  return response
    .status(status)
    .json({ error: { code, message, requestId: response.locals.requestId as string } });
}
export const requestContext: RequestHandler = (_request, response, next) => {
  response.locals.requestId = randomUUID();
  response.setHeader('X-Request-ID', response.locals.requestId as string);
  response.setHeader('Cache-Control', 'no-store');
  next();
};
// Deliberately fail closed. Replace with validated sessions + server-side ownership checks in M5.
// Never trust X-User-ID, query parameters, or a client-selected role as authentication.
export const requireSession: RequestHandler = (_request, response) => {
  fail(
    response,
    503,
    'AUTH_NOT_CONFIGURED',
    'Chức năng tài khoản chưa được triển khai trong bản khung.',
  );
};
export const notImplemented: RequestHandler = (_request, response) => {
  fail(response, 501, 'NOT_IMPLEMENTED', 'Chức năng này đang chờ triển khai.');
};
export const errorHandler: ErrorRequestHandler = (error: unknown, _request, response, _next) => {
  if (response.headersSent) return;
  const status =
    error && typeof error === 'object' && 'status' in error ? Number(error.status) : 500;
  if (status === 400) {
    fail(response, 400, 'INVALID_REQUEST', 'Dữ liệu yêu cầu không hợp lệ.');
    return;
  }
  if (status === 413) {
    fail(response, 413, 'PAYLOAD_TOO_LARGE', 'Nội dung vượt quá giới hạn cho phép.');
    return;
  }
  // No error object, body, prompt, credential, or health data is sent to logs or clients.
  fail(response, 500, 'INTERNAL_ERROR', 'Không thể xử lý yêu cầu. Vui lòng thử lại.');
};
