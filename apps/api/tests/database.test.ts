import test from 'node:test';
import assert from 'node:assert/strict';
import { PGlite } from '@electric-sql/pglite';
import { readSqlFiles } from '../scripts/database-files.js';

test('all six module migrations execute, synthetic seed is idempotent, constraints hold', async () => {
  const db = new PGlite();
  try {
    const migrations = await readSqlFiles('migrations');
    assert.equal(migrations.length, 6);
    for (const file of migrations) await db.exec(file.sql);
    for (let run = 0; run < 2; run++)
      for (const file of await readSqlFiles('seeds')) await db.exec(file.sql);
    const users = await db.query<{ count: number }>(
      'SELECT count(*)::int AS count FROM accounts.users',
    );
    assert.equal(users.rows[0]!.count, 3);
    const approved = await db.query<{ count: number }>(
      "SELECT count(*)::int AS count FROM knowledge.articles WHERE review_status = 'APPROVED'",
    );
    assert.equal(approved.rows[0]!.count, 1);
    const rules = await db.query<{ count: number }>(
      "SELECT count(*)::int AS count FROM safety.rules WHERE review_status = 'APPROVED'",
    );
    assert.equal(rules.rows[0]!.count, 0);
    await assert.rejects(
      db.exec(
        "INSERT INTO accounts.user_roles VALUES ('00000000-0000-4000-8000-999999999999','USER')",
      ),
    );
    await assert.rejects(
      db.exec(
        "INSERT INTO health.daily_metrics VALUES ('00000000-0000-4000-8000-000000000001','2026-10-26',65,30)",
      ),
    );
    await assert.rejects(
      db.exec(
        "INSERT INTO chat.feedback VALUES ('00000000-0000-4000-8000-000000000099','OTHER','', 'unpaired',NULL,now())",
      ),
    );
  } finally {
    await db.close();
  }
});
