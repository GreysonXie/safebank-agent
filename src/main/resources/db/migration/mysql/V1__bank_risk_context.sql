-- Fresh MySQL baseline. Preserves source strings and ownership; never run against H2.

CREATE TABLE accounts(id VARCHAR(80) PRIMARY KEY,user_id VARCHAR(40) NOT NULL,label VARCHAR(120) NOT NULL,balance_minor BIGINT NOT NULL ,
    CONSTRAINT ck_accounts_1 CHECK(balance_minor>=0)) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE recipients(id VARCHAR(80) PRIMARY KEY,user_id VARCHAR(40) NOT NULL,name VARCHAR(80),phone VARCHAR(40),note VARCHAR(120),account_id VARCHAR(80) NOT NULL) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE transactions(id VARCHAR(80) PRIMARY KEY,user_id VARCHAR(40),account_id VARCHAR(80),merchant VARCHAR(200),category VARCHAR(80),amount_minor BIGINT,booked_on DATE) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE mandates(id VARCHAR(80) PRIMARY KEY,user_id VARCHAR(40),merchant VARCHAR(120),amount_minor BIGINT,renewal_date DATE,status VARCHAR(40),contract_status VARCHAR(40),
    billing_interval VARCHAR(20) DEFAULT 'MONTHLY') ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE actions(id VARCHAR(80) PRIMARY KEY,user_id VARCHAR(40) NOT NULL,kind VARCHAR(40) NOT NULL,payload LONGTEXT NOT NULL,payload_hash VARCHAR(64) NOT NULL,status VARCHAR(40) NOT NULL,expires_at DATETIME(6) NOT NULL,receipt_id VARCHAR(80),error_code VARCHAR(80)) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE ledger(id VARCHAR(80) PRIMARY KEY,action_id VARCHAR(80) NOT NULL,account_id VARCHAR(80) NOT NULL,amount_minor BIGINT NOT NULL,UNIQUE(action_id,account_id)) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE schedules(id VARCHAR(80) PRIMARY KEY,user_id VARCHAR(40),source_id VARCHAR(80),recipient_id VARCHAR(80),amount_minor BIGINT,day_of_month INT,first_date DATE,end_date DATE,status VARCHAR(40)) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE occurrences(schedule_id VARCHAR(80),due_date DATE,action_id VARCHAR(80),PRIMARY KEY(schedule_id,due_date)) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE audit(id VARCHAR(80) PRIMARY KEY,user_id VARCHAR(40),action_id VARCHAR(80),event VARCHAR(80),recorded_at DATETIME(6)) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE execution_observations(action_id VARCHAR(80) PRIMARY KEY,user_id VARCHAR(40),state VARCHAR(40),receipt_id VARCHAR(80),error_code VARCHAR(80),observed_at DATETIME(6)) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE bank_faults(action_id VARCHAR(80) PRIMARY KEY,user_id VARCHAR(40),submit_mode VARCHAR(60),lookup_mode VARCHAR(60)) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE workflows(id VARCHAR(80) PRIMARY KEY,user_id VARCHAR(40) NOT NULL,version BIGINT NOT NULL,state VARCHAR(40) NOT NULL,request_json LONGTEXT NOT NULL,analysis_json LONGTEXT NOT NULL,created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6)) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE workflow_steps(workflow_id VARCHAR(80) NOT NULL,step_key VARCHAR(120) NOT NULL,input_version BIGINT NOT NULL,kind VARCHAR(40) NOT NULL,status VARCHAR(40) NOT NULL,action_id VARCHAR(80) NOT NULL UNIQUE,saving_minor BIGINT NOT NULL,error_code VARCHAR(80),PRIMARY KEY(workflow_id,step_key,input_version),
    evidence_json LONGTEXT) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

ALTER TABLE actions ADD CONSTRAINT uq_risk_actions_owner UNIQUE(id,user_id);

ALTER TABLE schedules ADD CONSTRAINT uq_risk_schedules_owner UNIQUE(id,user_id);

