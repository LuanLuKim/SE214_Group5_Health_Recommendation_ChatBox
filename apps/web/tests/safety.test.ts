import test from 'node:test';
import assert from 'node:assert/strict';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { careLevelSchema, reviewStatusSchema, type RuleDraftInput } from '@health/contracts';
import { careLevelLabels, reviewStatusLabels } from '../src/modules/safety/labels';
import {
  emptyRuleForm,
  errorView,
  filterRules,
  formFromRule,
  lines,
  ruleActions,
  validateRuleForm,
  type RuleForm,
} from '../src/modules/safety/rules';
import { ApiError, createHttpRulesClient, createMockRulesClient, mockSeed } from '../src/modules/safety/client';
import { RulesWorkspace } from '../src/modules/safety/RulesWorkspace';

const draft: RuleDraftInput = {
  name: '[TEST] Quy tắc thử nghiệm',
  conditions: { all: ['__SYNTHETIC_ONLY__sign'], any: [] },
  requiredFields: ['__SYNTHETIC_ONLY__onset'],
  careLevel: 'SEEK_CARE_SOON',
  specialtyIds: [],
  sourceName: 'Nguồn thử nghiệm',
  sourceUrl: 'https://example.invalid/rule',
};
const validForm: RuleForm = formFromRule(draft);

async function rejects(promise: Promise<unknown>, code: string) {
  await assert.rejects(promise, (error: unknown) => error instanceof ApiError && error.code === code);
}

test('exactly the five care levels and five review states have distinct Vietnamese labels', () => {
  assert.deepEqual(Object.keys(careLevelLabels).sort(), [...careLevelSchema.options].sort());
  assert.deepEqual(Object.keys(reviewStatusLabels).sort(), [...reviewStatusSchema.options].sort());
  const labels = careLevelSchema.options.map((level) => careLevelLabels[level].label);
  assert.equal(new Set(labels).size, 5);
  assert.equal(careLevelLabels.EMERGENCY_NOW.label, 'Cấp cứu ngay');
  for (const level of careLevelSchema.options) assert.ok(careLevelLabels[level].hint.length > 0);
});

test('only DRAFT and RETURNED rules are editable, and only sourced ones can be submitted', () => {
  const sourced = { sourceName: 'Nguồn', sourceUrl: 'https://example.invalid/x' };
  assert.deepEqual(ruleActions({ ...sourced, reviewStatus: 'DRAFT' }), { canEdit: true, canSubmit: true, submitBlockedReason: null });
  assert.equal(ruleActions({ ...sourced, reviewStatus: 'RETURNED' }).canSubmit, true);
  for (const status of ['PENDING_REVIEW', 'APPROVED', 'RETIRED'] as const) {
    assert.deepEqual(ruleActions({ ...sourced, reviewStatus: status }), { canEdit: false, canSubmit: false, submitBlockedReason: null });
  }
  for (const source of [
    { sourceName: '  ', sourceUrl: 'https://example.invalid/x' },
    { sourceName: 'Nguồn', sourceUrl: '' },
    { sourceName: 'Nguồn', sourceUrl: 'file:///local' },
    { sourceName: 'Nguồn', sourceUrl: 'https://user:secret@example.invalid/x' },
  ]) {
    const actions = ruleActions({ ...source, reviewStatus: 'DRAFT' });
    assert.equal(actions.canEdit, true);
    assert.equal(actions.canSubmit, false);
    assert.ok(actions.submitBlockedReason);
  }
});

test('the form validates with the shared contract and maps errors to fields', () => {
  const ok = validateRuleForm({ ...validForm, name: '  [TEST] có khoảng trắng  ', any: 'a\n\n  b  \n' });
  assert.ok(ok.ok);
  assert.equal(ok.input.name, '[TEST] có khoảng trắng');
  assert.deepEqual(ok.input.conditions.any, ['a', 'b']);

  const empty = validateRuleForm(emptyRuleForm);
  assert.equal(empty.ok, false);
  if (!empty.ok) {
    for (const field of ['name', 'all', 'careLevel', 'sourceName', 'sourceUrl'] as const) assert.ok(empty.errors[field], field);
    assert.equal(empty.errors.any, undefined);
  }
  for (const [field, patch] of [
    ['careLevel', { careLevel: 'LOW' as RuleForm['careLevel'] }],
    ['sourceUrl', { sourceUrl: 'https://user:secret@example.invalid/x' }],
    ['sourceUrl', { sourceUrl: 'file:///local' }],
    ['all', { all: Array.from({ length: 21 }, (_, i) => `t${i}`).join('\n') }],
  ] as const) {
    const result = validateRuleForm({ ...validForm, ...patch });
    assert.equal(result.ok, false, field);
    if (!result.ok) assert.ok(result.errors[field], field);
  }
  assert.deepEqual(lines(' x \r\n\r\ny'), ['x', 'y']);
});

