ALTER TABLE ops_incident ADD COLUMN source_run_id VARCHAR(36);
CREATE UNIQUE INDEX uk_incident_source_run ON ops_incident(source_run_id);
ALTER TABLE ops_incident ADD CONSTRAINT fk_incident_source_run FOREIGN KEY(source_run_id) REFERENCES ops_workload_run(id);
ALTER TABLE ops_investigation_run ADD COLUMN progress_json TEXT;
CREATE TABLE ops_investigation_policy (
 server_id BIGINT PRIMARY KEY, enabled BOOLEAN NOT NULL DEFAULT FALSE,
 requested_by BIGINT NOT NULL, min_severity VARCHAR(20) NOT NULL DEFAULT 'CRITICAL',
 cooldown_seconds INT NOT NULL DEFAULT 900, max_per_day INT NOT NULL DEFAULT 3,
 last_triggered_at DATETIME(6),
 FOREIGN KEY(server_id) REFERENCES ops_node(id), FOREIGN KEY(requested_by) REFERENCES sys_user(id)
);
