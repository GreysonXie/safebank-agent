CREATE TABLE agent_model_turn_limits (
 turn_id VARCHAR(80) PRIMARY KEY,
 input_limit INT NOT NULL,
 created_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_model_limit_turn FOREIGN KEY(turn_id) REFERENCES agent_gateway_turns(turn_id),
 CONSTRAINT ck_model_input_limit CHECK(input_limit BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE=utf8mb4_0900_bin;
INSERT INTO agent_model_turn_limits(turn_id,input_limit,created_at)
SELECT g.turn_id,CASE
 WHEN t.request_hash=SHA2(CONCAT(g.user_text,CHAR(10),'1'),256) THEN 1
 WHEN t.request_hash=SHA2(CONCAT(g.user_text,CHAR(10),'2'),256) THEN 2
 WHEN t.request_hash=SHA2(CONCAT(g.user_text,CHAR(10),'3'),256) THEN 3
 WHEN t.request_hash=SHA2(CONCAT(g.user_text,CHAR(10),'4'),256) THEN 4
 ELSE 5 END,g.created_at
FROM agent_gateway_turns g JOIN agent_turn_receipts t ON t.id=g.turn_id;
