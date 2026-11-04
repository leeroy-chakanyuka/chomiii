CREATE TABLE personal_memories (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_hash CHAR(64) NOT NULL,
    content VARCHAR(500) NOT NULL,
    source VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT personal_memories_owner_fk FOREIGN KEY (owner_hash)
        REFERENCES browser_sessions(owner_hash) ON DELETE CASCADE,
    CONSTRAINT personal_memories_source_check CHECK (source = 'user_explicit'),
    CONSTRAINT personal_memories_content_check CHECK (CHAR_LENGTH(content) > 0)
);

CREATE INDEX personal_memories_owner_updated_idx
    ON personal_memories(owner_hash, updated_at);
