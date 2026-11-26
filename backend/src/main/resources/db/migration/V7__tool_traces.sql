-- V7: owner-scoped tool invocation traces. One row per tool invocation,
-- recording the lifecycle the user can see: request, authorization/start,
-- and a terminal outcome. Rows never contain raw tool input or output,
-- credentials, or free text; summaries hold counts only. Portable across
-- MySQL and PostgreSQL.

CREATE TABLE tool_traces (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_hash CHAR(64) NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    request_id CHAR(36) NOT NULL,
    plan_id CHAR(36),
    step_id CHAR(36),
    tool_name VARCHAR(64) NOT NULL,
    capability VARCHAR(64) NOT NULL,
    effect VARCHAR(8),
    provider VARCHAR(64),
    model VARCHAR(120),
    authorization_decision VARCHAR(8),
    approval_id CHAR(36),
    status VARCHAR(16) NOT NULL,
    error_code VARCHAR(48),
    retry_of_id CHAR(36),
    idempotency_key VARCHAR(128),
    argument_summary VARCHAR(1000) NOT NULL,
    result_summary VARCHAR(1000) NOT NULL,
    requested_at TIMESTAMP(6) NOT NULL,
    started_at TIMESTAMP(6),
    finished_at TIMESTAMP(6),
    duration_ms BIGINT,
    CONSTRAINT tool_traces_owner_fk FOREIGN KEY (owner_hash)
        REFERENCES browser_sessions(owner_hash) ON DELETE CASCADE,
    CONSTRAINT tool_traces_conversation_fk FOREIGN KEY (conversation_id)
        REFERENCES conversations(id) ON DELETE CASCADE,
    CONSTRAINT tool_traces_effect_check CHECK (effect IN ('READ', 'WRITE')),
    CONSTRAINT tool_traces_authorization_check CHECK (authorization_decision IN ('ALLOW', 'DENY')),
    CONSTRAINT tool_traces_status_check CHECK (status IN
        ('requested', 'running', 'succeeded', 'denied', 'failed', 'cancelled', 'timeout')),
    CONSTRAINT tool_traces_finished_check CHECK (
        (finished_at IS NULL AND duration_ms IS NULL) OR
        (finished_at IS NOT NULL AND duration_ms IS NOT NULL AND duration_ms >= 0))
);

CREATE INDEX tool_traces_conversation_requested_idx
    ON tool_traces(conversation_id, requested_at);

CREATE INDEX tool_traces_owner_requested_idx
    ON tool_traces(owner_hash, requested_at);
