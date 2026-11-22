import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import pg from 'pg';

const { Pool } = pg;
test('PostgreSQL enforces store event uniqueness and per-store ordering', {
  skip: !process.env.DATABASE_URL
}, async () => {
  const pool = new Pool({ connectionString: process.env.DATABASE_URL });
  const storeId = crypto.randomUUID();
  const deviceId = crypto.randomUUID();
  const eventId = crypto.randomUUID();
  try {
    await pool.query(
      'INSERT INTO stores(id, owner_google_sub, owner_email) VALUES($1,$2,$3)',
      [storeId, 'integration-' + storeId, 'integration@example.invalid']
    );
    await pool.query(
      'INSERT INTO devices(id, store_id, google_sub, label) VALUES($1,$2,$3,$4)',
      [deviceId, storeId, 'integration-' + storeId, 'CI device']
    );
    await pool.query(
      `INSERT INTO sync_events(store_id, seq, event_id, device_id, entity_type, entity_id,
        operation, base_version, entity_version, payload, payload_sha256)
       VALUES($1,1,$2,$3,'product','SKU-1','upsert',0,1,'{"algorithm":"A256GCM","encryptionVersion":1,"aadVersion":1,"keyEpoch":1,"nonce":"AAAAAAAAAAAAAAAA","ciphertext":"AAAAAAAAAAAAAAAAAAAAAA"}'::jsonb,$4)`,
      [storeId, eventId, deviceId, '0'.repeat(64)]
    );
    await assert.rejects(
      pool.query(
        `INSERT INTO sync_events(store_id, seq, event_id, device_id, entity_type, entity_id,
          operation, base_version, entity_version, payload, payload_sha256)
         VALUES($1,2,$2,$3,'product','SKU-PLAINTEXT','upsert',0,1,'{"sku":"must-not-persist"}'::jsonb,$4)`,
        [storeId, crypto.randomUUID(), deviceId, '0'.repeat(64)]
      )
    );
    await assert.rejects(
      pool.query(
        `INSERT INTO sync_conflicts(id, store_id, device_id, event_id, entity_type, entity_id,
          expected_version, actual_version, submitted_payload)
         VALUES($1,$2,$3,$4,'product','SKU-PLAINTEXT',0,1,'{"sku":"must-not-persist"}'::jsonb)`,
        [crypto.randomUUID(), storeId, deviceId, crypto.randomUUID()]
      )
    );
    await assert.rejects(
      pool.query(
        `INSERT INTO sync_events(store_id, seq, event_id, device_id, entity_type, entity_id,
          operation, base_version, entity_version, payload, payload_sha256)
         VALUES($1,2,$2,$3,'product','SKU-1','upsert',1,2,'{"algorithm":"A256GCM","encryptionVersion":1,"aadVersion":1,"keyEpoch":1,"nonce":"AAAAAAAAAAAAAAAA","ciphertext":"AAAAAAAAAAAAAAAAAAAAAA"}'::jsonb,$4)`,
        [storeId, eventId, deviceId, '0'.repeat(64)]
      )
    );
    await pool.query(
      'UPDATE stores SET last_event_seq = 1 WHERE id = $1',
      [storeId]
    );
    const rows = await pool.query(
      'SELECT seq, event_id FROM sync_events WHERE store_id = $1 ORDER BY seq',
      [storeId]
    );
    assert.equal(rows.rowCount, 1);
    assert.equal(String(rows.rows[0].seq), '1');
    assert.equal(rows.rows[0].event_id, eventId);
  } finally {
    await pool.query('DELETE FROM stores WHERE id = $1', [storeId]).catch(() => {});
    await pool.end();
  }
});
