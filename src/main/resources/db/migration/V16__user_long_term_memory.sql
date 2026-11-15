ALTER TABLE ai_user_memory_setting ADD COLUMN chat_automatic BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ai_user_memory_setting ADD COLUMN chat_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_user_memory_setting ADD COLUMN memory_generation BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_user_memory_setting ADD COLUMN enabled_after_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_user_memory ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT 'USER_PREFERENCE';
ALTER TABLE ai_user_memory ADD COLUMN semantic_key VARCHAR(100);
ALTER TABLE ai_user_memory ADD COLUMN source_session_id VARCHAR(36);
ALTER TABLE ai_user_memory ADD COLUMN source_message_id BIGINT;
ALTER TABLE ai_user_memory ADD COLUMN evidence_quote VARCHAR(500);
ALTER TABLE ai_user_memory ADD COLUMN confidence DOUBLE NOT NULL DEFAULT 1;
ALTER TABLE ai_user_memory ADD COLUMN version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE ai_user_memory ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE ai_user_memory ADD COLUMN updated_at DATETIME(6);
ALTER TABLE ai_user_memory ADD COLUMN write_after_id BIGINT NOT NULL DEFAULT 0;
UPDATE ai_user_memory SET updated_at=created_at;
CREATE UNIQUE INDEX uk_memory_semantic ON ai_user_memory(user_id,category,semantic_key);
CREATE TABLE ai_user_memory_revision (
 memory_id VARCHAR(36) NOT NULL, version BIGINT NOT NULL, user_id BIGINT NOT NULL,
 content TEXT NOT NULL, category VARCHAR(32) NOT NULL, source_session_id VARCHAR(36),
 source_message_id BIGINT, status VARCHAR(16) NOT NULL, created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(memory_id,version), FOREIGN KEY(memory_id) REFERENCES ai_user_memory(id)
);
CREATE TABLE ai_chat_summary (
 session_id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, summary TEXT NOT NULL,
 last_message_id BIGINT NOT NULL DEFAULT 0, version BIGINT NOT NULL DEFAULT 0, updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY(session_id) REFERENCES ai_chat_session(id), FOREIGN KEY(user_id) REFERENCES sys_user(id)
);
CREATE TABLE ai_chat_memory_task (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, session_id VARCHAR(36) NOT NULL,
 from_message_id BIGINT NOT NULL, to_message_id BIGINT NOT NULL, summary_version BIGINT NOT NULL,
 target_message_id BIGINT NOT NULL,
 chat_epoch BIGINT NOT NULL, memory_generation BIGINT NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING', lease_token VARCHAR(36), lease_until DATETIME(6),
 attempts INT NOT NULL DEFAULT 0, next_attempt_at DATETIME(6) NOT NULL, error_code VARCHAR(80),
 payload_json MEDIUMTEXT, result_json MEDIUMTEXT, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE(session_id,from_message_id,to_message_id,chat_epoch,memory_generation),
 FOREIGN KEY(user_id) REFERENCES sys_user(id), FOREIGN KEY(session_id) REFERENCES ai_chat_session(id)
);
CREATE INDEX idx_chat_memory_pending ON ai_chat_memory_task(state,next_attempt_at,lease_until);
CREATE TABLE ai_memory_index_task (
 memory_id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, desired_version BIGINT NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING', lease_token VARCHAR(36), lease_until DATETIME(6),
 attempts INT NOT NULL DEFAULT 0, next_attempt_at DATETIME(6) NOT NULL, error_code VARCHAR(80),
 FOREIGN KEY(memory_id) REFERENCES ai_user_memory(id), FOREIGN KEY(user_id) REFERENCES sys_user(id)
);
INSERT INTO ai_memory_index_task(memory_id,user_id,desired_version,next_attempt_at)
 SELECT id,user_id,version,CURRENT_TIMESTAMP FROM ai_user_memory WHERE status='ACTIVE';
