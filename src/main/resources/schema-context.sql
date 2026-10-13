-- Explicit fresh-memory context schema; never auto-migrate old files.
-- Four additive tables; existing bank/risk tables remain unchanged.
CREATE TABLE agent_conversations (
    id VARCHAR(80) PRIMARY KEY,
    user_id VARCHAR(40) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version >= 1),
    intent VARCHAR(40) NOT NULL DEFAULT 'GENERAL',
    state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (state IN ('ACTIVE','NEEDS_CLARIFICATION','AWAITING_CONFIRMATION','PAUSED','EXPIRED')),
    proposal_json CLOB,
    display_history_json CLOB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_agent_conversation_owner UNIQUE (id,user_id)
);
CREATE INDEX idx_agent_conversation_user ON agent_conversations(user_id,updated_at,id);

CREATE TABLE agent_turn_receipts (
    id VARCHAR(80) PRIMARY KEY,
    conversation_id VARCHAR(80) NOT NULL,
    user_id VARCHAR(40) NOT NULL,
    request_id VARCHAR(80) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    input_version BIGINT NOT NULL CHECK (input_version >= 1),
    output_version BIGINT CHECK (output_version >= input_version),
    state VARCHAR(24) NOT NULL CHECK (state IN ('PROCESSING','COMPLETED','FAILED','INTERRUPTED')),
    tool_intents_json CLOB,
    bank_references_json CLOB,
    response_json CLOB,
    error_code VARCHAR(80),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_agent_turn_request UNIQUE (conversation_id,user_id,request_id),
    CONSTRAINT uq_agent_turn_owner UNIQUE (id,conversation_id,user_id),
    CONSTRAINT fk_agent_turn_conversation FOREIGN KEY (conversation_id,user_id)
        REFERENCES agent_conversations(id,user_id)
);
CREATE INDEX idx_agent_turn_state ON agent_turn_receipts(conversation_id,user_id,state,created_at);

CREATE TABLE agent_context_evidence (
    id VARCHAR(80) PRIMARY KEY,
    conversation_id VARCHAR(80) NOT NULL,
    user_id VARCHAR(40) NOT NULL,
    evidence_key VARCHAR(120) NOT NULL,
    tool_name VARCHAR(80) NOT NULL,
    object_id VARCHAR(80),
    snapshot_json CLOB,
    content_hash VARCHAR(64) NOT NULL,
    source_turn_id VARCHAR(80) NOT NULL,
    observed_at TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    valid_until TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_agent_evidence_key UNIQUE (conversation_id,user_id,evidence_key),
    CONSTRAINT uq_agent_evidence_owner UNIQUE (id,conversation_id,user_id),
    CONSTRAINT fk_agent_evidence_conversation FOREIGN KEY (conversation_id,user_id)
        REFERENCES agent_conversations(id,user_id),
    CONSTRAINT fk_agent_evidence_turn FOREIGN KEY (source_turn_id,conversation_id,user_id)
        REFERENCES agent_turn_receipts(id,conversation_id,user_id)
);

CREATE TABLE agent_context_slots (
    conversation_id VARCHAR(80) NOT NULL,
    user_id VARCHAR(40) NOT NULL,
    slot_name VARCHAR(80) NOT NULL,
    value_json CLOB NOT NULL,
    provenance VARCHAR(24) NOT NULL
        CHECK (provenance IN ('USER_EXPLICIT','MODEL_PROPOSED','BANK_CONFIRMED')),
    context_version BIGINT NOT NULL CHECK (context_version >= 1),
    source_turn_id VARCHAR(80) NOT NULL,
    evidence_id VARCHAR(80),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (conversation_id,user_id,slot_name),
    CONSTRAINT fk_agent_slot_conversation FOREIGN KEY (conversation_id,user_id)
        REFERENCES agent_conversations(id,user_id),
    CONSTRAINT fk_agent_slot_turn FOREIGN KEY (source_turn_id,conversation_id,user_id)
        REFERENCES agent_turn_receipts(id,conversation_id,user_id),
    CONSTRAINT fk_agent_slot_evidence FOREIGN KEY (evidence_id,conversation_id,user_id)
        REFERENCES agent_context_evidence(id,conversation_id,user_id)
);
