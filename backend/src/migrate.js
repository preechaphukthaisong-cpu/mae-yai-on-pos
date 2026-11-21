import fs from 'node:fs/promises';
import path from 'node:path';
import pg from 'pg';

const { Pool } = pg;
if (!process.env.DATABASE_URL) throw new Error('DATABASE_URL is required');
const pool = new Pool({ connectionString: process.env.DATABASE_URL });
try {
  const migration = await fs.readFile(path.join(import.meta.dirname, '../migrations/001_sync_core.sql'), 'utf8');
  await pool.query(migration);
  console.log('Database migration 001_sync_core.sql applied');
} finally {
  await pool.end();
}
