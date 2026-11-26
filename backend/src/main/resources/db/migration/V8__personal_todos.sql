CREATE TABLE personal_todos (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_hash CHAR(64) NOT NULL,
    idempotency_key CHAR(36) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(4000),
    status VARCHAR(12) NOT NULL,
    due_at_micros BIGINT,
    timezone VARCHAR(100),
    source_conversation_id CHAR(36),
    source_plan_id CHAR(36),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT todos_owner_fk FOREIGN KEY (owner_hash) REFERENCES browser_sessions(owner_hash) ON DELETE CASCADE,
    CONSTRAINT todos_owner_key_unique UNIQUE (owner_hash, idempotency_key),
    CONSTRAINT todos_status_check CHECK (status IN ('open', 'completed'))
);
CREATE INDEX todos_owner_created_idx ON personal_todos(owner_hash, created_at);
