-- Reviewed-design candidate only; NOT included in application schema.sql or startup.
-- Apply once after the existing 13-table schema and preflight checks, on a NEW candidate database.
-- All user IDs come from the authenticated/synthetic server session, never model arguments.
ALTER TABLE actions ADD CONSTRAINT uq_risk_actions_owner UNIQUE(id,user_id);
ALTER TABLE schedules ADD CONSTRAINT uq_risk_schedules_owner UNIQUE(id,user_id);

CREATE TABLE risk_user_guards (
    user_id VARCHAR(40) PRIMARY KEY,
    failed_verifications INT NOT NULL DEFAULT 0 CHECK(failed_verifications>=0),
    failure_window_started_at TIMESTAMP WITH TIME ZONE,
    locked_until TIMESTAMP WITH TIME ZONE,
    last_challenge_at TIMESTAMP WITH TIME ZONE,
    reconciliation_required BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE risk_decisions (
    id VARCHAR(80) PRIMARY KEY,
    action_id VARCHAR(80) NOT NULL,
    user_id VARCHAR(40) NOT NULL,
    revision BIGINT NOT NULL CHECK(revision>0),
    policy_version VARCHAR(40) NOT NULL,
    risk_level VARCHAR(12) NOT NULL CHECK(risk_level IN ('GREEN','YELLOW','RED')),
    required_assurance VARCHAR(24) NOT NULL CHECK(required_assurance IN ('NONE','CONFIRMED','STEP_UP')),
    reason_code VARCHAR(80) NOT NULL,
    business_date DATE NOT NULL,
    amount_minor BIGINT NOT NULL CHECK(amount_minor>=0),
    effective_minor BIGINT NOT NULL CHECK(effective_minor>=0),
    projected_minor BIGINT NOT NULL CHECK(projected_minor>=0),
    payload_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_until TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK(valid_until>created_at),
    CHECK((risk_level='GREEN' AND required_assurance='NONE') OR
          (risk_level='YELLOW' AND required_assurance='CONFIRMED') OR
          (risk_level='RED' AND required_assurance='STEP_UP')),
    UNIQUE(action_id,revision),
    UNIQUE(id,action_id,user_id),
    FOREIGN KEY(action_id,user_id) REFERENCES actions(id,user_id),
    FOREIGN KEY(user_id) REFERENCES risk_user_guards(user_id)
);
CREATE INDEX ix_risk_decision_owner ON risk_decisions(user_id,action_id,revision);

CREATE TABLE verification_challenges (
    id VARCHAR(80) PRIMARY KEY,
    action_id VARCHAR(80) NOT NULL,
    user_id VARCHAR(40) NOT NULL,
    decision_id VARCHAR(80) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    method VARCHAR(24) NOT NULL CHECK(method IN ('DEMO_OTP','EXTERNAL_MFA')),
    provider_reference VARCHAR(120),
    code_digest VARCHAR(128),
    status VARCHAR(24) NOT NULL CHECK(status IN ('PENDING_DELIVERY','SENT','VERIFIED','CONSUMED','EXPIRED','LOCKED','INVALIDATED','DELIVERY_FAILED')),
    failed_attempts INT NOT NULL DEFAULT 0 CHECK(failed_attempts BETWEEN 0 AND 3),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    verified_at TIMESTAMP WITH TIME ZONE,
    CHECK(expires_at>created_at),
    CHECK(method<>'DEMO_OTP' OR code_digest IS NOT NULL),
    UNIQUE(id,action_id,user_id),
    FOREIGN KEY(decision_id,action_id,user_id) REFERENCES risk_decisions(id,action_id,user_id)
);
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
    method VARCHAR(24) NOT NULL CHECK(method IN ('USER_CONFIRM','DEMO_OTP','EXTERNAL_MFA','SCHEDULE_DELEGATION')),
    assurance VARCHAR(24) NOT NULL CHECK(assurance IN ('CONFIRMED','DEMO_VERIFIED','MFA_VERIFIED')),
    status VARCHAR(24) NOT NULL CHECK(status IN ('ACTIVE','SUBMISSION_STARTED','CONSUMED','REVOKED','EXPIRED')),
    authorized_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK(expires_at>authorized_at),
    CHECK((method IN ('USER_CONFIRM','SCHEDULE_DELEGATION') AND assurance='CONFIRMED' AND challenge_id IS NULL) OR
          (method='DEMO_OTP' AND assurance='DEMO_VERIFIED' AND challenge_id IS NOT NULL) OR
          (method='EXTERNAL_MFA' AND assurance='MFA_VERIFIED' AND challenge_id IS NOT NULL)),
    UNIQUE(id,action_id,user_id),
    UNIQUE(id,user_id),
    FOREIGN KEY(decision_id,action_id,user_id) REFERENCES risk_decisions(id,action_id,user_id),
    FOREIGN KEY(challenge_id,action_id,user_id) REFERENCES verification_challenges(id,action_id,user_id)
);
CREATE INDEX ix_authorization_action ON action_authorizations(user_id,action_id,status);

