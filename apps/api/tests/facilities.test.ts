import assert from 'node:assert/strict';
import test from 'node:test';
import express from 'express';
import { PGlite } from '@electric-sql/pglite';
import type { FacilityLookup, FacilitySummary } from '@health/contracts';
import type { Pool } from 'pg';
import request from 'supertest';
import { readSqlFiles } from '../scripts/database-files.js';
import { loadConfig } from '../src/config.js';
import { createKnowledgeRouter } from '../src/modules/knowledge/router.js';
import { createKnowledgeService } from '../src/modules/knowledge/service.js';
import { syntheticFacilities } from '../src/modules/knowledge/synthetic.js';
import { errorHandler, requestContext } from '../src/shared/http.js';

const path = '/api/v1/knowledge/facilities';
const verifiedAt = '2026-10-29T00:00:00.000Z';
const idFor = (number: number) => `00000000-0000-4000-8000-${String(number).padStart(12, '0')}`;

function facility(
  number: number,
  name: string,
  province: string,
  specialties: string[],
): FacilitySummary {
  return {
    id: idFor(number),
    name: `[TEST] ${name}`,
    province,
    specialties,
    address: 'Địa chỉ giả lập, không có thật',
    officialUrl: 'https://example.invalid/facility',
    source: {
      id: idFor(number),
      name: 'Synthetic fixture',
      url: 'https://example.invalid/source',
      verifiedAt,
    },
  };
}

function searchFixtures(): FacilitySummary[] {
  const visible = [
    facility(1, 'Cơ sở Alpha', 'Tỉnh A', ['Nội tổng quát', 'Tim mạch']),
    facility(2, 'Cơ sở Beta', 'Tỉnh A', ['Nội tổng quát']),
    facility(3, 'Cơ sở Gamma', 'Tỉnh B', ['Tim mạch']),
    facility(4, 'Trung tâm Delta', 'Tỉnh B', ['Nội tổng quát', 'Tim mạch']),
    facility(5, 'Cơ sở Alpha', 'Tỉnh A', ['Nội tổng quát']),
    facility(6, 'Cơ sở 100%_Demo', 'Tỉnh C', []),
  ];
  const hidden = [
    { name: '', url: 'https://example.invalid/source', verifiedAt },
    { name: '   ', url: 'https://example.invalid/source', verifiedAt },
    { name: 'Synthetic fixture', url: '', verifiedAt },
    { name: 'Synthetic fixture', url: '   ', verifiedAt },
    { name: 'Synthetic fixture', url: 'https://example.invalid/source', verifiedAt: '' },
  ].map((source, index) => {
    const item = facility(90 + index, 'Cơ sở Alpha', 'Tỉnh A', ['Tim mạch']);
    return { ...item, source: { id: item.id, ...source } };
  });
  return [...visible, ...hidden];
}

async function insertFacility(db: PGlite, item: FacilitySummary) {
  await db.query(
    `INSERT INTO knowledge.facilities
       (id, name, specialties, province, address, official_url,
        source_name, source_url, verified_at, is_synthetic)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, true)`,
    [
      item.id,
      item.name,
      item.specialties,
      item.province,
      item.address,
      item.officialUrl,
      item.source.name,
      item.source.url,
      item.source.verifiedAt || null,
    ],
  );
}

