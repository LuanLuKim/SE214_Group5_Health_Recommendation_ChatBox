import { readdir, readFile } from 'node:fs/promises';

export async function readSqlFiles(folder: 'migrations' | 'seeds') {
  const directory = new URL(`../../../database/${folder}/`, import.meta.url);
  const names = (await readdir(directory)).filter((name) => name.endsWith('.sql')).sort();
  return Promise.all(
    names.map(async (name) => ({ name, sql: await readFile(new URL(name, directory), 'utf8') })),
  );
}
