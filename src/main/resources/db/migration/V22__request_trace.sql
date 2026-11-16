CREATE TABLE ops_request_trace (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, trace_id VARCHAR(32) NOT NULL,
 execution_id VARCHAR(36) NOT NULL, spans_json MEDIUMTEXT NOT NULL, created_at DATETIME(6) NOT NULL,
 UNIQUE(user_id,trace_id,execution_id), FOREIGN KEY(user_id) REFERENCES sys_user(id)
);
CREATE INDEX ix_request_trace_retention ON ops_request_trace(created_at);
