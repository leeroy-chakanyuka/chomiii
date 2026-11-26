CREATE TABLE spoken_slot_choices (
    message_id CHAR(36) NOT NULL PRIMARY KEY,
    context_message_id CHAR(36),
    owner_hash CHAR(64) NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    memory_revision BIGINT NOT NULL,
    activity VARCHAR(20) NOT NULL,
    local_date DATE NOT NULL,
    local_time TIME NOT NULL,
    zone_id VARCHAR(64) NOT NULL,
    captured_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT spoken_slot_message_fk FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE,
    CONSTRAINT spoken_slot_context_fk FOREIGN KEY (context_message_id) REFERENCES messages(id) ON DELETE CASCADE,
    CONSTRAINT spoken_slot_owner_fk FOREIGN KEY (owner_hash) REFERENCES browser_sessions(owner_hash) ON DELETE CASCADE,
    CONSTRAINT spoken_slot_conversation_fk FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
);

CREATE INDEX spoken_slot_owner_conversation_idx ON spoken_slot_choices(owner_hash, conversation_id, expires_at);
CREATE INDEX spoken_slot_expiry_idx ON spoken_slot_choices(expires_at);
