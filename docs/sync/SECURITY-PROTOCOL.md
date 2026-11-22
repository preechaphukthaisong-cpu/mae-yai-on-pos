# Sync Security Protocol (Pre-Production ADR)

Status: proposed protocol; implementation and interoperability tests are required before any sync endpoint may receive store data.

## Security objectives

- The API, PostgreSQL operator, logs, and Drive mirror must never receive plaintext business payloads or the store content-encryption key.
- Google ID tokens authenticate an account; they are not encryption keys and must never be used as key material.
- A revoked device must not obtain future store keys. Revocation requires key rotation and re-wrapping the new key only for active devices.
- Device loss, account sign-out, app reinstall, and recovery must have explicit, testable behavior. No silent fallback to plaintext is permitted.

## Cryptographic profile

- Content key: independently generated 256-bit random key per store key epoch.
- Payload encryption: AES-256-GCM with a fresh cryptographically random 96-bit nonce for every event and snapshot.
- Authentication tag: 128 bits. Decryption must fail closed on any tag or associated-data mismatch.
- Key derivation: HKDF-SHA-256 with domain-separated labels only; never derive keys from a Google token, PIN, device ID, or user-entered low-entropy value.
- Device key agreement: Android Keystore P-256 ECDH key pair, private key non-exportable where the platform supports it. Use a reviewed, interoperable HKDF construction for wrapping the store key.
- Recovery: a high-entropy recovery secret generated on-device, displayed once, and used only to wrap the store key. Store only the wrapped key and KDF metadata server-side; never store the recovery secret.
- Do not implement custom cryptographic primitives. Use Android/JCA primitives and published test vectors.

## Encrypted event wire envelope

The server-visible payload is an opaque object with exactly these versioned fields:

- `encryptionVersion`: integer protocol version.
- `keyEpoch`: positive integer identifying the store-key epoch.
- `algorithm`: fixed identifier `A256GCM`.
- `nonce`: base64url-encoded 12-byte nonce.
- `ciphertext`: base64url-encoded ciphertext followed by the 16-byte GCM tag.
- `aadVersion`: integer version of associated-data encoding.

Associated data is a canonical UTF-8 encoding of immutable routing metadata: store ID, event ID, operation, entity type, entity ID, base version, and encryption version. The server validates routing metadata and idempotency but cannot decrypt content. The client verifies the same metadata before decryption and verifies the decrypted domain payload schema before applying it.

The event content hash used for server idempotency is SHA-256 over the canonical encrypted envelope bytes. The backend must compare event ID, immutable routing metadata, and ciphertext hash on retries. Reuse of an event ID with changed ciphertext or metadata is rejected.

## Device enrollment and recovery

1. An authenticated device creates its non-exportable ECDH key pair and registers the public key plus a proof-of-possession challenge.
2. A device already trusted by the store scans a short-lived pairing QR containing the new device's public key fingerprint and a one-time nonce. The QR contains no store key or bearer credential.
3. The trusted device verifies the fingerprint and explicitly approves enrollment, then wraps the current store key to the new device's public key.
4. The new device verifies the wrapping metadata and decrypts the key inside its local process. Private key material never leaves Android Keystore.
5. Recovery enrollment wraps the same store key to the one-time recovery secret. The user must confirm that the recovery secret was saved before the server accepts the recovery wrapper.
6. Device revocation increments the key epoch, generates a new random store key, re-encrypts future writes under the new epoch, and wraps it only for remaining active devices and the recovery secret. Historical ciphertext remains decryptable only by devices that retained the prior epoch key; this behavior must be explicit in the product's recovery policy.

## Required implementation gates

- Cross-platform test vectors for AAD canonicalization, AES-GCM encrypt/decrypt, HKDF, device key wrapping, recovery wrapping, and tamper rejection.
- Tests for nonce uniqueness policy, wrong key, modified nonce, modified AAD, truncated ciphertext, unsupported version, unknown key epoch, revoked device, and recovery.
- Backend schema and OpenAPI contract updated to accept opaque encrypted envelopes and never log request bodies or ciphertext-derived secrets.
- Redaction tests for HTTP logs, crash reports, analytics, and diagnostic output.
- No plaintext compatibility mode, feature flag, or fallback path in production builds.
- No customer, sale, debt, or inventory event may be uploaded until the encrypted envelope is mandatory end-to-end and all gates pass.

## Operational rules

- Drive `appDataFolder` stores only already-encrypted backup artifacts. It is a mirror, not the event database.
- TLS remains mandatory even with end-to-end encryption.
- Key rotation, recovery, and revocation are separate operations with durable audit records.
- If encryption, key lookup, or authentication fails, keep events in the local durable outbox and show a recoverable error. Never discard the local event or send plaintext.
- This ADR does not authorize provisioning Cloud resources, enabling live sync, merging the PR, or releasing an APK.
