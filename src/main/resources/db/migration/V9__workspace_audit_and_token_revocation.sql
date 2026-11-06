CREATE TABLE ops_action_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, cluster_id BIGINT NOT NULL,
 actor_user_id BIGINT NOT NULL, action VARCHAR(80) NOT NULL, target_id BIGINT,
 detail VARCHAR(1000), created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(cluster_id) REFERENCES ops_cluster(id), FOREIGN KEY(actor_user_id) REFERENCES sys_user(id)
);
CREATE INDEX idx_action_cluster_time ON ops_action_audit(cluster_id,created_at,id);
ALTER TABLE ops_agent_enroll_token ADD COLUMN revoked_at DATETIME(6);
