ALTER TABLE browser_sessions ADD COLUMN memory_revision BIGINT NOT NULL DEFAULT 0;

CREATE TABLE personal_memories (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_hash CHAR(64) NOT NULL,
    content VARCHAR(500) NOT NULL,
    source VARCHAR(24) NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'active',
    confidence DECIMAL(4,3) NOT NULL DEFAULT 1.000,
    source_conversation_id CHAR(36),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT personal_memories_owner_fk FOREIGN KEY (owner_hash)
        REFERENCES browser_sessions(owner_hash) ON DELETE CASCADE,
    CONSTRAINT personal_memories_source_check CHECK (source IN ('user_explicit', 'conversation_fact', 'inferred')),
    CONSTRAINT personal_memories_status_check CHECK (status IN ('active', 'suggested')),
    CONSTRAINT personal_memories_confidence_check CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT personal_memories_content_check CHECK (CHAR_LENGTH(content) > 0)
);

CREATE INDEX personal_memories_owner_updated_idx
    ON personal_memories(owner_hash, updated_at);
