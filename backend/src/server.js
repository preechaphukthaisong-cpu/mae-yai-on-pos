import http from 'node:http';
import crypto from 'node:crypto';
import pg from 'pg';
import { verifyGoogleIdentity, encryptRefreshToken, sha256Hex, canonicalJson, isUuid, sameImmutableEvent, validateEncryptedEventPayload } from './security.js';
import { mirrorSnapshot } from './drive-mirror.js';

const { Pool } = pg;
const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  max: 12,
  idleTimeoutMillis: 30_000,
  connectionTimeoutMillis: 5_000,
  ssl: process.env.PGSSL === 'true' ? { rejectUnauthorized: true } : undefined
});
const PORT = Number(process.env.PORT || 8080);
const MAX_BACKUP_BYTES = Math.max(1, Number(process.env.MAX_BACKUP_BYTES || 134217728));
const MAX_JSON_BYTES = 2 * 1024 * 1024;

class HttpError extends Error {
  constructor(statusCode, message, details) {
    super(message);
    this.statusCode = statusCode;
    this.details = details;
  }
}

function send(res, status, data) {
  const body = Buffer.from(JSON.stringify(data));
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': body.length,
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff'
  });
  res.end(body);
}

async function readBody(req, limit) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > limit) throw new HttpError(413, 'Request body exceeds the configured size limit');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks, size);
}

async function readJson(req) {
  const raw = await readBody(req, MAX_JSON_BYTES);
  try {
    return raw.length ? JSON.parse(raw.toString('utf8')) : {};
  } catch {
    throw new HttpError(400, 'Malformed JSON request');
  }
}

async function identityFromRequest(req) {
  const header = req.headers.authorization || '';
  if (!header.startsWith('Bearer ')) throw new HttpError(401, 'Google sign-in is required');
  try {
    return await verifyGoogleIdentity(header.slice(7));
  } catch (error) {
    throw new HttpError(error.statusCode || 401, 'Google identity token is invalid or expired');
  }
}

async function requireDevice(req) {
  const identity = await identityFromRequest(req);
  const deviceId = req.headers['x-device-id'];
  if (!isUuid(deviceId)) throw new HttpError(401, 'A registered device ID is required');
  const result = await pool.query(
    `SELECT s.id AS store_id, s.last_event_seq, d.id AS device_id
       FROM stores s JOIN devices d ON d.store_id = s.id
      WHERE s.owner_google_sub = $1 AND d.id = $2
        AND d.google_sub = $1 AND d.revoked_at IS NULL`,
    [identity.sub, deviceId]
  );
  if (!result.rowCount) throw new HttpError(403, 'This device is not registered for the signed-in store');
  await pool.query('UPDATE devices SET last_seen_at = now() WHERE id = $1', [deviceId]);
  return { ...identity, ...result.rows[0] };
}

function validateEvent(event) {
  if (!event || !isUuid(event.eventId) || !['upsert', 'delete'].includes(event.operation)
      || typeof event.entityType !== 'string' || !/^[a-z][a-z0-9_]{0,63}$/.test(event.entityType)
      || typeof event.entityId !== 'string' || event.entityId.length < 1 || event.entityId.length > 160
      || !Number.isSafeInteger(event.baseVersion) || event.baseVersion < 0
      || event.payload === undefined || event.payload === null
      || typeof event.payload !== 'object') {
    throw new HttpError(400, 'Invalid sync event envelope');
  }
  if (!validateEncryptedEventPayload(event.payload)) {
    throw new HttpError(400, 'Sync payload must be a supported AES-256-GCM encrypted envelope');
  }
  const canonical = canonicalJson(event.payload);
  if (Buffer.byteLength(canonical, 'utf8') > 256 * 1024) {
    throw new HttpError(413, 'A sync event payload exceeds 256 KiB');
  }
  const digest = sha256Hex(canonical);
  if (typeof event.payloadSha256 !== 'string' || event.payloadSha256.toLowerCase() !== digest) {
    throw new HttpError(400, 'Sync event payload checksum mismatch');
  }
  return digest;
}

async function createStore(identity) {
  const id = crypto.randomUUID();
  const result = await pool.query(
    `INSERT INTO stores(id, owner_google_sub, owner_email)
     VALUES ($1, $2, $3)
     ON CONFLICT(owner_google_sub) DO UPDATE SET owner_email = EXCLUDED.owner_email
     RETURNING id, owner_email, last_event_seq, created_at`,
    [id, identity.sub, identity.email]
  );
  return result.rows[0];
}