CREATE TABLE risk_user_guards (
    user_id VARCHAR(40) PRIMARY KEY,
    failed_verifications INT NOT NULL DEFAULT 0 ,
    failure_window_started_at DATETIME(6),
    locked_until DATETIME(6),
    last_challenge_at DATETIME(6),
    reconciliation_required BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 0 ,
    updated_at DATETIME(6) NOT NULL
,
    CONSTRAINT ck_risk_user_guards_1 CHECK(failed_verifications>=0),
    CONSTRAINT ck_risk_user_guards_2 CHECK(revision>=0),
    CONSTRAINT ck_risk_user_guards_3 CHECK(reconciliation_required IN (0,1))) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE risk_decisions (
    id VARCHAR(80) PRIMARY KEY,
    action_id VARCHAR(80) NOT NULL,
    user_id VARCHAR(40) NOT NULL,
    revision BIGINT NOT NULL ,
    policy_version VARCHAR(40) NOT NULL,
    risk_level VARCHAR(12) NOT NULL ,
    required_assurance VARCHAR(24) NOT NULL ,
    reason_code VARCHAR(80) NOT NULL,
    business_date DATE NOT NULL,
    amount_minor BIGINT NOT NULL ,
    effective_minor BIGINT NOT NULL ,
    projected_minor BIGINT NOT NULL ,
    payload_hash VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    valid_until DATETIME(6) NOT NULL,
    UNIQUE(action_id,revision),
    UNIQUE(id,action_id,user_id),
    FOREIGN KEY(action_id,user_id) REFERENCES actions(id,user_id),
    FOREIGN KEY(user_id) REFERENCES risk_user_guards(user_id)
,
    CONSTRAINT ck_risk_decisions_1 CHECK(revision>0),
    CONSTRAINT ck_risk_decisions_2 CHECK(risk_level IN ('GREEN','YELLOW','RED')),
    CONSTRAINT ck_risk_decisions_3 CHECK(required_assurance IN ('NONE','CONFIRMED','STEP_UP')),
    CONSTRAINT ck_risk_decisions_4 CHECK(amount_minor>=0),
    CONSTRAINT ck_risk_decisions_5 CHECK(effective_minor>=0),
    CONSTRAINT ck_risk_decisions_6 CHECK(projected_minor>=0),
    CONSTRAINT ck_risk_decisions_7 CHECK(valid_until>created_at),
    CONSTRAINT ck_risk_decisions_8 CHECK((risk_level='GREEN' AND required_assurance='NONE') OR
          (risk_level='YELLOW' AND required_assurance='CONFIRMED') OR
          (risk_level='RED' AND required_assurance='STEP_UP'))) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE INDEX ix_risk_decision_owner ON risk_decisions(user_id,action_id,revision);

CREATE TABLE verification_challenges (
    id VARCHAR(80) PRIMARY KEY,
    action_id VARCHAR(80) NOT NULL,
    user_id VARCHAR(40) NOT NULL,
    decision_id VARCHAR(80) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    method VARCHAR(24) NOT NULL ,
    provider_reference VARCHAR(120),
    code_digest VARCHAR(128),
    status VARCHAR(24) NOT NULL ,
    failed_attempts INT NOT NULL DEFAULT 0 ,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    verified_at DATETIME(6),
    UNIQUE(id,action_id,user_id),
    FOREIGN KEY(decision_id,action_id,user_id) REFERENCES risk_decisions(id,action_id,user_id)
,
    CONSTRAINT ck_verification_challenges_1 CHECK(method IN ('DEMO_OTP','EXTERNAL_MFA')),
    CONSTRAINT ck_verification_challenges_2 CHECK(status IN ('PENDING_DELIVERY','SENT','VERIFIED','CONSUMED','EXPIRED','LOCKED','INVALIDATED','DELIVERY_FAILED')),
    CONSTRAINT ck_verification_challenges_3 CHECK(failed_attempts BETWEEN 0 AND 3),
    CONSTRAINT ck_verification_challenges_4 CHECK(expires_at>created_at),
    CONSTRAINT ck_verification_challenges_5 CHECK(method<>'DEMO_OTP' OR code_digest IS NOT NULL)) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE INDEX ix_verification_owner ON verification_challenges(user_id,action_id,status);

