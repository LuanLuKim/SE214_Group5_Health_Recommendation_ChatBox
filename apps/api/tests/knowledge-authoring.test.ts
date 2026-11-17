import assert from 'node:assert/strict';
import test from 'node:test';
import express from 'express';
import { PGlite } from '@electric-sql/pglite';
import type { ApprovedTemplateProvider, PlanTemplateDraft } from '@health/contracts';
import type { Pool } from 'pg';
import request from 'supertest';
import { readSqlFiles } from '../scripts/database-files.js';
import { loadConfig } from '../src/config.js';
import { createApp } from '../src/app.js';
import { createArticleDraftService } from '../src/modules/knowledge/article-drafts.js';
import {
  AuthoringError,
  type ContentActor,
  type ContentAuthenticator,
} from '../src/modules/knowledge/authoring-access.js';
import type { KnowledgeDatabase } from '../src/modules/knowledge/repository.js';
import { createKnowledgeRouter } from '../src/modules/knowledge/router.js';
import {
  createApprovedTemplateProvider,
  createTemplateDraftService,
} from '../src/modules/knowledge/template-drafts.js';
import { errorHandler, requestContext } from '../src/shared/http.js';

const root = '/api/v1/knowledge';
const expert: ContentActor = {
  userId: '00000000-0000-4000-8000-000000000002',
  roles: ['CONTENT_EXPERT'],
};
const admin: ContentActor = { userId: '00000000-0000-4000-8000-000000000003', roles: ['ADMIN'] };
const user: ContentActor = { userId: '00000000-0000-4000-8000-000000000001', roles: ['USER'] };
const missingId = '00000000-0000-4000-8000-999999999999';
const articleInput = {
  title: '[TEST] Bài gửi duyệt',
  topic: 'Kiểm thử',
  content: 'Nội dung tổng hợp.',
  sourceName: 'Synthetic fixture',
  sourceUrl: 'https://example.invalid/article',
};
const templateInput = {
  title: '[TEST] Mẫu kế hoạch',
  goal: 'SLEEP',
  activities: [{ id: 'demo', title: 'Hoạt động giả lập' }],
  durationDays: 7,
  applicability: 'Đối tượng giả lập dành cho kiểm thử.',
  contraindications: ['Điều kiện thử nghiệm'],
  excludedAllergens: ['Tác nhân thử nghiệm'],
  advisory: 'Hỏi ý kiến nhân viên y tế trước khi áp dụng mẫu thực tế.',
  sourceName: 'Synthetic fixture',
  sourceUrl: 'https://example.invalid/template',
};
const resources = [
  {
    name: 'articles',
    table: 'knowledge.articles',
    input: articleInput,
    service: createArticleDraftService,
  },
  {
    name: 'templates',
    table: 'lifestyle.templates',
    input: templateInput,
    service: createTemplateDraftService,
  },
] as const;

async function prepareDatabase(db: PGlite) {
  for (const file of await readSqlFiles('migrations')) await db.exec(file.sql);
  for (const file of await readSqlFiles('seeds')) await db.exec(file.sql);
}

function appFor(
  db: KnowledgeDatabase | null,
  authenticate: ContentAuthenticator = async () => expert,
) {
  const app = express();
  app.use(requestContext);
  app.use(express.json());
  app.use(
    root,
    createKnowledgeRouter(loadConfig({ DATA_MODE: 'postgres' }), db as Pool | null, authenticate),
  );
  app.use(errorHandler);
  return app;
}

function authoringError(code: string) {
  return (error: unknown) =>
    error instanceof AuthoringError && error.status === 409 && error.code === code;
}

