import assert from 'node:assert/strict';
import test from 'node:test';
import express from 'express';
import { PGlite } from '@electric-sql/pglite';
import type { Pool } from 'pg';
import request from 'supertest';
import { readSqlFiles } from '../scripts/database-files.js';
import { loadConfig } from '../src/config.js';
import { createKnowledgeRouter } from '../src/modules/knowledge/router.js';
import { requestContext } from '../src/shared/http.js';

const path = '/api/v1/knowledge/articles';
const approvedId = '00000000-0000-4000-8000-000000000010';
const draftId = '00000000-0000-4000-8000-000000000011';

function mockApp() {
  const app = express();
  app.use(requestContext);
  app.use('/api/v1/knowledge', createKnowledgeRouter(loadConfig({ DATA_MODE: 'mock' }), null));
  return app;
}

test('mock article search combines keyword, topic and pagination metadata', async () => {
  const app = mockApp();
  const matching = await request(app)
    .get(path)
    .query({ q: 'GIẤC', topic: 'giấc ngủ', limit: '1', offset: '0' });
  assert.equal(matching.status, 200);
  assert.deepEqual(
    matching.body.data.map((article: { id: string }) => article.id),
    [approvedId],
  );
  assert.deepEqual(matching.body.meta, {
    total: 1,
    limit: 1,
    offset: 0,
    totalPages: 1,
    status: 'SYNTHETIC_DATA',
  });
  assert.equal(matching.body.data[0].content, undefined);
  assert.ok(matching.body.data[0].source.name);
  assert.ok(matching.body.data[0].updatedAt);

  const pastEnd = await request(app).get(path).query({ limit: '1', offset: '1' });
  assert.equal(pastEnd.status, 200);
  assert.deepEqual(pastEnd.body.data, []);
  assert.equal(pastEnd.body.meta.total, 1);
  assert.equal(pastEnd.body.meta.totalPages, 1);

  const noResults = await request(app).get(path).query({ topic: 'Vận động' });
  assert.equal(noResults.status, 200);
  assert.deepEqual(noResults.body.data, []);
  assert.equal(noResults.body.meta.total, 0);
  assert.equal(noResults.body.meta.totalPages, 0);
  assert.equal(noResults.body.meta.limit, 20);
  assert.equal(noResults.body.meta.offset, 0);
});

test('mock article search rejects invalid query parameters', async () => {
  const app = mockApp();
  for (const params of [
    { limit: '0' },
    { limit: '21' },
    { limit: '1.5' },
    { limit: '-1' },
    { offset: '-1' },
    { offset: '1.5' },
    { offset: '9007199254740992' },
    { topic: '   ' },
    { q: 'x'.repeat(201) },
  ]) {
    const response = await request(app).get(path).query(params);
    assert.equal(response.status, 400, JSON.stringify(params));
    assert.equal(response.body.error.code, 'INVALID_QUERY');
  }
  const repeated = await request(app).get(`${path}?q=a&q=b`);
  assert.equal(repeated.status, 400);
});

test('PostgreSQL search counts only approved verified articles and keeps pages stable', async () => {
  const db = new PGlite();
  try {
    for (const file of await readSqlFiles('migrations')) await db.exec(file.sql);
    for (const file of await readSqlFiles('seeds')) await db.exec(file.sql);

    async function addArticle(
      suffix: string,
      title: string,
      topic: string,
      status: string,
      verified: boolean,
    ) {
      await db.query(
        `INSERT INTO knowledge.articles
           (id, title, topic, content, source_name, source_url, verified_at, review_status, is_synthetic)
         VALUES ($1, $2, $3, 'Nội dung thử nghiệm', 'Synthetic fixture',
                 'https://example.invalid/article', $4, $5, true)`,
        [
          `00000000-0000-4000-8000-0000000000${suffix}`,
          title,
          topic,
          verified ? '2026-10-29T00:00:00Z' : null,
          status,
        ],
      );
    }
    await addArticle('12', '[TEST] Chuyên đề A', 'Giấc ngủ', 'APPROVED', true);
    await addArticle('13', '[TEST] Chuyên đề B', 'Giấc ngủ', 'APPROVED', true);
    await addArticle('14', '[TEST] Chuyên đề C', 'Giấc ngủ', 'APPROVED', true);
    await addArticle('15', '[TEST] Vận động', 'Vận động', 'APPROVED', true);
    await addArticle('16', '[TEST] Chuyên đề chờ duyệt', 'Giấc ngủ', 'PENDING_REVIEW', true);
    await addArticle('17', '[TEST] Chuyên đề ngừng dùng', 'Giấc ngủ', 'RETIRED', true);
    await addArticle('18', '[TEST] Chuyên đề chưa xác minh', 'Giấc ngủ', 'APPROVED', false);

    const app = express();
    app.use(requestContext);
    app.use(
      '/api/v1/knowledge',
      createKnowledgeRouter(loadConfig({ DATA_MODE: 'postgres' }), db as unknown as Pool),
    );

    const first = await request(app)
      .get(path)
      .query({ q: 'CHUYÊN ĐỀ', topic: 'giấc ngủ', limit: '2', offset: '0' });
    assert.equal(first.status, 200);
    assert.deepEqual(
      first.body.data.map((article: { title: string }) => article.title),
      ['[TEST] Chuyên đề A', '[TEST] Chuyên đề B'],
    );
    assert.deepEqual(first.body.meta, {
      total: 3,
      limit: 2,
      offset: 0,
      totalPages: 2,
      status: 'DATABASE_RESULTS',
    });

    const second = await request(app)
      .get(path)
      .query({ q: 'CHUYÊN ĐỀ', topic: 'giấc ngủ', limit: '2', offset: '2' });
    assert.deepEqual(
      second.body.data.map((article: { title: string }) => article.title),
      ['[TEST] Chuyên đề C'],
    );
    assert.equal(second.body.meta.total, 3);
    assert.equal(second.body.meta.totalPages, 2);

    const pastEnd = await request(app)
      .get(path)
      .query({ q: 'CHUYÊN ĐỀ', topic: 'giấc ngủ', limit: '2', offset: '3' });
    assert.deepEqual(pastEnd.body.data, []);
    assert.equal(pastEnd.body.meta.total, 3);

    const all = await request(app).get(path);
    assert.equal(all.body.meta.total, 5);
    assert.equal(all.body.data.length, 5);
    assert.ok(all.body.data.some((article: { id: string }) => article.id === approvedId));
    assert.ok(all.body.data.every((article: { id: string }) => article.id !== draftId));
    assert.ok(
      all.body.data.every(
        (article: { source: { verifiedAt: string } }) => article.source.verifiedAt,
      ),
    );

    const noResults = await request(app).get(path).query({ q: '%', topic: 'Giấc ngủ' });
    assert.deepEqual(noResults.body.data, []);
    assert.equal(noResults.body.meta.total, 0);
    assert.equal(noResults.body.meta.totalPages, 0);
  } finally {
    await db.close();
  }
});

