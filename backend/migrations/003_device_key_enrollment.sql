BEGIN;
ALTER TABLE stores
  ADD COLUMN IF NOT EXISTS current_key_epoch BIGINT NOT NULL DEFAULT 0 CHECK (current_key_epoch >= 0);
ALTER TABLE devices
  ADD COLUMN IF NOT EXISTS public_key TEXT,
  ADD COLUMN IF NOT EXISTS key_fingerprint CHAR(64);
CREATE TABLE IF NOT EXISTS store_key_wrappers (
  store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
  device_id UUID NOT NULL,
  key_epoch BIGINT NOT NULL CHECK (key_epoch > 0),
  wrapped_key BYTEA NOT NULL,
  created_by_device UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(store_id, device_id, key_epoch),
  FOREIGN KEY(store_id, device_id) REFERENCES devices(store_id, id),
  FOREIGN KEY(store_id, created_by_device) REFERENCES devices(store_id, id)
);
CREATE TABLE IF NOT EXISTS store_recovery_wrappers (
  store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
  key_epoch BIGINT NOT NULL CHECK (key_epoch > 0),
  wrapped_key BYTEA NOT NULL,
  created_by_device UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(store_id, key_epoch),
  FOREIGN KEY(store_id, created_by_device) REFERENCES devices(store_id, id)
);
COMMIT;