test('template authoring validates drafts and limits the M6 catalog to approved content', async (t) => {
  const db = new PGlite();
  t.after(() => db.close());
  await prepareDatabase(db);
  const app = appFor(db);

  await t.test('CRUD preserves activities, exclusions, source metadata and creator', async () => {
    const created = await request(app)
      .post(`${root}/templates`)
      .send({ ...templateInput, sourceUpdatedAt: '2026-10-29' });
    assert.equal(created.status, 201);
    const item = created.body.data as PlanTemplateDraft;
    assert.equal(item.reviewStatus, 'DRAFT');
    assert.equal(item.verifiedAt, null);
    assert.deepEqual(item.activities, templateInput.activities);
    assert.equal(item.sourceUpdatedAt, '2026-10-29T00:00:00.000Z');
    assert.equal(item.createdBy, expert.userId);
    assert.equal(item.revision, 1);
    assert.deepEqual(
      (await request(app).get(`${root}/authoring/templates/${item.id}`)).body.data,
      item,
    );
    const list = await request(app).get(`${root}/authoring/templates`);
    assert.equal(list.status, 200);
    assert.ok(list.body.data.some((row: PlanTemplateDraft) => row.id === item.id));
    const updated = await request(appFor(db, async () => admin))
      .patch(`${root}/templates/${item.id}`)
      .send({ title: '[TEST] Đã sửa mẫu' });
    assert.equal(updated.status, 200);
    assert.deepEqual(updated.body.data.excludedAllergens, templateInput.excludedAllergens);
    assert.deepEqual(updated.body.data.contraindications, templateInput.contraindications);
    assert.deepEqual(updated.body.data.activities, templateInput.activities);
    assert.equal(updated.body.data.durationDays, templateInput.durationDays);
    assert.equal(updated.body.data.advisory, templateInput.advisory);
    assert.equal(updated.body.data.createdBy, expert.userId);
    assert.equal(updated.body.data.updatedBy, admin.userId);
    assert.equal(updated.body.data.revision, 2);
    assert.equal((await request(app).delete(`${root}/templates/${item.id}`)).status, 204);
    assert.equal((await request(app).get(`${root}/authoring/templates/${item.id}`)).status, 404);
  });

  await t.test(
    'invalid activity, goal, duration, source and management fields never change storage',
    async () => {
      const created = await request(app).post(`${root}/templates`).send(templateInput);
      assert.equal(created.status, 201);
      const item = created.body.data as PlanTemplateDraft;
      const before = await db.query('SELECT count(*)::int AS count FROM lifestyle.templates');
      const cases = [
        { goal: 'UNKNOWN' },
        { activities: [] },
        { activities: [templateInput.activities[0], templateInput.activities[0]] },
        { activities: [{ id: '', title: 'Invalid' }] },
        { activities: [{ id: 'demo', title: '' }] },
        { durationDays: 0 },
        { durationDays: 366 },
        { durationDays: 1.5 },
        { applicability: '' },
        { advisory: '  ' },
        { excludedAllergens: [''] },
        { sourceName: '' },
        { sourceUrl: '' },
        { sourceUrl: 'file:///private' },
        { sourceUrl: 'https://user:secret@example.invalid/template' },
        { reviewStatus: 'APPROVED' },
        { verifiedAt: '2026-10-29T00:00:00Z' },
        { submittedBy: admin.userId },
        { revision: 50 },
      ];
      for (const fields of cases) {
        for (const response of [
          await request(app)
            .post(`${root}/templates`)
            .send({ ...templateInput, ...fields }),
          await request(app).patch(`${root}/templates/${item.id}`).send(fields),
        ]) {
          assert.equal(response.status, 400, JSON.stringify(fields));
          assert.equal(response.body.error.code, 'INVALID_INPUT');
        }
      }
      assert.equal((await request(app).patch(`${root}/templates/${item.id}`).send({})).status, 400);
      assert.deepEqual(
        (await db.query('SELECT count(*)::int AS count FROM lifestyle.templates')).rows,
        before.rows,
      );
      assert.deepEqual(
        (await request(app).get(`${root}/authoring/templates/${item.id}`)).body.data,
        item,
      );
    },
  );

  await t.test('a referenced draft cannot be deleted and its plan is preserved', async () => {
    const id = '00000000-0000-4000-8000-000000000050'; // Synthetic seed is referenced by a plan.
    const response = await request(app).delete(`${root}/templates/${id}`);
    assert.equal(response.status, 409);
    assert.equal(response.body.error.code, 'TEMPLATE_IN_USE');
    assert.equal((await request(app).get(`${root}/authoring/templates/${id}`)).status, 200);
    const plans = await db.query('SELECT template_id FROM lifestyle.plans WHERE template_id = $1', [
      id,
    ]);
    assert.equal(plans.rows.length, 1);
  });

  await t.test(
    'M6 only receives complete verified approved templates without editorial identities',
    async () => {
      const provider: ApprovedTemplateProvider = createApprovedTemplateProvider(db);
      assert.deepEqual(await provider.listApprovedTemplates(), []);
      const states = [
        'DRAFT',
        'PENDING_REVIEW',
        'RETURNED',
        'RETIRED',
        'APPROVED',
        'APPROVED',
        'APPROVED',
      ];
      const ids: string[] = [];
      for (const [index, status] of states.entries()) {
        const item = await createTemplateDraftService(db).create(expert, templateInput);
        ids.push(item.id);
        // UC-17 decisions are simulated only inside this isolated test database.
        await db.query(
          'UPDATE lifestyle.templates SET review_status = $2, verified_at = $3, source_url = $4 WHERE id = $1',
          [
            item.id,
            status,
            index === 4 ? null : '2026-10-29T00:00:00Z',
            index === 5 ? 'ftp://example.invalid/template' : templateInput.sourceUrl,
          ],
        );
      }
      const approved = await provider.listApprovedTemplates();
      assert.deepEqual(
        approved.map((item) => item.id),
        [ids[6]],
      );
      assert.equal(approved[0]!.reviewStatus, 'APPROVED');
      assert.equal(approved[0]!.verifiedAt, '2026-10-29T00:00:00.000Z');
      assert.deepEqual(approved[0]!.excludedAllergens, templateInput.excludedAllergens);
      for (const field of ['createdBy', 'updatedBy', 'submittedBy', 'revision']) {
        assert.equal(field in approved[0]!, false);
      }
      assert.deepEqual(await provider.getApprovedTemplate(ids[6]!), approved[0]);
      for (const id of ['not-a-uuid', missingId, ...ids.slice(0, 6)]) {
        assert.equal(await provider.getApprovedTemplate(id), null, id);
      }
    },
  );
});

