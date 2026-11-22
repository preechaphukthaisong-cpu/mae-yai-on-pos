import crypto from 'node:crypto';
import { OAuth2Client } from 'google-auth-library';

const oauthClient = new OAuth2Client(process.env.GOOGLE_OAUTH_CLIENT_ID);

export async function verifyGoogleIdentity(idToken) {
  if (!process.env.GOOGLE_OAUTH_CLIENT_ID) throw new Error('GOOGLE_OAUTH_CLIENT_ID is not configured');
  const ticket = await oauthClient.verifyIdToken({
    idToken,
    audience: process.env.GOOGLE_OAUTH_CLIENT_ID
  });
  const p = ticket.getPayload();
  if (!p?.sub || !p.email || p.email_verified !== true) {
    const error = new Error('A verified Google account is required');
    error.statusCode = 401;
    throw error;
  }
  return { sub: p.sub, email: p.email };
}

function encryptionKey() {
  const raw = process.env.TOKEN_ENCRYPTION_KEY_BASE64 || '';
  const key = Buffer.from(raw, 'base64');
  if (key.length !== 32) throw new Error('TOKEN_ENCRYPTION_KEY_BASE64 must decode to exactly 32 bytes');
  return key;
}

export function encryptRefreshToken(token) {
  if (typeof token !== 'string' || token.length < 20 || token.length > 8192) {
    throw new Error('Invalid Google Drive refresh token');
  }
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', encryptionKey(), iv);
  const ciphertext = Buffer.concat([cipher.update(token, 'utf8'), cipher.final()]);
  return { ciphertext, iv, tag: cipher.getAuthTag() };
}

export function decryptRefreshToken(row) {
  const decipher = crypto.createDecipheriv('aes-256-gcm', encryptionKey(), row.refresh_token_iv);
  decipher.setAuthTag(row.refresh_token_tag);
  return Buffer.concat([
    decipher.update(row.refresh_token_ciphertext),
    decipher.final()
  ]).toString('utf8');
}

export function sha256Hex(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

export function canonicalJson(value) {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return '[' + value.map(canonicalJson).join(',') + ']';
  return '{' + Object.keys(value).sort().map(k =>
    JSON.stringify(k) + ':' + canonicalJson(value[k])
  ).join(',') + '}';
}

export function isUuid(value) {
  return typeof value === 'string'
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

export function sameImmutableEvent(prior, event, digest) {
  return Boolean(prior && event)
    && prior.payload_sha256 === digest
    && prior.entity_type === event.entityType
    && prior.entity_id === event.entityId
    && prior.operation === event.operation
    && BigInt(prior.base_version) === BigInt(event.baseVersion);
}

/** Enforce the opaque AES-256-GCM envelope; plaintext domain JSON is never accepted. */
export function validateEncryptedEventPayload(payload) {
  const allowedFields = ['aadVersion', 'algorithm', 'ciphertext',
    'encryptionVersion', 'keyEpoch', 'nonce'];
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)
      || Object.keys(payload).length !== allowedFields.length
      || Object.keys(payload).some((key) => !allowedFields.includes(key))
      || payload.encryptionVersion !== 1 || payload.aadVersion !== 1
      || payload.algorithm !== 'A256GCM'
      || !Number.isSafeInteger(payload.keyEpoch) || payload.keyEpoch < 1
      || typeof payload.nonce !== 'string' || typeof payload.ciphertext !== 'string') {
    return false;
  }
  const base64url = /^[A-Za-z0-9_-]+$/;
  if (!base64url.test(payload.nonce) || !base64url.test(payload.ciphertext)) return false;
  let nonce;
  let ciphertext;
  try {
    nonce = Buffer.from(payload.nonce, 'base64url');
    ciphertext = Buffer.from(payload.ciphertext, 'base64url');
  } catch {
    return false;
  }
  return nonce.length === 12 && ciphertext.length >= 16 && ciphertext.length <= 256 * 1024 + 16
    && nonce.toString('base64url') === payload.nonce
    && ciphertext.toString('base64url') === payload.ciphertext;
}
