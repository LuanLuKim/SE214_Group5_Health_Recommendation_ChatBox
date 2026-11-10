import test from 'node:test';
import assert from 'node:assert/strict';
import { PGlite } from '@electric-sql/pglite';
import { ruleDraftInputSchema } from '@health/contracts';
import { readSqlFiles } from '../scripts/database-files.js';
import { createRuleDraftService } from '../src/modules/safety/service.js';

const draft = {
  name: '[TEST] Quy tắc thử nghiệm',
  conditions: { all: ['dau_hieu_thu_nghiem'], any: [] },
  requiredFields: ['thoi_diem_bat_dau'],
  careLevel: 'INSUFFICIENT_INFO',
  specialtyIds: [],
  sourceName: 'Nguồn thử nghiệm',
  sourceUrl: 'https://example.invalid/rule',
} as const;

async function withDatabase(run: (db: PGlite) => Promise<void>) {
  const db = new PGlite();
  try {
    for (const file of await readSqlFiles('migrations')) await db.exec(file.sql);
    await run(db);
  } finally {
    await db.close();
  }
}

test('draft input rejects empty conditions and non-http sources', () => {
  assert.equal(ruleDraftInputSchema.safeParse(draft).success, true);
  assert.equal(
    ruleDraftInputSchema.safeParse({ ...draft, conditions: { all: [], any: [] } }).success,
    false,
  );
  assert.equal(
    ruleDraftInputSchema.safeParse({ ...draft, sourceUrl: 'file:///local' }).success,
    false,
  );
});

test('draft source URL cannot contain embedded credentials', () => {
  assert.equal(
    ruleDraftInputSchema.safeParse({ ...draft, sourceUrl: 'https://user:secret@example.com/rule' })
      .success,
    false,
  );
});

test('draft can be created, listed, read and edited without becoming active', async () => {
  await withDatabase(async (db) => {
    const service = createRuleDraftService(db);
    const created = await service.create(draft);
    assert.equal(created.reviewStatus, 'DRAFT');
    assert.equal(created.isSynthetic, false);
    assert.equal(created.name, draft.name);
    assert.deepEqual(created.conditions, draft.conditions);
    assert.deepEqual(created.requiredFields, ['thoi_diem_bat_dau']);
    assert.equal((await service.list()).length, 1);
    assert.equal((await service.get(created.id))?.sourceName, 'Nguồn thử nghiệm');

    const edited = await service.update(created.id, { ...draft, name: '[TEST] Đã sửa' });
    assert.equal(edited?.name, '[TEST] Đã sửa');
    assert.equal((await service.get(created.id))?.name, '[TEST] Đã sửa');
    assert.equal((await service.get(created.id))?.reviewStatus, 'DRAFT');
  });
});

test('approved rule cannot be edited through draft service', async () => {
  await withDatabase(async (db) => {
    const service = createRuleDraftService(db);
    const created = await service.create(draft);
    await db.query("UPDATE safety.rules SET review_status = 'APPROVED' WHERE id = $1", [
      created.id,
    ]);
    assert.equal(await service.update(created.id, { ...draft, name: 'changed' }), null);
    assert.equal((await service.get(created.id))?.name, draft.name);
  });
});

test('invalid draft does not write to the database', async () => {
  await withDatabase(async (db) => {
    const service = createRuleDraftService(db);
    assert.throws(() => service.create({ ...draft, name: '' }));
    assert.deepEqual(await service.list(), []);
  });
});