test('article and template submissions preserve state and revision boundaries', async (t) => {
  const db = new PGlite();
  t.after(() => db.close());
  await prepareDatabase(db);
  const app = appFor(db);
  for (const resource of resources) {
    const service = resource.service(db);
    await t.test(
      `${resource.name}: drafts and returned content can be submitted, but never self-approved`,
      async () => {
        for (const actor of [expert, admin]) {
          const actorApp = appFor(db, async () => actor);
          const item = await service.create(actor, resource.input);
          const submitted = await request(actorApp)
            .post(`${root}/${resource.name}/${item.id}/submit`)
            .send({});
          assert.equal(submitted.status, 200);
          assert.equal(submitted.body.data.reviewStatus, 'PENDING_REVIEW');
          assert.equal(submitted.body.data.verifiedAt, null);
          assert.equal(submitted.body.data.submittedBy, actor.userId);
          assert.ok(Number.isFinite(Date.parse(submitted.body.data.submittedAt)));
          assert.equal(submitted.body.data.revision, item.revision + 1);
          await db.query(
            `UPDATE ${resource.table} SET review_status = 'RETURNED', verified_at = now() WHERE id = $1`,
            [item.id],
          );
          const returnedSubmission = await request(actorApp).post(
            `${root}/${resource.name}/${item.id}/submit`,
          );
          assert.equal(returnedSubmission.status, 200);
          assert.equal(returnedSubmission.body.data.reviewStatus, 'PENDING_REVIEW');
          assert.equal(returnedSubmission.body.data.verifiedAt, null);
          await db.query(`UPDATE ${resource.table} SET review_status = 'RETURNED' WHERE id = $1`, [
            item.id,
          ]);
          const fixed = await request(actorApp)
            .patch(`${root}/${resource.name}/${item.id}`)
            .send({ title: '[TEST] Fixed' });
          assert.equal(fixed.status, 200);
          assert.equal(fixed.body.data.reviewStatus, 'DRAFT');
          assert.equal(fixed.body.data.verifiedAt, null);
          assert.equal(fixed.body.data.submittedBy, null);
          assert.equal(fixed.body.data.submittedAt, null);
          const resubmitted = await request(actorApp).post(
            `${root}/${resource.name}/${item.id}/submit`,
          );
          assert.equal(resubmitted.status, 200);
          assert.equal(resubmitted.body.data.reviewStatus, 'PENDING_REVIEW');
          assert.equal(resubmitted.body.data.revision, fixed.body.data.revision + 1);
        }
        const item = await service.create(expert, resource.input);
        const injected = await request(app)
          .post(`${root}/${resource.name}/${item.id}/submit`)
          .send({ reviewStatus: 'APPROVED' });
        assert.equal(injected.status, 400);
        assert.equal((await service.get(expert, item.id)).reviewStatus, 'DRAFT');
      },
    );

    await t.test(
      `${resource.name}: pending, approved and retired records cannot be edited, deleted or submitted again`,
      async () => {
        for (const status of ['PENDING_REVIEW', 'APPROVED', 'RETIRED']) {
          const item = await service.create(expert, resource.input);
          await db.query(`UPDATE ${resource.table} SET review_status = $2 WHERE id = $1`, [
            item.id,
            status,
          ]);
          const before = await service.get(expert, item.id);
          const edited = await request(app)
            .patch(`${root}/${resource.name}/${item.id}`)
            .send({ title: 'Blocked' });
          const deleted = await request(app).delete(`${root}/${resource.name}/${item.id}`);
          const submitted = await request(app).post(`${root}/${resource.name}/${item.id}/submit`);
          for (const response of [edited, deleted]) {
            assert.equal(response.status, 409);
            assert.equal(response.body.error.code, 'CONTENT_NOT_EDITABLE');
          }
          assert.equal(submitted.status, 409);
          assert.equal(submitted.body.error.code, 'CONTENT_NOT_SUBMITTABLE');
          assert.deepEqual(await service.get(expert, item.id), before);
        }
        for (const id of ['not-a-uuid', missingId]) {
          for (const response of [
            await request(app).get(`${root}/authoring/${resource.name}/${id}`),
            await request(app).patch(`${root}/${resource.name}/${id}`).send({ title: 'Missing' }),
            await request(app).delete(`${root}/${resource.name}/${id}`),
            await request(app).post(`${root}/${resource.name}/${id}/submit`),
          ]) {
            assert.equal(response.status, 404);
            assert.equal(response.body.error.code, 'NOT_FOUND');
          }
        }
      },
    );

    await t.test(
      `${resource.name}: submission validates persisted sources and rejects a concurrent edit`,
      async () => {
        const invalid = await service.create(expert, resource.input);
        await db.query(
          `UPDATE ${resource.table} SET source_url = 'ftp://example.invalid/source' WHERE id = $1`,
          [invalid.id],
        );
        const rejected = await request(app).post(`${root}/${resource.name}/${invalid.id}/submit`);
        assert.equal(rejected.status, 400);
        assert.equal((await service.get(expert, invalid.id)).reviewStatus, 'DRAFT');

        const item = await service.create(expert, resource.input);
        let edited = false;
        const interleavedDatabase: KnowledgeDatabase = {
          async query(sql, values) {
            const result = await db.query<Record<string, unknown>>(sql, values);
            if (!edited && sql.trimStart().startsWith('SELECT') && values?.[0] === item.id) {
              edited = true;
              await service.update(admin, item.id, { title: '[TEST] Concurrent edit' });
            }
            return result;
          },
        };
        await assert.rejects(
          () => resource.service(interleavedDatabase).submit(expert, item.id),
          authoringError('CONTENT_CHANGED'),
        );
        assert.equal(edited, true);
        const after = await service.get(expert, item.id);
        assert.equal(after.title, '[TEST] Concurrent edit');
        assert.equal(after.reviewStatus, 'DRAFT');
        assert.equal(after.submittedAt, null);
        assert.equal(after.revision, item.revision + 1);
      },
    );
  }
});

