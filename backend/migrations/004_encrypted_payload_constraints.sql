BEGIN;

-- Defense in depth: event and quarantined conflict payloads must remain opaque
-- AES-256-GCM envelopes even if a future server route accidentally skips validation.
DO $migration$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'sync_events_payload_ciphertext_only') THEN
    ALTER TABLE sync_events ADD CONSTRAINT sync_events_payload_ciphertext_only CHECK (
    jsonb_typeof(payload) = 'object'
    AND (payload - ARRAY['algorithm','encryptionVersion','aadVersion','keyEpoch','nonce','ciphertext']) = '{}'::jsonb
    AND payload ?& ARRAY['algorithm','encryptionVersion','aadVersion','keyEpoch','nonce','ciphertext']
    AND payload->>'algorithm' = 'A256GCM'
    AND payload->>'encryptionVersion' = '1'
    AND payload->>'aadVersion' = '1'
    AND jsonb_typeof(payload->'keyEpoch') = 'number'
    AND (payload->>'keyEpoch')::numeric BETWEEN 1 AND 9007199254740991
    AND jsonb_typeof(payload->'nonce') = 'string'
    AND payload->>'nonce' ~ '^[A-Za-z0-9_-]{16}$'
    AND jsonb_typeof(payload->'ciphertext') = 'string'
    AND length(payload->>'ciphertext') BETWEEN 22 AND 349526
    AND payload->>'ciphertext' ~ '^[A-Za-z0-9_-]+$'
    );
  END IF;
END
$migration$;

DO $migration$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'sync_conflicts_payload_ciphertext_only') THEN
    ALTER TABLE sync_conflicts ADD CONSTRAINT sync_conflicts_payload_ciphertext_only CHECK (
    jsonb_typeof(submitted_payload) = 'object'
    AND (submitted_payload - ARRAY['algorithm','encryptionVersion','aadVersion','keyEpoch','nonce','ciphertext']) = '{}'::jsonb
    AND submitted_payload ?& ARRAY['algorithm','encryptionVersion','aadVersion','keyEpoch','nonce','ciphertext']
    AND submitted_payload->>'algorithm' = 'A256GCM'
    AND submitted_payload->>'encryptionVersion' = '1'
    AND submitted_payload->>'aadVersion' = '1'
    AND jsonb_typeof(submitted_payload->'keyEpoch') = 'number'
    AND (submitted_payload->>'keyEpoch')::numeric BETWEEN 1 AND 9007199254740991
    AND jsonb_typeof(submitted_payload->'nonce') = 'string'
    AND submitted_payload->>'nonce' ~ '^[A-Za-z0-9_-]{16}$'
    AND jsonb_typeof(submitted_payload->'ciphertext') = 'string'
    AND length(submitted_payload->>'ciphertext') BETWEEN 22 AND 349526
    AND submitted_payload->>'ciphertext' ~ '^[A-Za-z0-9_-]+$'
    );
  END IF;
END
$migration$;

COMMIT;