async function handlePush(req, res, context) {
  const body = await readJson(req);
  if (!Array.isArray(body.events) || body.events.length < 1 || body.events.length > 100) {
    throw new HttpError(400, 'Push requires between 1 and 100 events');
  }
  const client = await pool.connect();
  const acknowledgements = [];
  const conflicts = [];
  try {
    await client.query('BEGIN');
    const locked = await client.query('SELECT last_event_seq FROM stores WHERE id = $1 FOR UPDATE', [context.store_id]);
    if (!locked.rowCount) throw new HttpError(404, 'Store not found');
    let nextSeq = BigInt(locked.rows[0].last_event_seq);
    for (const event of body.events) {
      const digest = validateEvent(event);
      const duplicate = await client.query(
        `SELECT seq, payload_sha256, entity_type, entity_id, operation, base_version
           FROM sync_events WHERE store_id = $1 AND event_id = $2`,
        [context.store_id, event.eventId]
      );
      if (duplicate.rowCount) {
        const prior = duplicate.rows[0];
        if (!sameImmutableEvent(prior, event, digest)) {
          throw new HttpError(409, 'An event ID was reused with different immutable content', {
            eventId: event.eventId
          });
        }
        acknowledgements.push({ eventId: event.eventId, seq: String(prior.seq), duplicate: true });
        continue;
      }
      const versionResult = await client.query(
        'SELECT version FROM entity_versions WHERE store_id = $1 AND entity_type = $2 AND entity_id = $3 FOR UPDATE',
        [context.store_id, event.entityType, event.entityId]
      );
      const actualVersion = versionResult.rowCount ? BigInt(versionResult.rows[0].version) : 0n;
      if (actualVersion !== BigInt(event.baseVersion)) {
        const conflictId = crypto.randomUUID();
        await client.query(
          `INSERT INTO sync_conflicts(id, store_id, device_id, event_id, entity_type, entity_id,
             expected_version, actual_version, submitted_payload)
           VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9::jsonb)
           ON CONFLICT (store_id, event_id) DO NOTHING`,
          [conflictId, context.store_id, context.device_id, event.eventId, event.entityType,
            event.entityId, String(event.baseVersion), String(actualVersion), JSON.stringify(event.payload)]
        );
        conflicts.push({
          conflictId, eventId: event.eventId, entityType: event.entityType,
          entityId: event.entityId, expectedVersion: String(event.baseVersion),
          actualVersion: String(actualVersion)
        });
        continue;
      }
      nextSeq += 1n;
      const entityVersion = actualVersion + 1n;
      await client.query(
        `INSERT INTO sync_events(store_id, seq, event_id, device_id, entity_type, entity_id,
           operation, base_version, entity_version, payload, payload_sha256)
         VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10::jsonb,$11)`,
        [context.store_id, String(nextSeq), event.eventId, context.device_id, event.entityType,
          event.entityId, event.operation, String(actualVersion), String(entityVersion),
          JSON.stringify(event.payload), digest]
      );
      await client.query(
        `INSERT INTO entity_versions(store_id, entity_type, entity_id, version, last_event_seq)
         VALUES($1,$2,$3,$4,$5)
         ON CONFLICT(store_id, entity_type, entity_id)
         DO UPDATE SET version = EXCLUDED.version, last_event_seq = EXCLUDED.last_event_seq`,
        [context.store_id, event.entityType, event.entityId, String(entityVersion), String(nextSeq)]
      );
      await client.query(
        `UPDATE sync_conflicts SET resolved_at = now(), resolution_event_seq = $4
          WHERE store_id = $1 AND entity_type = $2 AND entity_id = $3 AND resolved_at IS NULL`,
        [context.store_id, event.entityType, event.entityId, String(nextSeq)]
      );
      acknowledgements.push({ eventId: event.eventId, seq: String(nextSeq), entityVersion: String(entityVersion) });
    }
    await client.query('UPDATE stores SET last_event_seq = $2 WHERE id = $1', [context.store_id, String(nextSeq)]);
    await client.query('COMMIT');
    send(res, 200, { acknowledgements, conflicts, lastSequence: String(nextSeq) });
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {});
    throw error;
  } finally {
    client.release();
  }
}