test('authoring access checks precede validation and database access', async () => {
  let queries = 0;
  const db: KnowledgeDatabase = {
    async query() {
      queries++;
      throw new Error('Must not access the database');
    },
  };
  const defaultApp = createApp(loadConfig({}));
  for (const resource of resources) {
    const routes = [
      ['get', `/authoring/${resource.name}`],
      ['get', `/authoring/${resource.name}/${missingId}`],
      ['post', `/${resource.name}`],
      ['patch', `/${resource.name}/${missingId}`],
      ['delete', `/${resource.name}/${missingId}`],
      ['post', `/${resource.name}/${missingId}/submit`],
    ] as const;
    for (const [method, suffix] of routes) {
      const unresolved = await request(defaultApp)
        [method](root + suffix)
        .set('Authorization', 'Bearer fake')
        .set('X-Role', 'ADMIN')
        .set('X-User-ID', admin.userId);
      assert.equal(unresolved.status, 503);
      assert.equal(unresolved.body.error.code, 'AUTH_NOT_CONFIGURED');
      for (const [actor, status, code] of [
        [null, 401, 'UNAUTHENTICATED'],
        [user, 403, 'FORBIDDEN'],
        [{ ...expert, userId: 'invalid' }, 401, 'UNAUTHENTICATED'],
      ] as const) {
        const response = await request(appFor(db, async () => actor))
          [method](root + suffix)
          .set('X-Role', 'ADMIN')
          .set('X-User-ID', admin.userId);
        assert.equal(response.status, status, `${method} ${suffix}`);
        assert.equal(response.body.error.code, code);
        assert.equal(response.body.data, undefined);
      }
    }
    const direct = resource.service(db);
    for (const call of [
      () => direct.create(user, resource.input),
      () => direct.list(user),
      () => direct.get(user, missingId),
      () => direct.update(user, missingId, { title: 'Blocked' }),
      () => direct.delete(user, missingId),
      () => direct.submit(user, missingId),
    ]) {
      await assert.rejects(
        call,
        (error) => error instanceof AuthoringError && error.status === 403,
      );
    }
  }
  assert.equal(queries, 0);
  const noDatabase = await request(appFor(null)).post(`${root}/templates`).send(templateInput);
  assert.equal(noDatabase.status, 503);
  assert.equal(noDatabase.body.error.code, 'DATABASE_NOT_CONFIGURED');
});

