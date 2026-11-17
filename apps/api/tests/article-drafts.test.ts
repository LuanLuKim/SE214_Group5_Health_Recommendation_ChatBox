import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import express from 'express';
import { PGlite } from '@electric-sql/pglite';
import type { ArticleDraft } from '@health/contracts';
import type { Pool } from 'pg';
import request from 'supertest';
import { readSqlFiles } from '../scripts/database-files.js';
import { loadConfig } from '../src/config.js';
import type { ContentActor } from '../src/modules/knowledge/authoring-access.js';
import { createKnowledgeRouter } from '../src/modules/knowledge/router.js';
import { errorHandler, requestContext } from '../src/shared/http.js';

const root = '/api/v1/knowledge';
const expert: ContentActor = {
  userId: '00000000-0000-4000-8000-000000000002',
  roles: ['CONTENT_EXPERT'],
};
const admin: ContentActor = {
  userId: '00000000-0000-4000-8000-000000000003',
  roles: ['ADMIN'],
};
const input = {
  title: '[TEST] Bài nháp tổng hợp',
  topic: 'Kiểm thử',
  content: 'Nội dung tổng hợp, không có hướng dẫn y tế.',
  sourceName: 'Synthetic fixture',
  sourceUrl: 'https://example.invalid/article',
  sourceUpdatedAt: '2026-10-29',
  copyrightNotes: 'Dữ liệu kiểm thử.',
};

interface CrawlerRow {
  title: string;
  topic: string;
  content: string;
  source_name: string;
  source_url: string;
  verified_at: string | null;
  review_status: string;
  is_synthetic: boolean;
}

async function crawlerFixture(): Promise<CrawlerRow> {
  // Shared with a Python test that compares this fixture to the actual M7 output.
  return JSON.parse(
    await readFile(
      new URL('../../../tools/normalize/examples/db_row.example.json', import.meta.url),
      'utf8',
    ),
  ) as CrawlerRow;
}

async function insertCrawlerRow(db: PGlite, id: string, row: CrawlerRow) {
  assert.deepEqual(
    Object.keys(row).sort(),
    [
      'title',
      'topic',
      'content',
      'source_name',
      'source_url',
      'verified_at',
      'review_status',
      'is_synthetic',
    ].sort(),
  );
  await db.query(
    `INSERT INTO knowledge.articles
       (id,title,topic,content,source_name,source_url,verified_at,review_status,is_synthetic)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)`,
    [
      id,
      row.title,
      row.topic,
      row.content,
      row.source_name,
      row.source_url,
      row.verified_at,
      row.review_status,
      row.is_synthetic,
    ],
  );
}

function authoringApp(db: PGlite, actor: ContentActor = expert) {
  const app = express();
  app.use(requestContext);
  app.use(express.json());
  app.use(
    root,
    createKnowledgeRouter(
      loadConfig({ DATA_MODE: 'postgres' }),
      db as unknown as Pool,
      async () => actor,
    ),
  );
  app.use(errorHandler);
  return app;
}

