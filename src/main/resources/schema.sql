CREATE TABLE IF NOT EXISTS accounts(id VARCHAR(80) PRIMARY KEY,user_id VARCHAR(40) NOT NULL,label VARCHAR(120) NOT NULL,balance_minor BIGINT NOT NULL CHECK(balance_minor>=0));
CREATE TABLE IF NOT EXISTS recipients(id VARCHAR(80) PRIMARY KEY,user_id VARCHAR(40) NOT NULL,name VARCHAR(80),phone VARCHAR(40),note VARCHAR(120),account_id VARCHAR(80) NOT NULL);
CREATE TABLE IF NOT EXISTS transactions(id VARCHAR(80) PRIMARY KEY,user_id VARCHAR(40),account_id VARCHAR(80),merchant VARCHAR(200),category VARCHAR(80),amount_minor BIGINT,booked_on DATE);
CREATE TABLE IF NOT EXISTS mandates(id VARCHAR(80) PRIMARY KEY,user_id VARCHAR(40),merchant VARCHAR(120),amount_minor BIGINT,renewal_date DATE,status VARCHAR(40),contract_status VARCHAR(40));
CREATE TABLE IF NOT EXISTS actions(id VARCHAR(80) PRIMARY KEY,user_id VARCHAR(40) NOT NULL,kind VARCHAR(40) NOT NULL,payload VARCHAR(8000) NOT NULL,payload_hash VARCHAR(64) NOT NULL,status VARCHAR(40) NOT NULL,expires_at TIMESTAMP WITH TIME ZONE NOT NULL,receipt_id VARCHAR(80),error_code VARCHAR(80));
CREATE TABLE IF NOT EXISTS ledger(id VARCHAR(80) PRIMARY KEY,action_id VARCHAR(80) NOT NULL,account_id VARCHAR(80) NOT NULL,amount_minor BIGINT NOT NULL,UNIQUE(action_id,account_id));
CREATE TABLE IF NOT EXISTS schedules(id VARCHAR(80) PRIMARY KEY,user_id VARCHAR(40),source_id VARCHAR(80),recipient_id VARCHAR(80),amount_minor BIGINT,day_of_month INT,first_date DATE,end_date DATE,status VARCHAR(40));
CREATE TABLE IF NOT EXISTS occurrences(schedule_id VARCHAR(80),due_date DATE,action_id VARCHAR(80),PRIMARY KEY(schedule_id,due_date));
CREATE TABLE IF NOT EXISTS audit(id VARCHAR(80) PRIMARY KEY,user_id VARCHAR(40),action_id VARCHAR(80),event VARCHAR(80),recorded_at TIMESTAMP WITH TIME ZONE);
ALTER TABLE mandates ADD COLUMN IF NOT EXISTS billing_interval VARCHAR(20) DEFAULT 'MONTHLY';
CREATE TABLE IF NOT EXISTS execution_observations(action_id VARCHAR(80) PRIMARY KEY,user_id VARCHAR(40),state VARCHAR(40),receipt_id VARCHAR(80),error_code VARCHAR(80),observed_at TIMESTAMP WITH TIME ZONE);
CREATE TABLE IF NOT EXISTS bank_faults(action_id VARCHAR(80) PRIMARY KEY,user_id VARCHAR(40),submit_mode VARCHAR(60),lookup_mode VARCHAR(60));
CREATE TABLE IF NOT EXISTS workflows(id VARCHAR(80) PRIMARY KEY,user_id VARCHAR(40) NOT NULL,version BIGINT NOT NULL,state VARCHAR(40) NOT NULL,request_json VARCHAR(8000) NOT NULL,analysis_json VARCHAR(16000) NOT NULL,created_at TIMESTAMP WITH TIME ZONE NOT NULL);
CREATE TABLE IF NOT EXISTS workflow_steps(workflow_id VARCHAR(80) NOT NULL,step_key VARCHAR(120) NOT NULL,input_version BIGINT NOT NULL,kind VARCHAR(40) NOT NULL,status VARCHAR(40) NOT NULL,action_id VARCHAR(80) NOT NULL UNIQUE,saving_minor BIGINT NOT NULL,error_code VARCHAR(80),PRIMARY KEY(workflow_id,step_key,input_version));
ALTER TABLE workflows ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE workflow_steps ADD COLUMN IF NOT EXISTS evidence_json VARCHAR(16000);

CREATE TABLE IF NOT EXISTS user_profiles (
 user_id VARCHAR(40) PRIMARY KEY, name VARCHAR(80) NOT NULL, phone VARCHAR(40) NOT NULL, gender VARCHAR(20) NOT NULL,
 CONSTRAINT ck_profile_gender CHECK(gender IN ('UNKNOWN','MALE','FEMALE','UNDISCLOSED'))
);
CREATE TABLE IF NOT EXISTS recipient_details (
 recipient_id VARCHAR(80) PRIMARY KEY, user_id VARCHAR(40) NOT NULL, gender VARCHAR(20) NOT NULL, savings_target BOOLEAN NOT NULL,
 CONSTRAINT ck_contact_gender CHECK(gender IN ('UNKNOWN','MALE','FEMALE','UNDISCLOSED'))
);
CREATE TABLE IF NOT EXISTS bank_cards (
 id VARCHAR(80) PRIMARY KEY, user_id VARCHAR(40) NOT NULL, account_id VARCHAR(80) NOT NULL, label VARCHAR(40) NOT NULL,
 card_type VARCHAR(16) NOT NULL, suffix VARCHAR(4) NOT NULL, status VARCHAR(16) NOT NULL,
 per_transaction_limit_minor BIGINT NOT NULL, daily_limit_minor BIGINT NOT NULL,
 online_enabled BOOLEAN NOT NULL, overseas_enabled BOOLEAN NOT NULL, version BIGINT NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT ck_card_type CHECK(card_type IN ('DEBIT','VIRTUAL')),
 CONSTRAINT ck_card_status CHECK(status IN ('ACTIVE','LOCKED','LOST')),
 CONSTRAINT ck_card_limits CHECK(per_transaction_limit_minor>0 AND daily_limit_minor>=per_transaction_limit_minor AND daily_limit_minor<=10000000),
 CONSTRAINT ck_card_version CHECK(version>=1)
);
CREATE TABLE IF NOT EXISTS card_operations (
 id VARCHAR(80) PRIMARY KEY, user_id VARCHAR(40) NOT NULL, card_id VARCHAR(80), kind VARCHAR(24) NOT NULL,
 payload VARCHAR(8000) NOT NULL, payload_hash VARCHAR(64) NOT NULL, status VARCHAR(24) NOT NULL, expected_version BIGINT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, expires_at TIMESTAMP WITH TIME ZONE NOT NULL, executed_at TIMESTAMP WITH TIME ZONE,
 receipt_id VARCHAR(80), result_json VARCHAR(8000),
 CONSTRAINT ck_card_operation_status CHECK(status IN ('AWAITING_CONFIRMATION','COMPLETED','CANCELLED')),
 CONSTRAINT ck_card_operation_kind CHECK(kind IN ('APPLY','UPDATE_LIMITS','UPDATE_CONTROLS','LOCK','UNLOCK','REPORT_LOST'))
);
