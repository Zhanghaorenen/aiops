CREATE TABLE ops_incident (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,cluster_id BIGINT NOT NULL,server_id BIGINT NOT NULL,
 title VARCHAR(200) NOT NULL,description VARCHAR(2000),status VARCHAR(20) NOT NULL,
 created_by BIGINT NOT NULL,created_at DATETIME(6) NOT NULL,updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY(cluster_id) REFERENCES ops_cluster(id),FOREIGN KEY(server_id) REFERENCES ops_node(id)
);
CREATE TABLE ops_incident_alert (incident_id BIGINT NOT NULL,alert_id BIGINT NOT NULL,
 PRIMARY KEY(incident_id,alert_id),FOREIGN KEY(incident_id) REFERENCES ops_incident(id),FOREIGN KEY(alert_id) REFERENCES ops_alert_event(id));
CREATE TABLE ops_incident_history (id BIGINT AUTO_INCREMENT PRIMARY KEY,incident_id BIGINT NOT NULL,
 actor_user_id BIGINT NOT NULL,action VARCHAR(80) NOT NULL,detail VARCHAR(2000),created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(incident_id) REFERENCES ops_incident(id));
CREATE TABLE ops_investigation_run (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,incident_id BIGINT NOT NULL,requested_by BIGINT NOT NULL,
 request_key VARCHAR(80) NOT NULL,status VARCHAR(20) NOT NULL,created_at DATETIME(6) NOT NULL,
 started_at DATETIME(6),finished_at DATETIME(6),lease_until DATETIME(6),lease_token VARCHAR(40),error_code VARCHAR(80),
 UNIQUE(incident_id,requested_by,request_key),FOREIGN KEY(incident_id) REFERENCES ops_incident(id),FOREIGN KEY(requested_by) REFERENCES sys_user(id)
);
CREATE INDEX idx_investigation_status ON ops_investigation_run(status,created_at);
CREATE TABLE ops_diagnosis_report (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,run_id BIGINT NOT NULL UNIQUE,incident_id BIGINT NOT NULL,
 report_json MEDIUMTEXT NOT NULL,helpful BOOLEAN,review_note VARCHAR(2000),reviewed_by BIGINT,created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(run_id) REFERENCES ops_investigation_run(id),FOREIGN KEY(incident_id) REFERENCES ops_incident(id)
);
CREATE TABLE ops_gpu_usage (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,server_id BIGINT NOT NULL,gpu_index INT NOT NULL,user_id BIGINT NOT NULL,
 task_name VARCHAR(160) NOT NULL,purpose VARCHAR(1000),starts_at DATETIME(6) NOT NULL,expected_end_at DATETIME(6) NOT NULL,
 notes VARCHAR(2000),status VARCHAR(20) NOT NULL,created_at DATETIME(6) NOT NULL,updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY(server_id) REFERENCES ops_node(id),FOREIGN KEY(user_id) REFERENCES sys_user(id)
);
CREATE INDEX idx_gpu_usage_server ON ops_gpu_usage(server_id,gpu_index,status);