for (const mode of ['mock', 'postgres'] as const) {
  test(`${mode}: public facility search and details`, async (t) => {
    const fixtures = searchFixtures();
    let db: PGlite | null = null;
    if (mode === 'postgres') {
      const database = new PGlite();
      db = database;
      t.after(() => database.close());
      for (const file of await readSqlFiles('migrations')) await database.exec(file.sql);
      for (const item of fixtures) await insertFacility(database, item);
    } else {
      // Replace public fixtures only within this test and restore them even on failure.
      const original = [...syntheticFacilities];
      t.after(() => {
        syntheticFacilities.splice(0, syntheticFacilities.length, ...original);
      });
      syntheticFacilities.splice(0, syntheticFacilities.length, ...fixtures);
    }

    const app = express();
    app.use(requestContext);
    app.use(
      '/api/v1/knowledge',
      createKnowledgeRouter(loadConfig({ DATA_MODE: mode }), db as unknown as Pool | null),
    );
    app.use(errorHandler);

    await t.test(
      'detail returns the verified directory record with all source fields',
      async () => {
        const response = await request(app).get(`${path}/${idFor(1)}`);
        assert.equal(response.status, 200);
        assert.deepEqual(response.body, {
          data: fixtures[0],
          meta: { status: mode === 'mock' ? 'SYNTHETIC_DATA' : 'DATABASE_RESULTS' },
        });
      },
    );

    await t.test(
      'invalid, unknown and unverified IDs return the same safe 404 response',
      async () => {
        const ids = ['not-a-uuid', idFor(999), ...[90, 91, 92, 93, 94].map(idFor)];
        for (const id of ids) {
          const response = await request(app).get(`${path}/${id}`);
          assert.equal(response.status, 404, id);
          assert.ok(response.headers['x-request-id']);
          assert.deepEqual(response.body, {
            error: {
              code: 'NOT_FOUND',
              message: 'Không tìm thấy cơ sở y tế.',
              requestId: response.headers['x-request-id'],
            },
          });
        }
      },
    );

    await t.test('FacilityLookup returns the same verified records as the public API', async () => {
      const lookup: FacilityLookup = createKnowledgeService(
        loadConfig({ DATA_MODE: mode }),
        db as unknown as Pool | null,
      );
      const filters = { province: 'tỉnh a', specialty: 'tim mạch' };
      const response = await request(app).get(path).query(filters);
      assert.equal(response.status, 200);
      const results = await lookup.listFacilities(filters);
      assert.deepEqual(results, [fixtures[0]]);
      assert.deepEqual(results, response.body.data);
      assert.deepEqual(await lookup.getFacility(idFor(1)), results[0]);
      assert.equal((await lookup.listFacilities()).length, 6);
      assert.deepEqual(await lookup.listFacilities({ specialty: 'Không tồn tại' }), []);
      for (const id of ['not-a-uuid', idFor(999), ...[90, 91, 92, 93, 94].map(idFor)]) {
        assert.equal(await lookup.getFacility(id), null, id);
      }
    });

    await t.test('filters independently and combines all supplied filters with AND', async () => {
      const cases: Array<{ query: Record<string, string>; ids: number[] }> = [
        { query: {}, ids: [1, 2, 3, 4, 5, 6] },
        { query: { q: '  ALPHA  ' }, ids: [1, 5] },
        { query: { province: '  TỈNH A  ' }, ids: [1, 2, 5] },
        { query: { specialty: '  TIM MẠCH  ' }, ids: [1, 3, 4] },
        { query: { q: 'CƠ SỞ', province: 'tỉnh a' }, ids: [1, 2, 5] },
        { query: { q: 'CƠ SỞ', specialty: 'tim mạch' }, ids: [1, 3] },
        { query: { province: 'tỉnh a', specialty: 'tim mạch' }, ids: [1] },
        { query: { q: 'alpha', province: 'tỉnh a', specialty: 'tim mạch' }, ids: [1] },
        { query: { q: 'alpha', province: 'Tỉnh B' }, ids: [] },
        { query: { province: 'Tỉnh' }, ids: [] },
        { query: { specialty: 'Tim' }, ids: [] },
        { query: { q: 'không tồn tại' }, ids: [] },
        { query: { q: '  ' }, ids: [1, 2, 3, 4, 5, 6] },
        { query: { q: '%_' }, ids: [6] },
        { query: { q: "' OR 1=1 --" }, ids: [] },
      ];
      for (const { query, ids } of cases) {
        const response = await request(app).get(path).query(query);
        assert.equal(response.status, 200, JSON.stringify(query));
        const results = response.body.data as FacilitySummary[];
        assert.deepEqual(
          results.map((item) => item.id).sort(),
          ids.map(idFor).sort(),
          JSON.stringify(query),
        );
        assert.deepEqual(response.body.meta, {
          total: ids.length,
          status: mode === 'mock' ? 'SYNTHETIC_DATA' : 'DATABASE_RESULTS',
        });
        for (const item of results) {
          assert.equal(item.source.name, 'Synthetic fixture');
          assert.equal(item.source.url, 'https://example.invalid/source');
          assert.equal(item.source.verifiedAt, verifiedAt);
        }
      }
    });

    await t.test('uses ID to order facilities with the same name', async () => {
      const response = await request(app).get(path).query({ q: 'alpha' });
      assert.equal(response.status, 200);
      assert.deepEqual(
        (response.body.data as FacilitySummary[]).map((item) => item.id),
        [idFor(1), idFor(5)],
      );
    });

    await t.test(
      'rejects invalid or repeated parameters with the standard error response',
      async () => {
        const queries = [
          { q: 'x'.repeat(201) },
          { province: 'x'.repeat(201) },
          { specialty: 'x'.repeat(201) },
          { province: '' },
          { province: '   ' },
          { specialty: '' },
          { specialty: '   ' },
          { q: ['a', 'b'] },
          { province: ['a', 'b'] },
          { specialty: ['a', 'b'] },
        ];
        for (const query of queries) {
          const response = await request(app).get(path).query(query);
          assert.equal(response.status, 400, JSON.stringify(query));
          assert.equal(response.body.error.code, 'INVALID_QUERY');
          assert.equal(response.body.data, undefined);
          assert.equal(response.body.error.requestId, response.headers['x-request-id']);
          assert.ok(response.body.error.requestId);
        }
        for (const field of ['q', 'province', 'specialty']) {
          const response = await request(app)
            .get(path)
            .query({ [field]: 'x'.repeat(200) });
          assert.equal(response.status, 200, field);
          assert.deepEqual(response.body.data, []);
        }
      },
    );

    await t.test('returns at most 20 records in stable name order', async () => {
      const records = Array.from({ length: 23 }, (_, index) =>
        facility(100 + index, `Limit ${String(index).padStart(2, '0')}`, 'Tỉnh thử', []),
      );
      if (db) {
        for (const item of [...records].reverse()) await insertFacility(db, item);
      } else {
        syntheticFacilities.push(...[...records].reverse());
      }
      const response = await request(app).get(path).query({ q: '[TEST] Limit' });
      assert.equal(response.status, 200);
      assert.deepEqual(
        (response.body.data as FacilitySummary[]).map((item) => item.id),
        records.slice(0, 20).map((item) => item.id),
      );
      assert.equal(response.body.meta.total, 20);
    });
  });
}

test('database failures reject lookup calls and return safe HTTP errors', async () => {
  const failure = new Error('PRIVATE_DATABASE_DETAIL');
  const pool = {
    async query() {
      throw failure;
    },
  } as unknown as Pool;
  const config = loadConfig({ DATA_MODE: 'postgres' });
  const lookup: FacilityLookup = createKnowledgeService(config, pool);
  await assert.rejects(
    () => lookup.listFacilities(),
    (error) => error === failure,
  );
  await assert.rejects(
    () => lookup.getFacility(idFor(1)),
    (error) => error === failure,
  );

  const app = express();
  app.use(requestContext);
  app.use('/api/v1/knowledge', createKnowledgeRouter(config, pool));
  app.use(errorHandler);
  for (const url of [path, `${path}/${idFor(1)}`]) {
    const response = await request(app).get(url);
    assert.equal(response.status, 500);
    assert.equal(response.body.error.code, 'INTERNAL_ERROR');
    assert.equal(response.body.data, undefined);
    assert.ok(response.body.error.requestId);
    assert.equal(response.body.error.requestId, response.headers['x-request-id']);
    assert.doesNotMatch(JSON.stringify(response.body), /PRIVATE_DATABASE_DETAIL|stack/);
  }
});
