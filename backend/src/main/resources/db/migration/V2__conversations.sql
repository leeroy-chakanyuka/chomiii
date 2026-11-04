CREATE TABLE browser_sessions (
    owner_hash CHAR(64) NOT NULL PRIMARY KEY,
    created_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE conversations (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_hash CHAR(64) NOT NULL,
    title VARCHAR(120) NOT NULL,
    active_request_id CHAR(36),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT conversations_owner_fk FOREIGN KEY (owner_hash)
        REFERENCES browser_sessions(owner_hash) ON DELETE CASCADE
);

CREATE INDEX conversations_owner_updated_idx ON conversations(owner_hash, updated_at);

CREATE TABLE messages (
    id CHAR(36) NOT NULL PRIMARY KEY,
    conversation_id CHAR(36) NOT NULL,
    sequence_no BIGINT NOT NULL,
    request_id CHAR(36) NOT NULL,
    role VARCHAR(10) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(12) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT messages_conversation_fk FOREIGN KEY (conversation_id)
        REFERENCES conversations(id) ON DELETE CASCADE,
    CONSTRAINT messages_sequence_unique UNIQUE (conversation_id, sequence_no),
    CONSTRAINT messages_request_role_unique UNIQUE (conversation_id, request_id, role),
    CONSTRAINT messages_sequence_positive CHECK (sequence_no > 0),
    CONSTRAINT messages_role_status CHECK (
        (role = 'user' AND status = 'complete') OR
        (role = 'assistant' AND status IN ('pending', 'complete', 'failed', 'interrupted'))
    )
);
