import pg from 'pg';
import { loadConfig } from '../src/config.js';
import { readSqlFiles } from './database-files.js';

const config = loadConfig();
if (!config.DATABASE_URL) throw new Error('Set DATABASE_URL before running migrations.');
const client = new pg.Client({
  connectionString: config.DATABASE_URL,
  connectionTimeoutMillis: 5000,
});
try {
  await client.connect();
  await client.query('BEGIN');
  await client.query('SELECT pg_advisory_xact_lock(214005)');
  await client.query(
    'CREATE TABLE IF NOT EXISTS public.schema_migrations (name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())',
  );
  for (const file of await readSqlFiles('migrations')) {
    const existing = await client.query(
      'SELECT name FROM public.schema_migrations WHERE name = $1',
      [file.name],
    );
    if (existing.rowCount) continue;
    await client.query(file.sql);
    await client.query('INSERT INTO public.schema_migrations(name) VALUES ($1)', [file.name]);
    console.info(`Applied ${file.name}`);
  }
  await client.query('COMMIT');
} catch {
  await client.query('ROLLBACK').catch(() => {});
  console.error(
    'Migration failed. Check PostgreSQL, DATABASE_URL and migration order. No connection details are logged.',
  );
  process.exitCode = 1;
} finally {
  await client.end();
}
