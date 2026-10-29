import test from 'node:test';
import assert from 'node:assert/strict';
import request from 'supertest';
import { modules, careLevelSchema, moduleListSchema } from '@health/contracts';
import { createApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';
import { unavailableSafetyEvaluator } from '../src/modules/safety/service.js';

const app = createApp(loadConfig({}));
test('health and module registry expose six modules covering each of 19 UC once', async () => {
  assert.equal((await request(app).get('/healthz')).status, 200);
  const response = await request(app).get('/api/v1/modules');
  const data = moduleListSchema.parse(response.body.data);
  assert.deepEqual(data, modules);
  const useCases = data.flatMap((item) => item.useCases);
  assert.equal(useCases.length, 19);
  assert.equal(new Set(useCases).size, 19);
  for (const module of data)
    assert.equal((await request(app).get(module.apiPrefix)).body.data.id, module.id);
});
test('public synthetic search works and does not expose the draft fixture', async () => {
  const articles = await request(app).get('/api/v1/knowledge/articles?q=giấc');
  assert.equal(articles.status, 200);
  assert.equal(articles.body.meta.status, 'SYNTHETIC_DATA');
  assert.equal(articles.body.data.length, 1);
  assert.match(articles.body.data[0].title, /^\[TEST\]/);
  const empty = await request(app).get('/api/v1/knowledge/articles?q=not-present');
  assert.deepEqual(empty.body.data, []);
  const facilities = await request(app).get('/api/v1/knowledge/facilities');
  assert.equal(facilities.body.data.length, 1);
  assert.match(facilities.body.data[0].address, /không có thật/);
  assert.equal((await request(app).get('/api/v1/knowledge/articles?q=a&q=b')).status, 400);
  assert.equal(
    (await request(app).get(`/api/v1/knowledge/articles?q=${'x'.repeat(201)}`)).status,
    400,
  );
});
test('private, clinical, review and admin paths fail closed even with forged identity', async () => {
  const paths: Array<['get' | 'post' | 'put', string]> = [
    ['post', '/safety/evaluate'],
    ['get', '/safety/rules'],
    ['post', '/knowledge/articles'],
    ['get', '/knowledge/reviews'],
    ['get', '/knowledge/feedback'],
    ['get', '/health/profile'],
    ['get', '/health/metrics'],
    ['get', '/health/statistics'],
    ['post', '/chat/messages'],
    ['get', '/chat/sessions'],
    ['post', '/chat/messages/example/feedback'],
    ['get', '/accounts/me'],
    ['get', '/accounts/users'],
    ['get', '/lifestyle/templates'],
    ['get', '/lifestyle/reminders'],
  ];
  for (const [method, path] of paths) {
    const response = await request(app)
      [method](`/api/v1${path}`)
      .set('X-User-ID', 'admin')
      .set('X-Role', 'ADMIN');
    assert.equal(response.status, 503, path);
    assert.equal(response.body.error.code, 'AUTH_NOT_CONFIGURED');
    assert.ok(response.body.error.requestId);
    assert.equal(response.body.data, undefined);
  }
  assert.equal(
    (await request(app).post('/api/v1/accounts/register').send({ role: 'ADMIN' })).status,
    501,
  );
});
test('safe errors, response headers and request size limits', async () => {
  const missing = await request(app).get('/missing');
  assert.equal(missing.status, 404);
  assert.equal(missing.headers['x-powered-by'], undefined);
  assert.ok(missing.headers['x-content-type-options']);
  assert.equal(missing.body.error.requestId, missing.headers['x-request-id']);
  const malformed = await request(app)
    .post('/api/v1/accounts/login')
    .set('Content-Type', 'application/json')
    .send('{private-test-sentinel');
  assert.equal(malformed.status, 400);
  assert.doesNotMatch(JSON.stringify(malformed.body), /private-test-sentinel|SyntaxError|stack/);
  const large = await request(app)
    .post('/api/v1/accounts/login')
    .send({ text: 'x'.repeat(40000) });
  assert.equal(large.status, 413);
});
test('unconfigured Safety Gate yields no care direction and has exactly five labels', async () => {
  const result = await unavailableSafetyEvaluator.evaluate({ text: 'synthetic test' });
  assert.equal(result.status, 'UNAVAILABLE');
  assert.equal(result.careLevel, null);
  assert.equal(careLevelSchema.options.length, 5);
  assert.equal(careLevelSchema.safeParse('OUT_OF_SCOPE').success, false);
});
