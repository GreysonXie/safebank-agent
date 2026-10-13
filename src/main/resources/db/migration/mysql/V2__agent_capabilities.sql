-- Storage only: grant, callback and ACK services are a later milestone.
CREATE TABLE agent_service_grants (
 id VARCHAR(80) PRIMARY KEY,
 token_hash CHAR(64) NOT NULL UNIQUE,
 user_id VARCHAR(40) NOT NULL,
 conversation_id VARCHAR(80) NOT NULL,
 turn_id VARCHAR(80) NOT NULL,
 request_id VARCHAR(80) NOT NULL,
 scope_json LONGTEXT NOT NULL,
 state VARCHAR(16) NOT NULL,
 call_limit INT NOT NULL,
 calls_used INT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 CONSTRAINT ck_grant_state CHECK(state IN ('ACTIVE','REVOKED','EXPIRED')),
 CONSTRAINT ck_grant_quota CHECK(call_limit BETWEEN 1 AND 8 AND calls_used BETWEEN 0 AND call_limit),
 CONSTRAINT ck_grant_expiry CHECK(expires_at>created_at),
 CONSTRAINT fk_grant_turn FOREIGN KEY(turn_id,conversation_id,user_id) REFERENCES agent_turn_receipts(id,conversation_id,user_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
CREATE INDEX ix_grant_owner ON agent_service_grants(user_id,turn_id,state);
CREATE INDEX ix_grant_expiry ON agent_service_grants(expires_at);

CREATE TABLE agent_tool_call_receipts (
 id VARCHAR(80) PRIMARY KEY,
 turn_id VARCHAR(80) NOT NULL,
 conversation_id VARCHAR(80) NOT NULL,
 user_id VARCHAR(40) NOT NULL,
 operation_key CHAR(64) NOT NULL,
 input_hash CHAR(64) NOT NULL,
 tool_name VARCHAR(80) NOT NULL,
 grant_id VARCHAR(80) NOT NULL,
 result_json LONGTEXT NOT NULL,
 result_hash CHAR(64) NOT NULL,
 observed_at DATETIME(6) NOT NULL,
 CONSTRAINT uq_tool_operation UNIQUE(turn_id,operation_key),
 CONSTRAINT fk_tool_turn FOREIGN KEY(turn_id,conversation_id,user_id) REFERENCES agent_turn_receipts(id,conversation_id,user_id),
 CONSTRAINT fk_tool_grant FOREIGN KEY(grant_id) REFERENCES agent_service_grants(id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