CREATE TABLE action_authorizations (
    id VARCHAR(80) PRIMARY KEY,
    action_id VARCHAR(80) NOT NULL,
    user_id VARCHAR(40) NOT NULL,
    decision_id VARCHAR(80) NOT NULL,
    challenge_id VARCHAR(80),
    payload_hash VARCHAR(64) NOT NULL,
    policy_version VARCHAR(40) NOT NULL,
    business_date DATE NOT NULL,
    method VARCHAR(24) NOT NULL ,
    assurance VARCHAR(24) NOT NULL ,
    status VARCHAR(24) NOT NULL ,
    authorized_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    UNIQUE(id,action_id,user_id),
    UNIQUE(id,user_id),
    FOREIGN KEY(decision_id,action_id,user_id) REFERENCES risk_decisions(id,action_id,user_id),
    FOREIGN KEY(challenge_id,action_id,user_id) REFERENCES verification_challenges(id,action_id,user_id)
,
    CONSTRAINT ck_action_authorizations_1 CHECK(method IN ('USER_CONFIRM','DEMO_OTP','EXTERNAL_MFA','SCHEDULE_DELEGATION')),
    CONSTRAINT ck_action_authorizations_2 CHECK(assurance IN ('CONFIRMED','DEMO_VERIFIED','MFA_VERIFIED')),
    CONSTRAINT ck_action_authorizations_3 CHECK(status IN ('ACTIVE','SUBMISSION_STARTED','CONSUMED','REVOKED','EXPIRED')),
    CONSTRAINT ck_action_authorizations_4 CHECK(expires_at>authorized_at),
    CONSTRAINT ck_action_authorizations_5 CHECK((method IN ('USER_CONFIRM','SCHEDULE_DELEGATION') AND assurance='CONFIRMED' AND challenge_id IS NULL) OR
          (method='DEMO_OTP' AND assurance='DEMO_VERIFIED' AND challenge_id IS NOT NULL) OR
          (method='EXTERNAL_MFA' AND assurance='MFA_VERIFIED' AND challenge_id IS NOT NULL))) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE INDEX ix_authorization_action ON action_authorizations(user_id,action_id,status);

CREATE TABLE risk_transfer_usage (
    action_id VARCHAR(80) PRIMARY KEY,
    user_id VARCHAR(40) NOT NULL,
    authorization_id VARCHAR(80),
    provenance VARCHAR(24) NOT NULL ,
    business_date DATE,
    amount_minor BIGINT NOT NULL ,
    state VARCHAR(16) NOT NULL ,
    reserved_until DATETIME(6) NOT NULL,
    submitted_at DATETIME(6),
    resolved_at DATETIME(6),
    updated_at DATETIME(6) NOT NULL,
    FOREIGN KEY(action_id,user_id) REFERENCES actions(id,user_id),
    FOREIGN KEY(authorization_id,action_id,user_id) REFERENCES action_authorizations(id,action_id,user_id),
    FOREIGN KEY(user_id) REFERENCES risk_user_guards(user_id)
,
    CONSTRAINT ck_risk_transfer_usage_1 CHECK(provenance IN ('CURRENT_AUTHORIZATION','LEGACY_OBSERVED')),
    CONSTRAINT ck_risk_transfer_usage_2 CHECK(amount_minor>0),
    CONSTRAINT ck_risk_transfer_usage_3 CHECK(state IN ('RESERVED','SUBMITTED','COMMITTED','RELEASED')),
    CONSTRAINT ck_risk_transfer_usage_4 CHECK((provenance='CURRENT_AUTHORIZATION' AND authorization_id IS NOT NULL) OR
          (provenance='LEGACY_OBSERVED' AND authorization_id IS NULL AND state IN ('SUBMITTED','COMMITTED','RELEASED'))),
    CONSTRAINT ck_risk_transfer_usage_5 CHECK(business_date IS NOT NULL OR (provenance='LEGACY_OBSERVED' AND state='SUBMITTED'))) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE INDEX ix_risk_usage_day ON risk_transfer_usage(user_id,business_date,state);

