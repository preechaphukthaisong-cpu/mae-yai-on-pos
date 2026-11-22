import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import {
  canonicalJson, sha256Hex, encryptRefreshToken, decryptRefreshToken, isUuid, sameImmutableEvent, validateEncryptedEventPayload
} from '../src/security.js';

test('canonical JSON sorts object keys recursively and preserves arrays', () => {
  assert.equal(canonicalJson({ z: 1, a: { y: 2, x: 3 }, list: [2, 1] }),
    '{"a":{"x":3,"y":2},"list":[2,1],"z":1}');
});

test('SHA-256 uses UTF-8 bytes', () => {
  assert.equal(sha256Hex('hello'), '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824');
});

test('Drive refresh token is encrypted and round-trips with AES-256-GCM', () => {
  process.env.TOKEN_ENCRYPTION_KEY_BASE64 = crypto.randomBytes(32).toString('base64');
  const token = 'refresh-token-value-long-enough-for-test';
  const encrypted = encryptRefreshToken(token);
  assert.notEqual(encrypted.ciphertext.toString('utf8'), token);
  assert.equal(decryptRefreshToken({
    refresh_token_ciphertext: encrypted.ciphertext,
    refresh_token_iv: encrypted.iv,
    refresh_token_tag: encrypted.tag
  }), token);
  const tampered = { ...encrypted, tag: Buffer.from(encrypted.tag) };
  tampered.tag[0] ^= 1;
  assert.throws(() => decryptRefreshToken({
    refresh_token_ciphertext: tampered.ciphertext,
    refresh_token_iv: tampered.iv,
    refresh_token_tag: tampered.tag
  }));
});

test('UUID validator rejects malformed identifiers', () => {
  assert.equal(isUuid('b7f9f3a1-5a6a-4f4d-9c2e-7b6f1b5d8e31'), true);
  assert.equal(isUuid('../stores/1'), false);
});


test('event idempotency accepts only byte-identical immutable event metadata', () => {
  const prior = {
    payload_sha256: 'a'.repeat(64),
    entity_type: 'product',
    entity_id: 'SKU-1',
    operation: 'upsert',
    base_version: '4'
  };
  const event = {
    entityType: 'product',
    entityId: 'SKU-1',
    operation: 'upsert',
    baseVersion: 4
  };
  assert.equal(sameImmutableEvent(prior, event, 'a'.repeat(64)), true);
  assert.equal(sameImmutableEvent(prior, { ...event, entityId: 'SKU-2' }, 'a'.repeat(64)), false);
  assert.equal(sameImmutableEvent(prior, { ...event, operation: 'delete' }, 'a'.repeat(64)), false);
  assert.equal(sameImmutableEvent(prior, { ...event, baseVersion: 3 }, 'a'.repeat(64)), false);
  assert.equal(sameImmutableEvent(prior, event, 'b'.repeat(64)), false);
});

test('backend accepts only canonical AES-256-GCM encrypted event envelopes', () => {
  const valid = {
    encryptionVersion: 1,
    keyEpoch: 2,
    algorithm: 'A256GCM',
    nonce: crypto.randomBytes(12).toString('base64url'),
    ciphertext: crypto.randomBytes(48).toString('base64url'),
    aadVersion: 1
  };
  assert.equal(validateEncryptedEventPayload(valid), true);
  assert.equal(validateEncryptedEventPayload({ ...valid, plaintext: { sku: 'LEAK' } }), false);
  assert.equal(validateEncryptedEventPayload({ sku: 'PLAINTEXT' }), false);
  assert.equal(validateEncryptedEventPayload({ ...valid, algorithm: 'A128GCM' }), false);
  assert.equal(validateEncryptedEventPayload({ ...valid, keyEpoch: 0 }), false);
  assert.equal(validateEncryptedEventPayload({ ...valid, nonce: 'bad=' }), false);
  assert.equal(validateEncryptedEventPayload({ ...valid, nonce: Buffer.alloc(11).toString('base64url') }), false);
});