test('persistence failures cannot become successful mutations or an empty M6 catalog', async () => {
  const failure = new Error('PRIVATE_DATABASE_SENTINEL');
  const db: KnowledgeDatabase = {
    async query() {
      throw failure;
    },
  };
  const app = appFor(db);
  for (const resource of resources) {
    const calls = [
      request(app).get(`${root}/authoring/${resource.name}`),
      request(app).get(`${root}/authoring/${resource.name}/${missingId}`),
      request(app).post(`${root}/${resource.name}`).send(resource.input),
      request(app).patch(`${root}/${resource.name}/${missingId}`).send({ title: 'Test' }),
      request(app).delete(`${root}/${resource.name}/${missingId}`),
      request(app).post(`${root}/${resource.name}/${missingId}/submit`),
    ];
    for (const call of calls) {
      const response = await call;
      assert.equal(response.status, 500);
      assert.equal(response.body.error.code, 'INTERNAL_ERROR');
      assert.equal(response.body.data, undefined);
      assert.equal(response.body.error.requestId, response.headers['x-request-id']);
      assert.doesNotMatch(JSON.stringify(response.body), /PRIVATE_DATABASE_SENTINEL|stack/);
    }
  }
  const provider = createApprovedTemplateProvider(db);
  await assert.rejects(
    () => provider.listApprovedTemplates(),
    (error) => error === failure,
  );
  await assert.rejects(
    () => provider.getApprovedTemplate(missingId),
    (error) => error === failure,
  );

  const racedForeignKey: KnowledgeDatabase = {
    async query() {
      throw Object.assign(new Error('Concurrent plan insert'), { code: '23503' });
    },
  };
  const result = await request(appFor(racedForeignKey)).delete(`${root}/templates/${missingId}`);
  assert.equal(result.status, 409);
  assert.equal(result.body.error.code, 'TEMPLATE_IN_USE');
});

test('legacy templates require missing metadata before editing or submitting', async (t) => {
  const db = new PGlite();
  t.after(() => db.close());
  const migrations = await readSqlFiles('migrations');
  for (const file of migrations.filter((file) => file.name < '010_')) await db.exec(file.sql);
  await db.query('INSERT INTO accounts.users (id,email,display_name) VALUES ($1,$2,$3)', [
    expert.userId,
    'expert@example.invalid',
    'Synthetic expert',
  ]);
  const id = '00000000-0000-4000-8000-000000000083';
  await db.query(
    `INSERT INTO lifestyle.templates (id,title,goal,activities,source_url)
    VALUES ($1,'[TEST] Legacy','SLEEP','[{"id":"demo","title":"Synthetic"}]','https://example.invalid/template')`,
    [id],
  );
  for (const file of migrations.filter((file) => file.name >= '010_')) await db.exec(file.sql);
  const app = appFor(db);
  const legacy = await request(app).get(`${root}/authoring/templates/${id}`);
  assert.equal(legacy.status, 200);
  assert.equal(legacy.body.data.durationDays, null);
  assert.equal(legacy.body.data.sourceName, '');
  assert.equal(legacy.body.data.verifiedAt, null);
  assert.equal((await request(app).post(`${root}/templates/${id}/submit`)).status, 400);
  assert.equal(
    (await request(app).patch(`${root}/templates/${id}`).send({ title: 'Partial' })).status,
    400,
  );
  const completed = await request(app).patch(`${root}/templates/${id}`).send(templateInput);
  assert.equal(completed.status, 200);
  assert.equal(completed.body.data.verifiedAt, null);
  const submitted = await request(app).post(`${root}/templates/${id}/submit`);
  assert.equal(submitted.status, 200);
  assert.equal(submitted.body.data.reviewStatus, 'PENDING_REVIEW');
  assert.deepEqual(await createApprovedTemplateProvider(db).listApprovedTemplates(), []);
});