CREATE INDEX ix_risk_usage_open ON risk_transfer_usage(user_id,state,reserved_until);

CREATE TABLE schedule_authorizations (
    schedule_id VARCHAR(80) PRIMARY KEY,
    user_id VARCHAR(40) NOT NULL,
    source_authorization_id VARCHAR(80) NOT NULL,
    scope_json LONGTEXT NOT NULL,
    scope_hash VARCHAR(64) NOT NULL,
    policy_version VARCHAR(40) NOT NULL,
    maximum_risk_level VARCHAR(12) NOT NULL ,
    status VARCHAR(16) NOT NULL ,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    FOREIGN KEY(schedule_id,user_id) REFERENCES schedules(id,user_id),
    FOREIGN KEY(source_authorization_id,user_id) REFERENCES action_authorizations(id,user_id)
,
    CONSTRAINT ck_schedule_authorizations_1 CHECK(maximum_risk_level='YELLOW'),
    CONSTRAINT ck_schedule_authorizations_2 CHECK(status IN ('ACTIVE','REVOKED','EXPIRED')),
    CONSTRAINT ck_schedule_authorizations_3 CHECK(expires_at>created_at)) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE risk_events (
    id VARCHAR(80) PRIMARY KEY,
    request_id VARCHAR(80) NOT NULL,
    user_id VARCHAR(40) NOT NULL,
    action_id VARCHAR(80),
    decision_id VARCHAR(80),
    event_code VARCHAR(80) NOT NULL,
    reason_code VARCHAR(80),
    channel VARCHAR(24) NOT NULL ,
    policy_version VARCHAR(40) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    FOREIGN KEY(user_id) REFERENCES risk_user_guards(user_id),
    FOREIGN KEY(action_id,user_id) REFERENCES actions(id,user_id),
    FOREIGN KEY(decision_id,action_id,user_id) REFERENCES risk_decisions(id,action_id,user_id)
,
    CONSTRAINT ck_risk_events_1 CHECK(channel IN ('HTTP','AGENT','WORKFLOW','SCHEDULER','SYSTEM')),
    CONSTRAINT ck_risk_events_2 CHECK(decision_id IS NULL OR action_id IS NOT NULL)) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE INDEX ix_risk_event_owner ON risk_events(user_id,recorded_at);

CREATE INDEX ix_risk_event_action ON risk_events(action_id,recorded_at);

CREATE TABLE agent_conversations (
    id VARCHAR(80) PRIMARY KEY,
    user_id VARCHAR(40) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1 ,
    intent VARCHAR(40) NOT NULL DEFAULT 'GENERAL',
    state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        ,
    proposal_json LONGTEXT,
    display_history_json LONGTEXT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_agent_conversation_owner UNIQUE (id,user_id)
,
    CONSTRAINT ck_agent_conversations_1 CHECK(version >= 1),
    CONSTRAINT ck_agent_conversations_2 CHECK(state IN ('ACTIVE','NEEDS_CLARIFICATION','AWAITING_CONFIRMATION','PAUSED','EXPIRED'))) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE INDEX idx_agent_conversation_user ON agent_conversations(user_id,updated_at,id);

