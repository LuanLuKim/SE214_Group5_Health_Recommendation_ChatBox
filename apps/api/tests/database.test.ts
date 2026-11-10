import test from 'node:test';
import assert from 'node:assert/strict';
import { PGlite } from '@electric-sql/pglite';
import { readSqlFiles } from '../scripts/database-files.js';

test('all module migrations execute, synthetic seed is idempotent, constraints hold', async () => {
  const db = new PGlite();
  try {
    const migrations = await readSqlFiles('migrations');
    assert.equal(migrations.length, 8);
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

    // UC-14: seeded reminder gets every day by default; invalid schedules and duplicate sends are rejected.
    const reminder = await db.query<{ days: number[]; enabled: boolean }>(
      "SELECT days_of_week AS days, enabled FROM lifestyle.reminders WHERE id = '00000000-0000-4000-8000-000000000052'",
    );
    assert.deepEqual(reminder.rows[0], { days: [1, 2, 3, 4, 5, 6, 7], enabled: false });
    const reminderColumns =
      "INSERT INTO lifestyle.reminders (id, user_id, content, local_time, channel, days_of_week) VALUES ('00000000-0000-4000-8000-000000000098','00000000-0000-4000-8000-000000000001'";
    await assert.rejects(db.exec(`${reminderColumns}, '   ', '07:00', 'EMAIL', '{1}')`));
    await assert.rejects(db.exec(`${reminderColumns}, 'Uống nước', '07:00', 'EMAIL', '{}')`));
    await assert.rejects(db.exec(`${reminderColumns}, 'Uống nước', '07:00', 'EMAIL', '{0,8}')`));
    await assert.rejects(db.exec(`${reminderColumns}, 'Uống nước', '07:00', 'SMS', '{1}')`));

    const delivery = (id: string) =>
      `INSERT INTO lifestyle.reminder_deliveries (id, reminder_id, scheduled_for, channel) VALUES ('${id}','00000000-0000-4000-8000-000000000052','2026-11-17T13:00:00Z','EMAIL')`;
    await db.exec(delivery('00000000-0000-4000-8000-000000000060'));
    await assert.rejects(db.exec(delivery('00000000-0000-4000-8000-000000000061')));
    await assert.rejects(
      db.exec(
        "UPDATE lifestyle.reminder_deliveries SET status = 'SENT' WHERE id = '00000000-0000-4000-8000-000000000060'",
      ),
    );
    await db.exec(
      "UPDATE lifestyle.reminder_deliveries SET status = 'SENT', sent_at = now(), attempt_count = 1 WHERE id = '00000000-0000-4000-8000-000000000060'",
    );
    await db.exec(
      "DELETE FROM lifestyle.reminders WHERE id = '00000000-0000-4000-8000-000000000052'",
    );
    const orphaned = await db.query<{ count: number }>(
      'SELECT count(*)::int AS count FROM lifestyle.reminder_deliveries',
    );
    assert.equal(orphaned.rows[0]!.count, 0);
  } finally {
    await db.close();
  }
});