test('mock article detail exposes content and source only for a public article', async () => {
  const app = mockApp();
  const article = await request(app).get(`${path}/${approvedId}`);
  assert.equal(article.status, 200);
  assert.deepEqual(article.body, {
    data: {
      id: approvedId,
      title: '[TEST] Kiểm thử bài viết về giấc ngủ',
      topic: 'Giấc ngủ',
      content: 'Nội dung giả lập để kiểm tra giao diện. Không có hướng dẫn y tế.',
      source: {
        id: approvedId,
        name: 'Synthetic fixture',
        url: 'https://example.invalid/articles/sleep',
        verifiedAt: '2026-10-29T00:00:00.000Z',
      },
      updatedAt: '2026-10-29T00:00:00.000Z',
    },
    meta: { status: 'SYNTHETIC_DATA' },
  });

  for (const id of [draftId, '00000000-0000-4000-8000-000000000099', 'not-a-uuid']) {
    const hidden = await request(app).get(`${path}/${id}`);
    assert.equal(hidden.status, 404, id);
    assert.equal(hidden.body.error.code, 'NOT_FOUND');
    assert.equal(hidden.body.data, undefined);
    assert.ok(hidden.body.error.requestId);
  }

  const list = await request(app).get(path);
  assert.equal(list.status, 200);
  assert.equal(list.body.data[0].content, undefined);
});

test('PostgreSQL article detail hides drafts, returned, retired and unverified content', async () => {
  const db = new PGlite();
  try {
    for (const file of await readSqlFiles('migrations')) await db.exec(file.sql);
    for (const file of await readSqlFiles('seeds')) await db.exec(file.sql);
    await db.query('UPDATE knowledge.articles SET updated_at = $1 WHERE id = $2', [
      '2026-10-30T12:00:00Z',
      approvedId,
    ]);

    const hiddenArticles = [
      ['00000000-0000-4000-8000-000000000020', 'PENDING_REVIEW', '2026-10-29T00:00:00Z'],
      ['00000000-0000-4000-8000-000000000021', 'RETURNED', '2026-10-29T00:00:00Z'],
      ['00000000-0000-4000-8000-000000000022', 'RETIRED', '2026-10-29T00:00:00Z'],
      ['00000000-0000-4000-8000-000000000023', 'APPROVED', null],
    ] as const;
    for (const [id, status, verifiedAt] of hiddenArticles) {
      await db.query(
        `INSERT INTO knowledge.articles
           (id, title, topic, content, source_name, source_url, verified_at, review_status)
         VALUES ($1, '[TEST] Bài riêng tư', 'Kiểm thử', 'PRIVATE_CONTENT_SENTINEL',
                 'Synthetic fixture', 'https://example.invalid/hidden', $2, $3)`,
        [id, verifiedAt, status],
      );
    }

    const app = express();
    app.use(requestContext);
    app.use(
      '/api/v1/knowledge',
      createKnowledgeRouter(loadConfig({ DATA_MODE: 'postgres' }), db as unknown as Pool),
    );

    const approved = await request(app).get(`${path}/${approvedId}`);
    assert.equal(approved.status, 200);
    assert.equal(approved.body.data.id, approvedId);
    assert.equal(
      approved.body.data.content,
      'Nội dung giả lập để kiểm tra giao diện. Không có hướng dẫn y tế.',
    );
    assert.deepEqual(approved.body.data.source, {
      id: approvedId,
      name: 'Synthetic fixture',
      url: 'https://example.invalid/articles/sleep',
      verifiedAt: '2026-10-29T00:00:00.000Z',
    });
    assert.equal(approved.body.data.updatedAt, '2026-10-30T12:00:00.000Z');
    assert.equal(approved.body.meta.status, 'DATABASE_RESULTS');

    for (const id of [
      draftId,
      ...hiddenArticles.map(([articleId]) => articleId),
      '00000000-0000-4000-8000-000000000099',
      'not-a-uuid',
    ]) {
      const hidden = await request(app).get(`${path}/${id}`);
      assert.equal(hidden.status, 404, id);
      assert.equal(hidden.body.error.code, 'NOT_FOUND');
      assert.equal(hidden.body.data, undefined);
      assert.doesNotMatch(JSON.stringify(hidden.body), /PRIVATE_CONTENT_SENTINEL/);
    }

    const list = await request(app).get(path);
    assert.equal(list.status, 200);
    assert.equal(list.body.meta.total, 1);
    assert.equal(list.body.data[0].content, undefined);
  } finally {
    await db.close();
  }
});