CREATE TABLE agent_turn_receipts (
    id VARCHAR(80) PRIMARY KEY,
    conversation_id VARCHAR(80) NOT NULL,
    user_id VARCHAR(40) NOT NULL,
    request_id VARCHAR(80) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    input_version BIGINT NOT NULL ,
    output_version BIGINT ,
    state VARCHAR(24) NOT NULL ,
    tool_intents_json LONGTEXT,
    bank_references_json LONGTEXT,
    response_json LONGTEXT,
    error_code VARCHAR(80),
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    CONSTRAINT uq_agent_turn_request UNIQUE (conversation_id,user_id,request_id),
    CONSTRAINT uq_agent_turn_owner UNIQUE (id,conversation_id,user_id),
    CONSTRAINT fk_agent_turn_conversation FOREIGN KEY (conversation_id,user_id)
        REFERENCES agent_conversations(id,user_id)
,
    CONSTRAINT ck_agent_turn_receipts_1 CHECK(input_version >= 1),
    CONSTRAINT ck_agent_turn_receipts_2 CHECK(output_version >= input_version),
    CONSTRAINT ck_agent_turn_receipts_3 CHECK(state IN ('PROCESSING','COMPLETED','FAILED','INTERRUPTED'))) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE INDEX idx_agent_turn_state ON agent_turn_receipts(conversation_id,user_id,state,created_at);

CREATE TABLE agent_context_evidence (
    id VARCHAR(80) PRIMARY KEY,
    conversation_id VARCHAR(80) NOT NULL,
    user_id VARCHAR(40) NOT NULL,
    evidence_key VARCHAR(120) NOT NULL,
    tool_name VARCHAR(80) NOT NULL,
    object_id VARCHAR(80),
    snapshot_json LONGTEXT,
    content_hash VARCHAR(64) NOT NULL,
    source_turn_id VARCHAR(80) NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    valid_until DATETIME(6),
    CONSTRAINT uq_agent_evidence_key UNIQUE (conversation_id,user_id,evidence_key),
    CONSTRAINT uq_agent_evidence_owner UNIQUE (id,conversation_id,user_id),
    CONSTRAINT fk_agent_evidence_conversation FOREIGN KEY (conversation_id,user_id)
        REFERENCES agent_conversations(id,user_id),
    CONSTRAINT fk_agent_evidence_turn FOREIGN KEY (source_turn_id,conversation_id,user_id)
        REFERENCES agent_turn_receipts(id,conversation_id,user_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE agent_context_slots (
    conversation_id VARCHAR(80) NOT NULL,
    user_id VARCHAR(40) NOT NULL,
    slot_name VARCHAR(80) NOT NULL,
    value_json LONGTEXT NOT NULL,
    provenance VARCHAR(24) NOT NULL
        ,
    context_version BIGINT NOT NULL ,
    source_turn_id VARCHAR(80) NOT NULL,
    evidence_id VARCHAR(80),
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (conversation_id,user_id,slot_name),
    CONSTRAINT fk_agent_slot_conversation FOREIGN KEY (conversation_id,user_id)
        REFERENCES agent_conversations(id,user_id),
    CONSTRAINT fk_agent_slot_turn FOREIGN KEY (source_turn_id,conversation_id,user_id)
        REFERENCES agent_turn_receipts(id,conversation_id,user_id),
    CONSTRAINT fk_agent_slot_evidence FOREIGN KEY (evidence_id,conversation_id,user_id)
        REFERENCES agent_context_evidence(id,conversation_id,user_id)
,
    CONSTRAINT ck_agent_context_slots_1 CHECK(provenance IN ('USER_EXPLICIT','MODEL_PROPOSED','BANK_CONFIRMED')),
    CONSTRAINT ck_agent_context_slots_2 CHECK(context_version >= 1)) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE INDEX ix_accounts_user ON accounts(user_id,id);

CREATE INDEX ix_recipients_user ON recipients(user_id,id);

CREATE INDEX ix_mandates_user ON mandates(user_id,id);

CREATE INDEX ix_transactions_user_date ON transactions(user_id,booked_on,id);

CREATE INDEX ix_agent_turn_reconcile ON agent_turn_receipts(state,created_at,id);
