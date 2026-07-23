CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    checksum TEXT NOT NULL,
    applied_at TEXT NOT NULL
) STRICT;
--@statement
CREATE TABLE drafts (
    id TEXT PRIMARY KEY,
    entry_type TEXT NOT NULL CHECK (entry_type IN ('EXPENSE', 'REFUND', 'LOAN', 'LOAN_PAYMENT')),
    amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
    currency TEXT NOT NULL CHECK (length(currency) = 3),
    occurred_on TEXT NOT NULL,
    merchant TEXT,
    category TEXT,
    person TEXT,
    note TEXT,
    raw_text TEXT NOT NULL,
    source_channel_id TEXT NOT NULL,
    source_message_id TEXT NOT NULL,
    related_entry_id TEXT,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED')),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (source_channel_id, source_message_id)
) STRICT;
--@statement
CREATE TABLE ledger_entries (
    id TEXT PRIMARY KEY,
    draft_id TEXT NOT NULL UNIQUE REFERENCES drafts(id),
    entry_type TEXT NOT NULL CHECK (entry_type IN ('EXPENSE', 'REFUND', 'LOAN', 'LOAN_PAYMENT')),
    amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
    currency TEXT NOT NULL CHECK (length(currency) = 3),
    occurred_on TEXT NOT NULL,
    merchant TEXT,
    category TEXT,
    person TEXT,
    note TEXT,
    raw_text TEXT NOT NULL,
    source_channel_id TEXT NOT NULL,
    source_message_id TEXT NOT NULL,
    related_entry_id TEXT REFERENCES ledger_entries(id),
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'VOID')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
) STRICT;
--@statement
CREATE TABLE audit_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    action TEXT NOT NULL,
    actor TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TEXT NOT NULL
) STRICT;
--@statement
CREATE TABLE backup_runs (
    id TEXT PRIMARY KEY,
    path TEXT,
    sha256 TEXT,
    size_bytes INTEGER,
    status TEXT NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    error TEXT,
    created_at TEXT NOT NULL,
    completed_at TEXT
) STRICT;
--@statement
CREATE INDEX idx_drafts_status_updated ON drafts(status, updated_at DESC);
--@statement
CREATE INDEX idx_ledger_occurred ON ledger_entries(occurred_on DESC, created_at DESC);
--@statement
CREATE INDEX idx_ledger_type_status ON ledger_entries(entry_type, status);
--@statement
CREATE INDEX idx_ledger_related ON ledger_entries(related_entry_id);
--@statement
CREATE INDEX idx_audit_entity ON audit_events(entity_type, entity_id, id);
--@statement
CREATE TRIGGER audit_events_no_update
BEFORE UPDATE ON audit_events
BEGIN
    SELECT RAISE(ABORT, 'audit_events are append-only');
END;
--@statement
CREATE TRIGGER audit_events_no_delete
BEFORE DELETE ON audit_events
BEGIN
    SELECT RAISE(ABORT, 'audit_events are append-only');
END;
