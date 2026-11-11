CREATE UNIQUE INDEX personal_memories_owner_id_idx ON personal_memories(owner_hash, id);

CREATE TABLE memory_embedding_jobs (
    owner_hash CHAR(64) NOT NULL,
    memory_id CHAR(36) NOT NULL,
    model_id VARCHAR(100) NOT NULL,
    dimensions INT NOT NULL,
    configuration VARCHAR(40) NOT NULL,
    content_hash CHAR(64),
    status VARCHAR(12) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    lease_token CHAR(36),
    lease_until TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (owner_hash, memory_id),
    CONSTRAINT embedding_jobs_memory_fk FOREIGN KEY (owner_hash, memory_id)
        REFERENCES personal_memories(owner_hash, id) ON DELETE CASCADE,
    CONSTRAINT embedding_jobs_status_check CHECK (status IN ('pending', 'running', 'ready', 'failed'))
);

CREATE INDEX embedding_jobs_due_idx ON memory_embedding_jobs(owner_hash, status, next_attempt_at);

CREATE TABLE memory_embeddings (
    owner_hash CHAR(64) NOT NULL,
    memory_id CHAR(36) NOT NULL,
    model_id VARCHAR(100) NOT NULL,
    dimensions INT NOT NULL,
    configuration VARCHAR(40) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    vector_text TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (owner_hash, memory_id),
    CONSTRAINT memory_embeddings_memory_fk FOREIGN KEY (owner_hash, memory_id)
        REFERENCES personal_memories(owner_hash, id) ON DELETE CASCADE
);

INSERT INTO memory_embedding_jobs
    (owner_hash, memory_id, model_id, dimensions, configuration, content_hash, status,
     attempts, next_attempt_at, lease_token, lease_until, updated_at)
SELECT owner_hash, id, 'gemini-embedding-2', 768, 'search-v1', NULL, 'pending',
       0, CURRENT_TIMESTAMP, NULL, NULL, CURRENT_TIMESTAMP
FROM personal_memories WHERE status = 'active';
