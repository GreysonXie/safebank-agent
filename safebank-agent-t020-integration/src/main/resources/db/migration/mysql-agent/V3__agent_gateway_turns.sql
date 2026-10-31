CREATE TABLE agent_gateway_turns (
 turn_id VARCHAR(80) PRIMARY KEY,
 conversation_id VARCHAR(80) NOT NULL,
 user_id VARCHAR(40) NOT NULL,
 request_id VARCHAR(80) NOT NULL,
 user_text VARCHAR(4000) NOT NULL,
 input_version BIGINT NOT NULL,
 session_expires_at DATETIME(6) NOT NULL,
 result_hash CHAR(64),
 result_json LONGTEXT,
 output_version BIGINT,
 reconcile_failures INT NOT NULL DEFAULT 0,
 next_check_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 state VARCHAR(24) NOT NULL,
 CONSTRAINT fk_gateway_turn FOREIGN KEY(turn_id,conversation_id,user_id) REFERENCES agent_turn_receipts(id,conversation_id,user_id),
 CONSTRAINT ck_gateway_state CHECK(state IN ('PENDING','ACK_PENDING','ACKED','PAUSED','INTERRUPTED')),
 CONSTRAINT ck_gateway_version CHECK(input_version>0 AND (output_version IS NULL OR output_version=input_version+1)),
 CONSTRAINT ck_gateway_failures CHECK(reconcile_failures BETWEEN 0 AND 3)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
CREATE INDEX ix_gateway_reconcile ON agent_gateway_turns(state,next_check_at,turn_id);
