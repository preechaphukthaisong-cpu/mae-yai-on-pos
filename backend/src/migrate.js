import fs from 'node:fs/promises';
import path from 'node:path';
import pg from 'pg';

const { Pool } = pg;
if (!process.env.DATABASE_URL) throw new Error('DATABASE_URL is required');
const pool = new Pool({ connectionString: process.env.DATABASE_URL });
try {
  const directory = path.join(import.meta.dirname, '../migrations');
  const migrations = (await fs.readdir(directory))
    .filter(name => /^[0-9]{3}_[a-z0-9_]+\\.sql$/.test(name))
    .sort();
  if (migrations.length === 0) throw new Error('No SQL migrations were found');
  for (const name of migrations) {
    const sql = await fs.readFile(path.join(directory, name), 'utf8');
    await pool.query(sql);
    console.log('Database migration ' + name + ' applied');
  }
} finally {
  await pool.end();
}
