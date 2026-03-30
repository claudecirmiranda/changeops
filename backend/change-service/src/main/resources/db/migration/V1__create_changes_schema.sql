-- V1__create_changes_schema.sql
-- ChangeOps Dashboard - Initial Schema

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ───────────────────────────────────────────────
-- changes
-- ───────────────────────────────────────────────
CREATE TABLE changes (
    change_id       UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    title           VARCHAR(255)    NOT NULL,
    description     TEXT,
    component_id    VARCHAR(100)    NOT NULL,
    requested_by    VARCHAR(100)    NOT NULL,
    scheduled_at    TIMESTAMPTZ     NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PREPARED'
                    CHECK (status IN ('DRAFT','PREPARED','COMPLETED','FAILED','CANCELLED')),
    correlation_id  UUID            NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_changes_status          ON changes (status);
CREATE INDEX idx_changes_component_id    ON changes (component_id);
CREATE INDEX idx_changes_created_at      ON changes (created_at DESC);
CREATE INDEX idx_changes_correlation_id  ON changes (correlation_id);

-- ───────────────────────────────────────────────
-- change_events  (timeline store)
-- ───────────────────────────────────────────────
CREATE TABLE change_events (
    event_id      UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    change_id     UUID          NOT NULL REFERENCES changes(change_id) ON DELETE CASCADE,
    event_type    VARCHAR(100)  NOT NULL,
    payload       JSONB         NOT NULL DEFAULT '{}',
    occurred_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_change_events_change_id    ON change_events (change_id);
CREATE INDEX idx_change_events_occurred_at  ON change_events (occurred_at DESC);
CREATE INDEX idx_change_events_type         ON change_events (event_type);

-- ───────────────────────────────────────────────
-- processed_events  (idempotency store)
-- ───────────────────────────────────────────────
CREATE TABLE processed_events (
    event_id        UUID          NOT NULL,
    processed_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    service_name    VARCHAR(100)  NOT NULL,
    PRIMARY KEY (event_id, service_name)
);

CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at DESC);

-- ───────────────────────────────────────────────
-- auto-update updated_at trigger
-- ───────────────────────────────────────────────
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_changes_updated_at
    BEFORE UPDATE ON changes
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
