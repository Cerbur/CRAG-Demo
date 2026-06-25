-- Test-only DDL for crag-event JDBC component tests (H2 in PostgreSQL mode).
-- The production Knowledge smoke tables are created by crag-knowledge-service in plan_17/17.4.
CREATE TABLE IF NOT EXISTS outbox_event (
  event_id BIGINT NOT NULL PRIMARY KEY,
  event_type VARCHAR(64) NOT NULL,
  producer VARCHAR(64) NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  resource_id BIGINT NOT NULL,
  operation_version BIGINT NOT NULL,
  payload_version INT NOT NULL,
  payload_json TEXT NOT NULL,
  trace_id VARCHAR(128) NOT NULL,
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
  status VARCHAR(16) NOT NULL,
  next_attempt_at TIMESTAMP WITH TIME ZONE,
  attempt_count INT NOT NULL DEFAULT 0,
  last_error_code VARCHAR(48),
  last_error_message VARCHAR(512),
  published_at TIMESTAMP WITH TIME ZONE,
  version BIGINT NOT NULL,
  claimed_by VARCHAR(64),
  claimed_until TIMESTAMP WITH TIME ZONE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS processed_event (
  consumer_name VARCHAR(64) NOT NULL,
  event_id BIGINT NOT NULL,
  idempotency_key VARCHAR(256) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  resource_id BIGINT NOT NULL,
  operation_version BIGINT NOT NULL,
  stream_key VARCHAR(128) NOT NULL,
  stream_record_id VARCHAR(128) NOT NULL,
  first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
  processed_at TIMESTAMP WITH TIME ZONE,
  status VARCHAR(16) NOT NULL,
  handler_attempt_count INT NOT NULL DEFAULT 0,
  last_error_code VARCHAR(48),
  last_error_message VARCHAR(512),
  CONSTRAINT pk_processed_event PRIMARY KEY (consumer_name, event_id),
  CONSTRAINT uq_processed_idempotency UNIQUE (consumer_name, idempotency_key)
);
