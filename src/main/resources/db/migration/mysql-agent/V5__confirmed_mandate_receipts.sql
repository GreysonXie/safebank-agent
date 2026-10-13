CREATE TABLE agent_mandate_query_receipts (
 action_id VARCHAR(80) PRIMARY KEY,
 user_id VARCHAR(40) NOT NULL,
 conversation_id VARCHAR(80) NOT NULL,
 bound_version BIGINT NOT NULL,
 invalidated BOOLEAN NOT NULL DEFAULT FALSE,
 queried_at DATETIME(6),
 expires_at DATETIME(6),
 snapshot_json LONGTEXT,
 snapshot_hash CHAR(64),
 CONSTRAINT fk_mandate_receipt_action FOREIGN KEY(action_id,user_id) REFERENCES actions(id,user_id),
 CONSTRAINT fk_mandate_receipt_conversation FOREIGN KEY(conversation_id,user_id) REFERENCES agent_conversations(id,user_id),
 CONSTRAINT ck_mandate_receipt_version CHECK(bound_version>0),
 CONSTRAINT ck_mandate_receipt_snapshot CHECK((queried_at IS NULL AND expires_at IS NULL AND snapshot_json IS NULL AND snapshot_hash IS NULL) OR (queried_at IS NOT NULL AND expires_at IS NOT NULL AND snapshot_json IS NOT NULL AND snapshot_hash IS NOT NULL AND expires_at>queried_at))
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
CREATE INDEX ix_mandate_receipt_owner ON agent_mandate_query_receipts(user_id,conversation_id,invalidated,queried_at);