-- V4: derived personal profile and categorized paragraphs, composed
-- deterministically from approved active canonical memories. Derived rows are
-- stamped with both the owner's memory_revision and a separate profile content
-- revision, so reads can reject stale or orphaned paragraphs without relying
-- on foreign-key cascades alone (a surviving paragraph may still contain
-- deleted text). Portable across MySQL and PostgreSQL.

CREATE TABLE memory_profiles (
    owner_hash CHAR(64) NOT NULL PRIMARY KEY,
    content_revision BIGINT NOT NULL DEFAULT 0,
    built_content_revision BIGINT,
    based_on_revision BIGINT,
    snapshot_text VARCHAR(4000),
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT memory_profiles_owner_fk FOREIGN KEY (owner_hash)
        REFERENCES browser_sessions(owner_hash) ON DELETE CASCADE
);

CREATE TABLE memory_profile_paragraphs (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_hash CHAR(64) NOT NULL,
    owner_memory_revision BIGINT NOT NULL,
    content_revision BIGINT NOT NULL,
    category VARCHAR(16) NOT NULL,
    -- Stable position in the composed profile (composer declaration order),
    -- so reads reproduce the saved snapshot text ordering exactly instead of
    -- an alphabetical accident.
    position INT NOT NULL,
    text VARCHAR(2000) NOT NULL,
    -- Number of source relationships recorded at build time; reads compare it
    -- with the surviving relationships to detect cascade-deleted sources.
    source_count INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT memory_profile_paragraphs_owner_fk FOREIGN KEY (owner_hash)
        REFERENCES browser_sessions(owner_hash) ON DELETE CASCADE,
    CONSTRAINT memory_profile_paragraphs_text_check CHECK (CHAR_LENGTH(text) > 0),
    CONSTRAINT memory_profile_paragraphs_source_count_check CHECK (source_count > 0)
);

CREATE INDEX memory_profile_paragraphs_owner_idx
    ON memory_profile_paragraphs(owner_hash);

CREATE TABLE memory_profile_sources (
    paragraph_id CHAR(36) NOT NULL,
    memory_id CHAR(36) NOT NULL,
    CONSTRAINT memory_profile_sources_pk PRIMARY KEY (paragraph_id, memory_id),
    CONSTRAINT memory_profile_sources_paragraph_fk FOREIGN KEY (paragraph_id)
        REFERENCES memory_profile_paragraphs(id) ON DELETE CASCADE,
    CONSTRAINT memory_profile_sources_memory_fk FOREIGN KEY (memory_id)
        REFERENCES personal_memories(id) ON DELETE CASCADE
);

CREATE INDEX memory_profile_sources_memory_idx ON memory_profile_sources(memory_id);