async function handlePull(req, res, context, url) {
  const rawCursor = url.searchParams.get('after') || '0';
  if (!/^[0-9]{1,19}$/.test(rawCursor)) throw new HttpError(400, 'Invalid sync cursor');
  const after = BigInt(rawCursor);
  const limit = Math.max(1, Math.min(500, Number(url.searchParams.get('limit') || 200)));
  const result = await pool.query(
    `SELECT seq, event_id, device_id, entity_type, entity_id, operation,
            base_version, entity_version, payload, payload_sha256, created_at
       FROM sync_events WHERE store_id = $1 AND seq > $2
       ORDER BY seq ASC LIMIT $3`,
    [context.store_id, String(after), limit]
  );
  const events = result.rows.map(row => ({
    seq: String(row.seq), eventId: row.event_id, deviceId: row.device_id,
    entityType: row.entity_type, entityId: row.entity_id, operation: row.operation,
    baseVersion: String(row.base_version), entityVersion: String(row.entity_version),
    payload: row.payload, payloadSha256: row.payload_sha256, createdAt: row.created_at
  }));
  const nextCursor = events.length ? events[events.length - 1].seq : String(after);
  send(res, 200, { events, nextCursor, hasMore: events.length === limit });
}

async function handleBackupUpload(req, res, context) {
  const throughSeq = req.headers['x-through-seq'];
  const claimedSha = req.headers['x-backup-sha256'];
  if (!/^[0-9]{1,19}$/.test(throughSeq || '') || !/^[a-f0-9]{64}$/i.test(claimedSha || '')) {
    throw new HttpError(400, 'Backup sequence and SHA-256 headers are required');
  }
  if (!String(req.headers['content-type'] || '').startsWith('application/octet-stream')) {
    throw new HttpError(415, 'Encrypted backup must use application/octet-stream');
  }
  const blob = await readBody(req, MAX_BACKUP_BYTES);
  if (!blob.length || sha256Hex(blob) !== claimedSha.toLowerCase()) {
    throw new HttpError(400, 'Encrypted backup is empty or checksum verification failed');
  }
  const client = await pool.connect();
  let snapshotId;
  try {
    await client.query('BEGIN');
    const store = await client.query('SELECT last_event_seq FROM stores WHERE id = $1 FOR UPDATE', [context.store_id]);
    if (!store.rowCount) throw new HttpError(404, 'Store not found');
    if (BigInt(throughSeq) !== BigInt(store.rows[0].last_event_seq)) {
      throw new HttpError(409, 'A full snapshot must include the latest authoritative event sequence', {
        expectedSequence: String(store.rows[0].last_event_seq), submittedSequence: throughSeq
      });
    }
    const inserted = await client.query(
      `INSERT INTO backup_snapshots(id, store_id, created_by_device, through_seq, sha256, byte_length, encrypted_blob)
       VALUES($1,$2,$3,$4,$5,$6,$7)
       ON CONFLICT(store_id, through_seq, sha256) DO UPDATE
         SET created_by_device = EXCLUDED.created_by_device
       RETURNING id`,
      [crypto.randomUUID(), context.store_id, context.device_id, throughSeq, claimedSha.toLowerCase(), blob.length, blob]
    );
    snapshotId = inserted.rows[0].id;
    await client.query('COMMIT');
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {});
    throw error;
  } finally {
    client.release();
  }
  setImmediate(() => {
    mirrorSnapshot(pool, context.store_id, snapshotId)
      .catch(error => console.error('Drive mirror failed for snapshot', snapshotId, error.message));
  });
  send(res, 202, { snapshotId, throughSeq, sha256: claimedSha.toLowerCase(), stored: true, driveMirror: 'queued' });
}

