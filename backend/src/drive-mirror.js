import { decryptRefreshToken } from './security.js';

async function getDriveAccessToken(refreshToken) {
  const form = new URLSearchParams({
    client_id: process.env.GOOGLE_OAUTH_CLIENT_ID || '',
    client_secret: process.env.GOOGLE_DRIVE_OAUTH_CLIENT_SECRET || '',
    refresh_token: refreshToken,
    grant_type: 'refresh_token'
  });
  const response = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: form
  });
  if (!response.ok) throw new Error('Google Drive token refresh failed');
  const body = await response.json();
  if (!body.access_token) throw new Error('Google Drive did not return an access token');
  return body.access_token;
}

async function assertDriveAccount(accessToken, expectedGoogleSub) {
  const response = await fetch('https://www.googleapis.com/oauth2/v3/userinfo', {
    headers: { authorization: 'Bearer ' + accessToken }
  });
  if (!response.ok) throw new Error('Unable to verify connected Google Drive account');
  const profile = await response.json();
  if (profile.sub !== expectedGoogleSub) {
    throw new Error('Connected Google Drive account does not match the store owner');
  }
}

async function uploadAppDataFile(accessToken, snapshot) {
  const boundary = 'maeyai_' + cryptoRandomBoundary();
  const metadata = JSON.stringify({
    name: 'maeyai-pos-full-' + snapshot.id + '.myobak',
    mimeType: 'application/octet-stream',
    parents: ['appDataFolder'],
    appProperties: {
      storeId: snapshot.store_id,
      snapshotId: snapshot.id,
      throughSeq: String(snapshot.through_seq),
      sha256: snapshot.sha256,
      format: 'maeyai-pos-full-v1'
    }
  });
  const body = Buffer.concat([
    Buffer.from('--' + boundary + '\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n' + metadata + '\r\n'),
    Buffer.from('--' + boundary + '\r\nContent-Type: application/octet-stream\r\n\r\n'),
    snapshot.encrypted_blob,
    Buffer.from('\r\n--' + boundary + '--')
  ]);
  const response = await fetch('https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,createdTime', {
    method: 'POST',
    headers: {
      authorization: 'Bearer ' + accessToken,
      'content-type': 'multipart/related; boundary=' + boundary,
      'content-length': String(body.length)
    },
    body
  });
  if (!response.ok) throw new Error('Google Drive backup upload failed');
  const file = await response.json();
  if (!file.id) throw new Error('Google Drive upload response did not include a file ID');
  return file.id;
}

function cryptoRandomBoundary() {
  return crypto.randomBytes(18).toString('hex');
}

async function findExistingSnapshot(accessToken, snapshotId) {
  const query = "appProperties has { key='snapshotId' and value='" + snapshotId + "' } and trashed = false";
  const url = 'https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&pageSize=1&fields=files(id)&q='
    + encodeURIComponent(query);
  const response = await fetch(url, {
    headers: { authorization: 'Bearer ' + accessToken }
  });
  if (!response.ok) throw new Error('Google Drive snapshot reconciliation failed');
  const body = await response.json();
  return body.files?.[0]?.id || null;
}

async function deleteDriveFile(accessToken, fileId) {
  const response = await fetch('https://www.googleapis.com/drive/v3/files/' + encodeURIComponent(fileId), {
    method: 'DELETE',
    headers: { authorization: 'Bearer ' + accessToken }
  });
  if (!response.ok && response.status !== 404) {
    throw new Error('Google Drive backup rotation could not delete an expired file');
  }
}

export async function mirrorSnapshot(pool, storeId, snapshotId) {
  const client = await pool.connect();
  try {
    const result = await client.query(
      `SELECT b.*, s.owner_google_sub, d.refresh_token_ciphertext,
              d.refresh_token_iv, d.refresh_token_tag
         FROM backup_snapshots b
         JOIN stores s ON s.id = b.store_id
         LEFT JOIN drive_connections d ON d.store_id = b.store_id
        WHERE b.store_id = $1 AND b.id = $2`,
      [storeId, snapshotId]
    );
    if (!result.rowCount || !result.rows[0].refresh_token_ciphertext) return { mirrored: false, reason: 'drive_not_connected' };
    const snapshot = result.rows[0];
    const refreshToken = decryptRefreshToken(snapshot);
    const accessToken = await getDriveAccessToken(refreshToken);
    await assertDriveAccount(accessToken, snapshot.owner_google_sub);
    const driveFileId = await findExistingSnapshot(accessToken, snapshot.id)
      || await uploadAppDataFile(accessToken, snapshot);
    await client.query(
      'UPDATE backup_snapshots SET drive_file_id = $3, drive_uploaded_at = now() WHERE store_id = $1 AND id = $2',
      [storeId, snapshotId, driveFileId]
    );

    const keep = Math.max(1, Math.min(10, Number.parseInt(process.env.BACKUP_RETENTION_FULL || '3', 10) || 3));
    const expired = await client.query(
      `SELECT id, drive_file_id FROM backup_snapshots
        WHERE store_id = $1 AND id <> $2 AND drive_file_id IS NOT NULL
        ORDER BY through_seq DESC, created_at DESC OFFSET $3`,
      [storeId, snapshotId, keep - 1]
    );
    for (const old of expired.rows) {
      await deleteDriveFile(accessToken, old.drive_file_id);
      await client.query('DELETE FROM backup_snapshots WHERE store_id = $1 AND id = $2', [storeId, old.id]);
    }
    return { mirrored: true, driveFileId, rotated: expired.rowCount };
  } finally {
    client.release();
  }
}

import crypto from 'node:crypto';