test('article authoring API persists drafts and validates source data', async (t) => {
  const db = new PGlite();
  t.after(() => db.close());
  for (const file of await readSqlFiles('migrations')) await db.exec(file.sql);
  for (const file of await readSqlFiles('seeds')) await db.exec(file.sql);
  const app = authoringApp(db);

  await t.test('CRUD preserves content and records the actual creator and editor', async () => {
    const created = await request(app)
      .post(`${root}/articles`)
      .send({ ...input, title: `  ${input.title}  ` });
    assert.equal(created.status, 201);
    const article = created.body.data as ArticleDraft;
    assert.equal(article.title, input.title);
    assert.equal(article.content, input.content);
    assert.equal(article.reviewStatus, 'DRAFT');
    assert.equal(article.verifiedAt, null);
    assert.equal(article.sourceUpdatedAt, '2026-10-29T00:00:00.000Z');
    assert.equal(article.copyrightNotes, input.copyrightNotes);
    assert.equal(article.createdBy, expert.userId);
    assert.equal(article.updatedBy, expert.userId);
    assert.equal(article.revision, 1);
    assert.equal(article.submittedAt, null);
    assert.equal(article.submittedBy, null);
    assert.ok(Number.isFinite(Date.parse(article.createdAt)));

    const read = await request(app).get(`${root}/authoring/articles/${article.id}`);
    assert.equal(read.status, 200);
    assert.deepEqual(read.body.data, article);
    const list = await request(app).get(`${root}/authoring/articles`);
    assert.equal(list.status, 200);
    assert.ok(list.body.data.some((item: ArticleDraft) => item.id === article.id));

    const edited = await request(authoringApp(db, admin))
      .patch(`${root}/articles/${article.id}`)
      .send({ title: '[TEST] Đã sửa', sourceUpdatedAt: null });
    assert.equal(edited.status, 200);
    assert.equal(edited.body.data.title, '[TEST] Đã sửa');
    assert.equal(edited.body.data.content, input.content);
    assert.equal(edited.body.data.sourceUpdatedAt, null);
    assert.equal(edited.body.data.createdBy, expert.userId);
    assert.equal(edited.body.data.updatedBy, admin.userId);
    assert.equal(edited.body.data.verifiedAt, null);
    assert.equal(edited.body.data.revision, 2);

    const deleted = await request(app).delete(`${root}/articles/${article.id}`);
    assert.equal(deleted.status, 204);
    assert.equal(deleted.text, '');
    assert.equal((await request(app).get(`${root}/authoring/articles/${article.id}`)).status, 404);
  });

  await t.test(
    'invalid input is rejected without creating or changing stored content',
    async () => {
      const created = await request(app).post(`${root}/articles`).send(input);
      assert.equal(created.status, 201);
      const article = created.body.data as ArticleDraft;
      const before = await db.query<{ count: number }>(
        'SELECT count(*)::int AS count FROM knowledge.articles',
      );
      const invalidFields = [
        { title: '' },
        { topic: '   ' },
        { content: '' },
        { sourceName: '' },
        { sourceName: null },
        { sourceUrl: '' },
        { sourceUrl: 'not-a-url' },
        { sourceUrl: 'ftp://example.invalid/source' },
        { sourceUrl: 'https://user:secret@example.invalid/source' },
        { sourceUpdatedAt: '2026-02-30' },
        { sourceUpdatedAt: 'yesterday' },
        { title: 'x'.repeat(301) },
        { sourceName: 'x'.repeat(201) },
        { reviewStatus: 'APPROVED' },
        { verifiedAt: '2026-10-29T00:00:00Z' },
        { createdBy: admin.userId },
        { revision: 99 },
        { isSynthetic: true },
      ];
      for (const fields of invalidFields) {
        for (const response of [
          await request(app)
            .post(`${root}/articles`)
            .send({ ...input, ...fields }),
          await request(app).patch(`${root}/articles/${article.id}`).send(fields),
        ]) {
          assert.equal(response.status, 400, JSON.stringify(fields));
          assert.equal(response.body.error.code, 'INVALID_INPUT');
          assert.equal(response.body.data, undefined);
          assert.equal(response.body.error.requestId, response.headers['x-request-id']);
        }
      }
      const { sourceUrl: _sourceUrl, ...missingSource } = input;
      assert.equal((await request(app).post(`${root}/articles`).send(missingSource)).status, 400);
      assert.equal(
        (await request(app).patch(`${root}/articles/${article.id}`).send({})).status,
        400,
      );
      const after = await db.query<{ count: number }>(
        'SELECT count(*)::int AS count FROM knowledge.articles',
      );
      assert.deepEqual(after.rows, before.rows);
      assert.deepEqual(
        (await request(app).get(`${root}/authoring/articles/${article.id}`)).body.data,
        article,
      );
    },
  );

  await t.test('invalid and unknown IDs return 404 for read, update and delete', async () => {
    for (const id of ['not-a-uuid', '00000000-0000-4000-8000-999999999999']) {
      for (const response of [
        await request(app).get(`${root}/authoring/articles/${id}`),
        await request(app).patch(`${root}/articles/${id}`).send({ title: 'Changed' }),
        await request(app).delete(`${root}/articles/${id}`),
      ]) {
        assert.equal(response.status, 404);
        assert.equal(response.body.error.code, 'NOT_FOUND');
        assert.equal(response.body.data, undefined);
      }
    }
  });

  await t.test('public search only exposes approved and verified articles', async () => {
    const states = ['DRAFT', 'PENDING_REVIEW', 'RETURNED', 'RETIRED', 'APPROVED', 'APPROVED'];
    const ids: string[] = [];
    for (const [index, status] of states.entries()) {
      const created = await request(app)
        .post(`${root}/articles`)
        .send({ ...input, title: `[TEST] Visibility ${index}` });
      assert.equal(created.status, 201);
      ids.push(created.body.data.id);
      // Simulate existing editorial states; publication is not an authoring action.
      await db.query(
        'UPDATE knowledge.articles SET review_status = $2, verified_at = $3 WHERE id = $1',
        [created.body.data.id, status, index === 4 ? null : '2026-10-29T00:00:00Z'],
      );
    }
    const publicList = await request(app).get(`${root}/articles`).query({ q: 'Visibility' });
    assert.equal(publicList.status, 200);
    assert.deepEqual(
      publicList.body.data.map((item: { id: string }) => item.id),
      [ids[5]],
    );
    assert.equal(publicList.body.data[0].content, undefined);
    assert.equal(publicList.body.data[0].createdBy, undefined);
    assert.equal(publicList.body.data[0].source.name, input.sourceName);
    const privateList = await request(app).get(`${root}/authoring/articles`);
    assert.ok(
      ids.every((id) => privateList.body.data.some((item: ArticleDraft) => item.id === id)),
    );
  });

  await t.test('M7 db_row inserts remain valid and the API mapping stays unverified', async () => {
    const row = await crawlerFixture();
    assert.equal(row.review_status, 'DRAFT');
    assert.equal(row.verified_at, null);
    const id = '00000000-0000-4000-8000-000000000080';
    await insertCrawlerRow(db, id, row);
    const imported = await request(app).get(`${root}/authoring/articles/${id}`);
    assert.equal(imported.status, 200);
    assert.equal(imported.body.data.content, row.content);
    assert.equal(imported.body.data.sourceName, row.source_name);
    assert.equal(imported.body.data.sourceUrl, row.source_url);
    assert.equal(imported.body.data.sourceUpdatedAt, null);
    assert.equal(imported.body.data.verifiedAt, null);
    assert.equal(imported.body.data.createdBy, null);
    assert.equal(imported.body.data.revision, 1);

    assert.equal((await request(app).post(`${root}/articles`).send(row)).status, 400);
    const mapped = await request(app).post(`${root}/articles`).send({
      title: row.title,
      topic: row.topic,
      content: row.content,
      sourceName: row.source_name,
      sourceUrl: row.source_url,
    });
    assert.equal(mapped.status, 201);
    assert.equal(mapped.body.data.content, row.content);
    assert.equal(mapped.body.data.reviewStatus, 'DRAFT');
    assert.equal(mapped.body.data.verifiedAt, null);
    const publicList = await request(app).get(`${root}/articles`).query({ q: row.title });
    assert.equal(publicList.status, 200);
    assert.deepEqual(publicList.body.data, []);
  });
});