async function handle(req, res) {
  const url = new URL(req.url, 'http://localhost');
  if (req.method === 'GET' && url.pathname === '/health') {
    await pool.query('SELECT 1');
    return send(res, 200, { ok: true });
  }
  const identity = await identityFromRequest(req);
  if (req.method === 'POST' && url.pathname === '/v1/stores') {
    const store = await createStore(identity);
    return send(res, 201, { storeId: store.id, email: store.owner_email, lastSequence: String(store.last_event_seq) });
  }
  if (req.method === 'GET' && url.pathname === '/v1/stores/me') {
    const result = await pool.query(
      'SELECT id, owner_email, last_event_seq, created_at FROM stores WHERE owner_google_sub = $1',
      [identity.sub]
    );
    if (!result.rowCount) throw new HttpError(404, 'No store is registered for this Google account');
    const store = result.rows[0];
    return send(res, 200, { storeId: store.id, email: store.owner_email, lastSequence: String(store.last_event_seq), createdAt: store.created_at });
  }
  if (req.method === 'POST' && url.pathname === '/v1/devices') {
    const storeResult = await pool.query('SELECT id FROM stores WHERE owner_google_sub = $1', [identity.sub]);
    if (!storeResult.rowCount) throw new HttpError(404, 'Create the store before registering a device');
    const body = await readJson(req);
    if (!isUuid(body.deviceId) || typeof body.label !== 'string' || !body.label.trim() || body.label.length > 80) {
      throw new HttpError(400, 'A UUID deviceId and a label of 1–80 characters are required');
    }
    const result = await pool.query(
      `INSERT INTO devices(id, store_id, google_sub, label)
       VALUES($1,$2,$3,$4)
       ON CONFLICT(id) DO UPDATE SET label = EXCLUDED.label, last_seen_at = now()
       WHERE devices.store_id = EXCLUDED.store_id AND devices.google_sub = EXCLUDED.google_sub
         AND devices.revoked_at IS NULL
       RETURNING id, label, registered_at`,
      [body.deviceId, storeResult.rows[0].id, identity.sub, body.label.trim()]
    );
    if (!result.rowCount) throw new HttpError(409, 'Device ID is already assigned or has been revoked');
    return send(res, 201, { deviceId: result.rows[0].id, label: result.rows[0].label, registeredAt: result.rows[0].registered_at });
  }

  const context = await requireDevice(req);
  if (req.method === 'POST' && url.pathname === '/v1/sync/push') return handlePush(req, res, context);
  if (req.method === 'GET' && url.pathname === '/v1/sync/pull') return handlePull(req, res, context, url);
  if (req.method === 'POST' && url.pathname === '/v1/backups') return handleBackupUpload(req, res, context);

  const backupDownloadMatch = url.pathname.match(new RegExp('^/v1/backups/([0-9a-f-]{36})$', 'i'));
  if (req.method === 'GET' && backupDownloadMatch) {
    const result = await pool.query(
      'SELECT id, through_seq, sha256, encrypted_blob FROM backup_snapshots WHERE store_id = $1 AND id = $2',
      [context.store_id, backupDownloadMatch[1]]
    );
    if (!result.rowCount) throw new HttpError(404, 'Backup snapshot not found');
    const backup = result.rows[0];
    res.writeHead(200, {
      'content-type': 'application/octet-stream',
      'content-length': backup.encrypted_blob.length,
      'content-disposition': 'attachment; filename="maeyai-pos-' + backup.id + '.myobak"',
      'x-backup-sha256': backup.sha256,
      'x-through-seq': String(backup.through_seq),
      'cache-control': 'no-store',
      'x-content-type-options': 'nosniff'
    });
    res.end(backup.encrypted_blob);
    return;
  }
  if (req.method === 'GET' && url.pathname === '/v1/backups') {
    const result = await pool.query(
      `SELECT id, through_seq, sha256, byte_length, drive_file_id, drive_uploaded_at, created_at
         FROM backup_snapshots WHERE store_id = $1
        ORDER BY through_seq DESC, created_at DESC LIMIT 20`,
      [context.store_id]
    );
    return send(res, 200, { backups: result.rows.map(row => ({ ...row, through_seq: String(row.through_seq), byte_length: String(row.byte_length) })) });
  }
  if (req.method === 'POST' && url.pathname === '/v1/drive/connect') {
    const body = await readJson(req);
    if (typeof body.serverAuthCode !== 'string' || body.serverAuthCode.length < 20
        || body.serverAuthCode.length > 4096) {
      throw new HttpError(400, 'A valid one-time Google server authorization code is required');
    }
    const tokenForm = new URLSearchParams({
      code: body.serverAuthCode,
      client_id: process.env.GOOGLE_OAUTH_CLIENT_ID || '',
      client_secret: process.env.GOOGLE_DRIVE_OAUTH_CLIENT_SECRET || '',
      grant_type: 'authorization_code',
      redirect_uri: ''
    });
    const tokenResponse = await fetch('https://oauth2.googleapis.com/token', {
      method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: tokenForm
    });
    if (!tokenResponse.ok) throw new HttpError(400, 'Google server authorization code exchange failed');
    const token = await tokenResponse.json();
    if (!token.refresh_token || !token.access_token
        || !String(token.scope || '').includes('drive.appdata')) {
      throw new HttpError(403, 'Google did not grant offline Drive appData access; reconnect with consent');
    }
    const profileResponse = await fetch('https://www.googleapis.com/oauth2/v3/userinfo', {
      headers: { authorization: 'Bearer ' + token.access_token }
    });
    const profile = profileResponse.ok ? await profileResponse.json() : {};
    if (profile.sub !== identity.sub) {
      throw new HttpError(403, 'Connected Google Drive account does not match the signed-in store owner');
    }
    const credentials = encryptRefreshToken(token.refresh_token);
    await pool.query(
      `INSERT INTO drive_connections(store_id, refresh_token_ciphertext, refresh_token_iv,
         refresh_token_tag, connected_by_google_sub)
       VALUES($1,$2,$3,$4,$5)
       ON CONFLICT(store_id) DO UPDATE SET refresh_token_ciphertext = EXCLUDED.refresh_token_ciphertext,
         refresh_token_iv = EXCLUDED.refresh_token_iv, refresh_token_tag = EXCLUDED.refresh_token_tag,
         connected_by_google_sub = EXCLUDED.connected_by_google_sub, updated_at = now()`,
      [context.store_id, credentials.ciphertext, credentials.iv, credentials.tag, identity.sub]
    );
    setImmediate(() => void sweepPendingDriveMirrors());
    return send(res, 200, { connected: true, account: profile.email });
  }
  if (req.method === 'DELETE' && url.pathname.startsWith('/v1/devices/')) {
    const targetId = url.pathname.split('/').pop();
    if (!isUuid(targetId)) throw new HttpError(400, 'Invalid device ID');
    if (targetId === context.device_id) throw new HttpError(409, 'A device cannot revoke its own active session');
    const result = await pool.query(
      'UPDATE devices SET revoked_at = now() WHERE store_id = $1 AND id = $2 AND revoked_at IS NULL',
      [context.store_id, targetId]
    );
    return send(res, result.rowCount ? 200 : 404, { revoked: result.rowCount === 1 });
  }
  if (req.method === 'GET' && url.pathname === '/v1/conflicts') {
    const result = await pool.query(
      `SELECT id, event_id, entity_type, entity_id, expected_version, actual_version, created_at
         FROM sync_conflicts WHERE store_id = $1 AND resolved_at IS NULL ORDER BY created_at ASC LIMIT 200`,
      [context.store_id]
    );
    return send(res, 200, { conflicts: result.rows.map(row => ({ ...row, expectedVersion: String(row.expected_version), actualVersion: String(row.actual_version) })) });
  }
  throw new HttpError(404, 'Route not found');
}

