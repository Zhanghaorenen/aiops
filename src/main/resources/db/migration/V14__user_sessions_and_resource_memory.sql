ALTER TABLE ops_training_linux_identity ADD COLUMN linux_uid BIGINT;
CREATE TABLE ai_chat_session (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, title VARCHAR(160) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', active_request VARCHAR(36), lease_token VARCHAR(36), lease_until DATETIME(6),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY(user_id) REFERENCES sys_user(id)
);
CREATE INDEX idx_chat_owner ON ai_chat_session(user_id,status,updated_at);
CREATE TABLE ai_chat_message (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, session_id VARCHAR(36) NOT NULL, request_id VARCHAR(36) NOT NULL,
 role VARCHAR(16) NOT NULL, content MEDIUMTEXT NOT NULL, status VARCHAR(16) NOT NULL,
 tool_name VARCHAR(100), metadata_json MEDIUMTEXT, created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(session_id) REFERENCES ai_chat_session(id), UNIQUE(session_id,request_id,role)
);
CREATE INDEX idx_chat_messages ON ai_chat_message(session_id,id);
CREATE TABLE ai_user_memory_setting (
 user_id BIGINT PRIMARY KEY, automatic BOOLEAN NOT NULL DEFAULT FALSE, FOREIGN KEY(user_id) REFERENCES sys_user(id)
);
CREATE TABLE ai_user_memory (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, content TEXT NOT NULL, source VARCHAR(40) NOT NULL,
 created_at DATETIME(6) NOT NULL, FOREIGN KEY(user_id) REFERENCES sys_user(id)
);
CREATE INDEX idx_memory_owner ON ai_user_memory(user_id,created_at);
CREATE TABLE ops_workload_run (
 id VARCHAR(36) PRIMARY KEY, server_id BIGINT NOT NULL, user_id BIGINT, job_id BIGINT,
 root_pid BIGINT NOT NULL, process_started_at VARCHAR(64) NOT NULL, linux_uid BIGINT, linux_username VARCHAR(160),
 state VARCHAR(32) NOT NULL, confirmed BOOLEAN NOT NULL DEFAULT FALSE,
 task_type VARCHAR(80), configuration_key VARCHAR(160), environment_key VARCHAR(160),
 grouping_json TEXT NOT NULL, first_seen DATETIME(6) NOT NULL, last_seen DATETIME(6) NOT NULL,
 ended_at DATETIME(6), outcome_source VARCHAR(40), profile_key VARCHAR(64),
 FOREIGN KEY(server_id) REFERENCES ops_node(id), FOREIGN KEY(user_id) REFERENCES sys_user(id),
 FOREIGN KEY(job_id) REFERENCES ops_training_job(id), UNIQUE(server_id,root_pid,process_started_at)
);
CREATE INDEX idx_workload_live ON ops_workload_run(server_id,state,last_seen);
CREATE TABLE ops_workload_sample (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, run_id VARCHAR(36) NOT NULL, snapshot_id BIGINT NOT NULL,
 sampled_at DATETIME(6) NOT NULL, metrics_json MEDIUMTEXT NOT NULL,
 FOREIGN KEY(run_id) REFERENCES ops_workload_run(id), UNIQUE(run_id,snapshot_id)
);
CREATE TABLE ops_workload_profile (
 profile_key VARCHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL, statistics_json MEDIUMTEXT NOT NULL,
 updated_at DATETIME(6) NOT NULL, FOREIGN KEY(user_id) REFERENCES sys_user(id)
);
CREATE TABLE ops_workload_prediction (
 id VARCHAR(36) PRIMARY KEY, run_id VARCHAR(36) NOT NULL, method VARCHAR(40) NOT NULL,
 predicted_at DATETIME(6) NOT NULL, earliest_at DATETIME(6) NOT NULL, latest_at DATETIME(6) NOT NULL,
 actual_at DATETIME(6), absolute_error_seconds DOUBLE, relative_error DOUBLE,
 FOREIGN KEY(run_id) REFERENCES ops_workload_run(id)
);
CREATE INDEX idx_prediction_run ON ops_workload_prediction(run_id,predicted_at);
CREATE TABLE ops_incident_case (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, server_id BIGINT NOT NULL, incident_id BIGINT,
 title VARCHAR(160) NOT NULL, symptoms TEXT NOT NULL, evidence_json TEXT NOT NULL,
 candidate_cause TEXT, resolution TEXT, status VARCHAR(16) NOT NULL DEFAULT 'CANDIDATE',
 scope VARCHAR(16) NOT NULL DEFAULT 'PRIVATE', version INT NOT NULL DEFAULT 1,
 occurrences INT NOT NULL DEFAULT 1, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY(user_id) REFERENCES sys_user(id), FOREIGN KEY(server_id) REFERENCES ops_node(id)
);
CREATE INDEX idx_case_scope ON ops_incident_case(server_id,status,scope,user_id);
CREATE TABLE ops_resource_subscription (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, server_id BIGINT NOT NULL, gpu_index INT NOT NULL,
 required_vram_mb DOUBLE NOT NULL, required_ram_mb DOUBLE NOT NULL, run_id VARCHAR(36),
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', cooldown_seconds INT NOT NULL DEFAULT 3600,
 last_notified_at DATETIME(6), created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(user_id) REFERENCES sys_user(id), FOREIGN KEY(server_id) REFERENCES ops_node(id),
 FOREIGN KEY(run_id) REFERENCES ops_workload_run(id)
);
CREATE TABLE ops_user_notification (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, subscription_id VARCHAR(36) NOT NULL,
 kind VARCHAR(32) NOT NULL, content_json TEXT NOT NULL, dedup_key VARCHAR(160) NOT NULL UNIQUE,
 is_read BOOLEAN NOT NULL DEFAULT FALSE, created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(user_id) REFERENCES sys_user(id), FOREIGN KEY(subscription_id) REFERENCES ops_resource_subscription(id)
);
CREATE INDEX idx_notification_owner ON ops_user_notification(user_id,is_read,created_at);
