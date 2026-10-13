CREATE TABLE agent_thread_bindings (
 id VARCHAR(80) PRIMARY KEY,
 user_id VARCHAR(40) NOT NULL,
 conversation_id VARCHAR(80) NOT NULL,
 thread_id VARCHAR(80) NOT NULL UNIQUE,
 schema_version INT NOT NULL DEFAULT 1,
 last_output_version BIGINT,
 waiting_run_id VARCHAR(80),
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 CONSTRAINT uq_agent_binding UNIQUE(user_id,conversation_id),
 CONSTRAINT ck_binding_version CHECK(schema_version=1 AND (last_output_version IS NULL OR last_output_version>0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE agent_runs (
 id VARCHAR(80) PRIMARY KEY,
 binding_id VARCHAR(80) NOT NULL,
 java_turn_id VARCHAR(80) NOT NULL UNIQUE,
 request_id VARCHAR(80) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 request_json LONGTEXT NOT NULL,
 thread_id VARCHAR(80) NOT NULL,
 input_version BIGINT NOT NULL,
 output_version BIGINT,
 state VARCHAR(32) NOT NULL,
 lease_owner VARCHAR(80),
 lease_revision BIGINT NOT NULL DEFAULT 0,
 lease_until DATETIME(6),
 result_json LONGTEXT,
 result_hash CHAR(64),
 result_kind VARCHAR(24),
 error_code VARCHAR(80),
 interrupt_id VARCHAR(80),
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 acknowledged_at DATETIME(6),
 CONSTRAINT uq_agent_run_request UNIQUE(binding_id,request_id),
 CONSTRAINT fk_run_binding FOREIGN KEY(binding_id) REFERENCES agent_thread_bindings(id),
 CONSTRAINT ck_run_state CHECK(state IN ('PROCESSING','RESULT_READY','NEEDS_CLARIFICATION','COMPLETED','FAILED','INTERRUPTED','RECONCILIATION_REQUIRED')),
 CONSTRAINT ck_run_version CHECK(input_version>0 AND (output_version IS NULL OR output_version=input_version+1)),
 CONSTRAINT ck_run_lease CHECK(lease_revision>=0),
 CONSTRAINT ck_run_result CHECK(state NOT IN ('RESULT_READY','NEEDS_CLARIFICATION','COMPLETED') OR (result_json IS NOT NULL AND result_hash IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE INDEX ix_agent_run_lease ON agent_runs(state,lease_until,id);
CREATE INDEX ix_agent_run_updated ON agent_runs(state,updated_at,id);
CREATE TABLE agent_effect_receipts (
 operation_key CHAR(64) PRIMARY KEY,
 origin_run_id VARCHAR(80) NOT NULL,
 binding_id VARCHAR(80) NOT NULL,
 origin_java_turn_id VARCHAR(80) NOT NULL,
 node_name VARCHAR(80) NOT NULL,
 logical_sequence BIGINT NOT NULL,
 purpose VARCHAR(24) NOT NULL,
 input_hash CHAR(64) NOT NULL,
 state VARCHAR(16) NOT NULL,
 reservation_id VARCHAR(80),
 result_json LONGTEXT,
 result_hash CHAR(64),
 lease_revision BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_effect_run FOREIGN KEY(origin_run_id) REFERENCES agent_runs(id),
 CONSTRAINT fk_effect_binding FOREIGN KEY(binding_id) REFERENCES agent_thread_bindings(id),
 CONSTRAINT ck_effect_state CHECK(state IN ('PREPARED','SENDING','SUCCEEDED','FAILED','UNKNOWN')),
 CONSTRAINT ck_effect_sequence CHECK(logical_sequence>=0 AND lease_revision>0),
 CONSTRAINT ck_effect_result CHECK(state<>'SUCCEEDED' OR (result_json IS NOT NULL AND result_hash IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE INDEX ix_effect_recovery ON agent_effect_receipts(state,updated_at,operation_key);
CREATE TABLE agent_model_requests (
 id VARCHAR(80) PRIMARY KEY,
 run_id VARCHAR(80) NOT NULL,
 reservation_id VARCHAR(80) NOT NULL UNIQUE,
 purpose VARCHAR(24) NOT NULL,
 state VARCHAR(16) NOT NULL,
 provider_response_id VARCHAR(160),
 actual_model VARCHAR(120),
 prompt_tokens BIGINT,
 completion_tokens BIGINT,
 cache_hit_tokens BIGINT,
 cache_miss_tokens BIGINT,
 reserved_at DATETIME(6) NOT NULL,
 finished_at DATETIME(6),
 error_code VARCHAR(80),
 CONSTRAINT fk_model_run FOREIGN KEY(run_id) REFERENCES agent_runs(id),
 CONSTRAINT ck_model_state CHECK(state IN ('RESERVED','SUCCEEDED','FAILED','UNKNOWN')),
 CONSTRAINT ck_model_usage CHECK((prompt_tokens IS NULL OR prompt_tokens>=0) AND (completion_tokens IS NULL OR completion_tokens>=0) AND (cache_hit_tokens IS NULL OR cache_hit_tokens>=0) AND (cache_miss_tokens IS NULL OR cache_miss_tokens>=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE agent_run_events (
 id VARCHAR(80) PRIMARY KEY,
 run_id VARCHAR(80) NOT NULL,
 sequence BIGINT NOT NULL,
 node_name VARCHAR(80) NOT NULL,
 event_code VARCHAR(40) NOT NULL,
 detail_json TEXT,
 recorded_at DATETIME(6) NOT NULL,
 CONSTRAINT uq_agent_event UNIQUE(run_id,sequence),
 CONSTRAINT fk_event_run FOREIGN KEY(run_id) REFERENCES agent_runs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE agent_schema_migrations (
 version INT PRIMARY KEY,
 checksum CHAR(64) NOT NULL,
 applied_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