CREATE TABLE risk_transfer_usage (
    action_id VARCHAR(80) PRIMARY KEY,
    user_id VARCHAR(40) NOT NULL,
    authorization_id VARCHAR(80),
    provenance VARCHAR(24) NOT NULL CHECK(provenance IN ('CURRENT_AUTHORIZATION','LEGACY_OBSERVED')),
    business_date DATE,
    amount_minor BIGINT NOT NULL CHECK(amount_minor>0),
    state VARCHAR(16) NOT NULL CHECK(state IN ('RESERVED','SUBMITTED','COMMITTED','RELEASED')),
    reserved_until TIMESTAMP WITH TIME ZONE NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE,
    resolved_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK((provenance='CURRENT_AUTHORIZATION' AND authorization_id IS NOT NULL) OR
          (provenance='LEGACY_OBSERVED' AND authorization_id IS NULL AND state IN ('SUBMITTED','COMMITTED','RELEASED'))),
    CHECK(business_date IS NOT NULL OR (provenance='LEGACY_OBSERVED' AND state='SUBMITTED')),
    FOREIGN KEY(action_id,user_id) REFERENCES actions(id,user_id),
    FOREIGN KEY(authorization_id,action_id,user_id) REFERENCES action_authorizations(id,action_id,user_id),
    FOREIGN KEY(user_id) REFERENCES risk_user_guards(user_id)
);
CREATE INDEX ix_risk_usage_day ON risk_transfer_usage(user_id,business_date,state);
CREATE INDEX ix_risk_usage_open ON risk_transfer_usage(user_id,state,reserved_until);

CREATE TABLE schedule_authorizations (
    schedule_id VARCHAR(80) PRIMARY KEY,
    user_id VARCHAR(40) NOT NULL,
    source_authorization_id VARCHAR(80) NOT NULL,
    scope_json VARCHAR(4000) NOT NULL,
    scope_hash VARCHAR(64) NOT NULL,
    policy_version VARCHAR(40) NOT NULL,
    maximum_risk_level VARCHAR(12) NOT NULL CHECK(maximum_risk_level='YELLOW'),
    status VARCHAR(16) NOT NULL CHECK(status IN ('ACTIVE','REVOKED','EXPIRED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK(expires_at>created_at),
    FOREIGN KEY(schedule_id,user_id) REFERENCES schedules(id,user_id),
    FOREIGN KEY(source_authorization_id,user_id) REFERENCES action_authorizations(id,user_id)
);

CREATE TABLE risk_events (
    id VARCHAR(80) PRIMARY KEY,
    request_id VARCHAR(80) NOT NULL,
    user_id VARCHAR(40) NOT NULL,
    action_id VARCHAR(80),
    decision_id VARCHAR(80),
    event_code VARCHAR(80) NOT NULL,
    reason_code VARCHAR(80),
    channel VARCHAR(24) NOT NULL CHECK(channel IN ('HTTP','AGENT','WORKFLOW','SCHEDULER','SYSTEM')),
    policy_version VARCHAR(40) NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK(decision_id IS NULL OR action_id IS NOT NULL),
    FOREIGN KEY(user_id) REFERENCES risk_user_guards(user_id),
    FOREIGN KEY(action_id,user_id) REFERENCES actions(id,user_id),
    FOREIGN KEY(decision_id,action_id,user_id) REFERENCES risk_decisions(id,action_id,user_id)
);
CREATE INDEX ix_risk_event_owner ON risk_events(user_id,recorded_at);
CREATE INDEX ix_risk_event_action ON risk_events(action_id,recorded_at);
