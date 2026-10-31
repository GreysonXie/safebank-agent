-- Independent product workspace; existing banking tables and balances are preserved.
CREATE TABLE user_profiles (
 user_id VARCHAR(40) PRIMARY KEY, name VARCHAR(80) NOT NULL, phone VARCHAR(40) NOT NULL, gender VARCHAR(20) NOT NULL,
 CONSTRAINT ck_profile_gender CHECK(gender IN ('UNKNOWN','MALE','FEMALE','UNDISCLOSED'))
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
CREATE TABLE recipient_details (
 recipient_id VARCHAR(80) PRIMARY KEY, user_id VARCHAR(40) NOT NULL, gender VARCHAR(20) NOT NULL, savings_target BOOLEAN NOT NULL,
 CONSTRAINT ck_contact_gender CHECK(gender IN ('UNKNOWN','MALE','FEMALE','UNDISCLOSED'))
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
CREATE TABLE bank_cards (
 id VARCHAR(80) PRIMARY KEY, user_id VARCHAR(40) NOT NULL, account_id VARCHAR(80) NOT NULL, label VARCHAR(40) NOT NULL,
 card_type VARCHAR(16) NOT NULL, suffix VARCHAR(4) NOT NULL, status VARCHAR(16) NOT NULL,
 per_transaction_limit_minor BIGINT NOT NULL, daily_limit_minor BIGINT NOT NULL,
 online_enabled BOOLEAN NOT NULL, overseas_enabled BOOLEAN NOT NULL, version BIGINT NOT NULL, created_at DATETIME(6) NOT NULL,
 CONSTRAINT ck_card_type CHECK(card_type IN ('DEBIT','VIRTUAL')),
 CONSTRAINT ck_card_status CHECK(status IN ('ACTIVE','LOCKED','LOST')),
 CONSTRAINT ck_card_limits CHECK(per_transaction_limit_minor>0 AND daily_limit_minor>=per_transaction_limit_minor AND daily_limit_minor<=10000000),
 CONSTRAINT ck_card_version CHECK(version>=1)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
CREATE TABLE card_operations (
 id VARCHAR(80) PRIMARY KEY, user_id VARCHAR(40) NOT NULL, card_id VARCHAR(80), kind VARCHAR(24) NOT NULL,
 payload TEXT NOT NULL, payload_hash VARCHAR(64) NOT NULL, status VARCHAR(24) NOT NULL, expected_version BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL, expires_at DATETIME(6) NOT NULL, executed_at DATETIME(6),
 receipt_id VARCHAR(80), result_json TEXT,
 CONSTRAINT ck_card_operation_status CHECK(status IN ('AWAITING_CONFIRMATION','COMPLETED','CANCELLED')),
 CONSTRAINT ck_card_operation_kind CHECK(kind IN ('APPLY','UPDATE_LIMITS','UPDATE_CONTROLS','LOCK','UNLOCK','REPORT_LOST'))
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
