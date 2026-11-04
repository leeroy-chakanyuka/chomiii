CREATE TABLE task_proposals (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_hash CHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT task_proposals_owner_fk FOREIGN KEY (owner_hash)
        REFERENCES browser_sessions(owner_hash) ON DELETE CASCADE,
    CONSTRAINT task_proposals_status_check CHECK (status IN ('pending', 'approved', 'rejected'))
);
CREATE INDEX task_proposals_owner_created_idx ON task_proposals(owner_hash, created_at);

CREATE TABLE personal_tasks (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_hash CHAR(64) NOT NULL,
    proposal_id CHAR(36) NOT NULL UNIQUE,
    title VARCHAR(200) NOT NULL,
    status VARCHAR(12) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT personal_tasks_owner_fk FOREIGN KEY (owner_hash)
        REFERENCES browser_sessions(owner_hash) ON DELETE CASCADE,
    CONSTRAINT personal_tasks_proposal_fk FOREIGN KEY (proposal_id)
        REFERENCES task_proposals(id) ON DELETE CASCADE,
    CONSTRAINT personal_tasks_status_check CHECK (status IN ('open', 'completed'))
);
CREATE INDEX personal_tasks_owner_created_idx ON personal_tasks(owner_hash, created_at);