test('article metadata migrations preserve legacy content and review state', async (t) => {
  const db = new PGlite();
  t.after(() => db.close());
  const migrations = await readSqlFiles('migrations');
  for (const file of migrations.filter((file) => file.name < '009_')) await db.exec(file.sql);
  const row = await crawlerFixture();
  const draftId = '00000000-0000-4000-8000-000000000081';
  const approvedId = '00000000-0000-4000-8000-000000000082';
  await insertCrawlerRow(db, draftId, row);
  await insertCrawlerRow(db, approvedId, {
    ...row,
    review_status: 'APPROVED',
    verified_at: '2026-10-29T00:00:00Z',
  });
  for (const file of migrations.filter((file) => file.name >= '009_')) await db.exec(file.sql);
  const app = authoringApp(db);
  for (const [id, status] of [
    [draftId, 'DRAFT'],
    [approvedId, 'APPROVED'],
  ]) {
    const response = await request(app).get(`${root}/authoring/articles/${id}`);
    assert.equal(response.status, 200);
    assert.equal(response.body.data.content, row.content);
    assert.equal(response.body.data.reviewStatus, status);
    assert.equal(response.body.data.revision, 1);
    assert.equal(response.body.data.submittedAt, null);
    assert.equal(response.body.data.createdBy, null);
    assert.equal(response.body.data.copyrightNotes, '');
    assert.equal(
      response.body.data.verifiedAt,
      status === 'DRAFT' ? null : '2026-10-29T00:00:00.000Z',
    );
  }
});
