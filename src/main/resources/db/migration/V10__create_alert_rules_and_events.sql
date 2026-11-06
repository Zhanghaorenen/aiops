CREATE TABLE ops_alert_rule (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, cluster_id BIGINT NOT NULL, server_id BIGINT NOT NULL,
 name VARCHAR(160) NOT NULL, metric VARCHAR(40) NOT NULL, comparison VARCHAR(8) NOT NULL,
 threshold_value DOUBLE NOT NULL, recovery_threshold DOUBLE NOT NULL,
 duration_seconds INT NOT NULL, freshness_seconds INT NOT NULL, max_gap_seconds INT NOT NULL,
 cooldown_seconds INT NOT NULL, severity VARCHAR(16) NOT NULL, enabled BOOLEAN NOT NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 FOREIGN KEY(cluster_id) REFERENCES ops_cluster(id), FOREIGN KEY(server_id) REFERENCES ops_node(id)
);
CREATE TABLE ops_alert_event (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, rule_id BIGINT NOT NULL, cluster_id BIGINT NOT NULL,
 server_id BIGINT NOT NULL, gpu_index INT, fingerprint VARCHAR(160) NOT NULL,
 active_key VARCHAR(160), severity VARCHAR(16) NOT NULL, status VARCHAR(20) NOT NULL,
 first_triggered_at DATETIME(6) NOT NULL, last_observed_at DATETIME(6) NOT NULL,
 resolved_at DATETIME(6), acknowledged_at DATETIME(6), acknowledged_by BIGINT,
 evidence TEXT NOT NULL,
 CONSTRAINT uk_active_alert UNIQUE(active_key), FOREIGN KEY(rule_id) REFERENCES ops_alert_rule(id),
 FOREIGN KEY(server_id) REFERENCES ops_node(id), FOREIGN KEY(cluster_id) REFERENCES ops_cluster(id)
);
CREATE INDEX idx_alert_server_time ON ops_alert_event(server_id,first_triggered_at,id);
CREATE INDEX idx_rule_cluster ON ops_alert_rule(cluster_id,enabled);
