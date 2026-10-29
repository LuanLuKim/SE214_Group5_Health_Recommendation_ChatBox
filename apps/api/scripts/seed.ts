import pg from 'pg';
import { loadConfig } from '../src/config.js';
import { readSqlFiles } from './database-files.js';

const config = loadConfig();
if (!config.DATABASE_URL) throw new Error('Set DATABASE_URL before seeding.');
if (process.env.NODE_ENV === 'production')
  throw new Error('Synthetic seeding is disabled in production.');
const client = new pg.Client({
  connectionString: config.DATABASE_URL,
  connectionTimeoutMillis: 5000,
});
try {
  await client.connect();
  await client.query('BEGIN');
  for (const file of await readSqlFiles('seeds')) {
    await client.query(file.sql);
    console.info(`Seeded ${file.name}`);
  }
  await client.query('COMMIT');
} catch {
  await client.query('ROLLBACK').catch(() => {});
  console.error('Seed failed. Run migrations first and check local database configuration.');
  process.exitCode = 1;
} finally {
  await client.end();
}
