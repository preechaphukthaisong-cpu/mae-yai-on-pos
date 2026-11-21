BEGIN;

CREATE TABLE IF NOT EXISTS stores (
  id UUID PRIMARY KEY,
  owner_google_sub TEXT NOT NULL UNIQUE,
  owner_email TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_event_seq BIGINT NOT NULL DEFAULT 0 CHECK (last_event_seq >= 0)
);

CREATE TABLE IF NOT EXISTS devices (
  id UUID PRIMARY KEY,
  store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
  google_sub TEXT NOT NULL,
  label TEXT NOT NULL,
  platform TEXT NOT NULL DEFAULT 'android',
  registered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  revoked_at TIMESTAMPTZ,
  UNIQUE(store_id, id)
);
CREATE INDEX IF NOT EXISTS devices_store_active_idx
  ON devices(store_id, registered_at) WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS entity_versions (
  store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  version BIGINT NOT NULL CHECK (version > 0),
  last_event_seq BIGINT NOT NULL,
  PRIMARY KEY(store_id, entity_type, entity_id)
);

CREATE TABLE IF NOT EXISTS sync_events (
  store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
  seq BIGINT NOT NULL,
  event_id UUID NOT NULL,
  device_id UUID NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  operation TEXT NOT NULL CHECK (operation IN ('upsert','delete')),
  base_version BIGINT NOT NULL CHECK (base_version >= 0),
  entity_version BIGINT NOT NULL CHECK (entity_version > 0),
  payload JSONB NOT NULL,
  payload_sha256 CHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(store_id, seq),
  UNIQUE(store_id, event_id),
  FOREIGN KEY(store_id, device_id) REFERENCES devices(store_id, id)
);
CREATE INDEX IF NOT EXISTS sync_events_created_idx ON sync_events(store_id, created_at);

CREATE TABLE IF NOT EXISTS backup_snapshots (
  id UUID PRIMARY KEY,
  store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
  created_by_device UUID NOT NULL,
  through_seq BIGINT NOT NULL CHECK (through_seq >= 0),
  sha256 CHAR(64) NOT NULL,
  byte_length BIGINT NOT NULL CHECK (byte_length > 0),
  encrypted_blob BYTEA NOT NULL,
  drive_file_id TEXT,
  drive_uploaded_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(store_id, created_by_device) REFERENCES devices(store_id, id),
  UNIQUE(store_id, through_seq, sha256)
);
CREATE INDEX IF NOT EXISTS backup_snapshots_retention_idx
  ON backup_snapshots(store_id, through_seq DESC, created_at DESC);

CREATE TABLE IF NOT EXISTS drive_connections (
  store_id UUID PRIMARY KEY REFERENCES stores(id) ON DELETE CASCADE,
  refresh_token_ciphertext BYTEA NOT NULL,
  refresh_token_iv BYTEA NOT NULL,
  refresh_token_tag BYTEA NOT NULL,
  connected_by_google_sub TEXT NOT NULL,
  connected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sync_conflicts (
  id UUID PRIMARY KEY,
  store_id UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
  device_id UUID NOT NULL,
  event_id UUID NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  expected_version BIGINT NOT NULL,
  actual_version BIGINT NOT NULL,
  submitted_payload JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  resolved_at TIMESTAMPTZ,
  resolution_event_seq BIGINT
);
CREATE UNIQUE INDEX IF NOT EXISTS sync_conflicts_event_unique_idx ON sync_conflicts(store_id, event_id);
CREATE INDEX IF NOT EXISTS sync_conflicts_open_idx
  ON sync_conflicts(store_id, created_at) WHERE resolved_at IS NULL;

COMMIT;
