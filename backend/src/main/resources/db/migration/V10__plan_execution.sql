CREATE TABLE plan_executions (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_hash CHAR(64) NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    proposal_json TEXT NOT NULL,
    state VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT plan_execution_conversation_fk FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
    CONSTRAINT plan_execution_owner_fk FOREIGN KEY (owner_hash) REFERENCES browser_sessions(owner_hash) ON DELETE CASCADE,
    CONSTRAINT plan_execution_state_check CHECK (state IN ('PROPOSED', 'AWAITING_APPROVAL', 'RUNNING', 'BLOCKED', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT plan_execution_version_check CHECK (version >= 0)
);
CREATE INDEX plan_execution_owner_conversation_idx ON plan_executions(owner_hash, conversation_id);

CREATE TABLE plan_execution_steps (
    plan_id CHAR(36) NOT NULL,
    step_id VARCHAR(32) NOT NULL,
    execution_id CHAR(36) NOT NULL UNIQUE,
    state VARCHAR(20) NOT NULL,
    arguments_ref VARCHAR(128),
    observation_ref CHAR(36),
    pending_approval_id CHAR(36),
    PRIMARY KEY (plan_id, step_id),
    CONSTRAINT plan_step_plan_fk FOREIGN KEY (plan_id) REFERENCES plan_executions(id) ON DELETE CASCADE,
    CONSTRAINT plan_step_state_check CHECK (state IN ('PROPOSED', 'AWAITING_APPROVAL', 'RUNNING', 'BLOCKED', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT plan_step_observation_check CHECK (state <> 'COMPLETED' OR observation_ref IS NOT NULL)
);