test('error codes map to screen behaviour', () => {
  assert.equal(errorView('AUTH_NOT_CONFIGURED').inline, false);
  assert.match(errorView('AUTH_NOT_CONFIGURED').message, /M5/);
  assert.equal(errorView('RULE_SOURCE_REQUIRED').inline, true);
  assert.equal(errorView('RULE_NOT_EDITABLE').reload, true);
  assert.equal(errorView('RULE_CHANGED').reload, true);
  assert.equal(errorView('SOMETHING_NEW', 'Thông điệp từ API').message, 'Thông điệp từ API');
});

test('mock client follows the backend lifecycle and error codes', async () => {
  const client = createMockRulesClient([], () => new Date('2026-11-15T00:00:00Z'));
  assert.equal(client.mode, 'mock');
  const created = await client.create(draft);
  assert.equal(created.reviewStatus, 'DRAFT');
  assert.equal(created.isSynthetic, true);
  assert.equal((await client.list()).length, 1);

  const edited = await client.update(created.id, { ...draft, name: '[TEST] Đã sửa' });
  assert.equal(edited.name, '[TEST] Đã sửa');
  const pending = await client.submit(created.id);
  assert.equal(pending.reviewStatus, 'PENDING_REVIEW');
  await rejects(client.update(created.id, draft), 'RULE_NOT_EDITABLE');
  await rejects(client.submit(created.id), 'RULE_NOT_EDITABLE');
  await rejects(client.get('missing'), 'RULE_NOT_FOUND');

  // Returned results are copies: mutating them must not change stored rules.
  (await client.get(created.id)).name = 'mutated';
  assert.equal((await client.get(created.id)).name, '[TEST] Đã sửa');
});

test('mock seed shows every review state and rejects submitting a rule without source', async () => {
  const seed = mockSeed();
  assert.deepEqual(new Set(seed.map((rule) => rule.reviewStatus)), new Set(reviewStatusSchema.options));
  assert.ok(seed.every((rule) => rule.isSynthetic && rule.name.startsWith('[TEST]')));
  assert.equal(filterRules(seed, 'DRAFT').length, 2);
  assert.equal(filterRules(seed, 'ALL').length, seed.length);

  const client = createMockRulesClient(seed);
  const unsourced = seed.find((rule) => rule.sourceName === '')!;
  await rejects(client.submit(unsourced.id), 'RULE_SOURCE_REQUIRED');
  assert.equal((await client.get(unsourced.id)).reviewStatus, 'DRAFT');
  const returned = seed.find((rule) => rule.reviewStatus === 'RETURNED')!;
  assert.equal((await client.submit(returned.id)).reviewStatus, 'PENDING_REVIEW');
});

test('http client calls the UC-16 routes and surfaces API error codes', async () => {
  const calls: Array<{ url: string; method: string; body: unknown }> = [];
  const replies: Response[] = [
    Response.json({ data: [] }),
    Response.json({ data: { id: 'r1' } }, { status: 201 }),
    Response.json({ data: { id: 'r1' } }),
    Response.json({ data: { id: 'r1' } }),
    Response.json({ error: { code: 'AUTH_NOT_CONFIGURED', message: 'Chưa cấu hình', requestId: 'x' } }, { status: 503 }),
    new Response('not json', { status: 502 }),
  ];
  const client = createHttpRulesClient('http://api.test/', async (url, init) => {
    calls.push({ url, method: init?.method ?? 'GET', body: init?.body ? JSON.parse(String(init.body)) : undefined });
    return replies.shift()!;
  });
  assert.equal(client.mode, 'api');
  assert.deepEqual(await client.list(), []);
  await client.create(draft);
  await client.update('r 1', draft);
  await client.submit('r1');
  assert.deepEqual(
    calls.map((c) => `${c.method} ${c.url}`),
    [
      'GET http://api.test/api/v1/safety/rules',
      'POST http://api.test/api/v1/safety/rules',
      'PATCH http://api.test/api/v1/safety/rules/r%201',
      'POST http://api.test/api/v1/safety/rules/r1/submit',
    ],
  );
  assert.deepEqual(calls[1]!.body, draft);
  await assert.rejects(client.list(), (e: unknown) => e instanceof ApiError && e.code === 'AUTH_NOT_CONFIGURED' && e.status === 503);
  await rejects(client.list(), 'HTTP_502');

  const offline = createHttpRulesClient('', async () => {
    throw new TypeError('fetch failed');
  });
  await rejects(offline.list(), 'NETWORK_ERROR');
});

test('workspace shows the mock banner only for the mock client', () => {
  const mock = renderToStaticMarkup(createElement(RulesWorkspace, { client: createMockRulesClient() }));
  assert.match(mock, /Chế độ giả lập/);
  assert.match(mock, /Soạn quy tắc/);
  const api = renderToStaticMarkup(createElement(RulesWorkspace, { client: createHttpRulesClient('', async () => Response.json({ data: [] })) }));
  assert.doesNotMatch(api, /Chế độ giả lập/);
});