let mirrorSweepRunning = false;
async function sweepPendingDriveMirrors() {
  if (mirrorSweepRunning) return;
  mirrorSweepRunning = true;
  try {
    const pending = await pool.query(
      `SELECT b.store_id, b.id
         FROM backup_snapshots b
         JOIN drive_connections d ON d.store_id = b.store_id
        WHERE b.drive_file_id IS NULL
        ORDER BY b.created_at ASC
        LIMIT 10`
    );
    for (const item of pending.rows) {
      try {
        await mirrorSnapshot(pool, item.store_id, item.id);
      } catch (error) {
        console.error('Drive mirror retry failed for snapshot', item.id, error.message);
      }
    }
  } catch (error) {
    console.error('Drive mirror recovery scan failed:', error.message);
  } finally {
    mirrorSweepRunning = false;
  }
}

const server = http.createServer({
  requestTimeout: 120_000,
  headersTimeout: 15_000,
  keepAliveTimeout: 5_000
}, async (req, res) => {
  try {
    await handle(req, res);
  } catch (error) {
    if (res.headersSent) return res.destroy();
    const status = error.statusCode || 500;
    if (status >= 500) console.error('Request failed:', error.message);
    send(res, status, {
      error: status >= 500 ? 'Internal server error' : error.message,
      ...(error.details ? { details: error.details } : {})
    });
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log('Maeyai POS sync backend listening on port ' + PORT);
  void sweepPendingDriveMirrors();
});
const mirrorSweepTimer = setInterval(() => void sweepPendingDriveMirrors(), 30_000);
mirrorSweepTimer.unref();

async function shutdown() {
  clearInterval(mirrorSweepTimer);
  server.close();
  await pool.end();
}
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
