-- Existing nodes belong to the one shared cluster, explicitly confirmed by the user.
-- Preserve V5 ownership/issuer fields and all existing node/agent/metric IDs.
CREATE TABLE ops_cluster (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 cluster_name VARCHAR(160) NOT NULL,
 description VARCHAR(1000), status VARCHAR(16) NOT NULL,
 bootstrap_completed_at DATETIME(6), bootstrap_user_id BIGINT,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 CONSTRAINT uk_ops_cluster_name UNIQUE (cluster_name),
 CONSTRAINT fk_cluster_bootstrap_user FOREIGN KEY (bootstrap_user_id) REFERENCES sys_user(id)
);
CREATE TABLE ops_cluster_member (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 cluster_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 role VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 CONSTRAINT uk_cluster_member UNIQUE(cluster_id,user_id),
 CONSTRAINT fk_member_cluster FOREIGN KEY(cluster_id) REFERENCES ops_cluster(id),
 CONSTRAINT fk_member_user FOREIGN KEY(user_id) REFERENCES sys_user(id),
 CONSTRAINT ck_member_role CHECK(role IN ('ADMIN','OPERATOR','VIEWER')),
 CONSTRAINT ck_member_status CHECK(status IN ('ACTIVE','DISABLED'))
);
CREATE INDEX idx_member_user_status_cluster ON ops_cluster_member(user_id,status,cluster_id);
INSERT INTO ops_cluster(cluster_name,description,status,created_at,updated_at)
 VALUES('default-cluster','Shared server cluster','ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6));
ALTER TABLE ops_node ADD COLUMN cluster_id BIGINT;
UPDATE ops_node SET cluster_id=(SELECT id FROM ops_cluster WHERE cluster_name='default-cluster');
ALTER TABLE ops_node MODIFY COLUMN cluster_id BIGINT NOT NULL;
ALTER TABLE ops_node ADD CONSTRAINT fk_node_cluster FOREIGN KEY(cluster_id) REFERENCES ops_cluster(id);
CREATE INDEX idx_node_cluster_status ON ops_node(cluster_id,status,id);
-- Historical tokens remain unassigned; do not invent their issuer or change validity in stage 1.
ALTER TABLE ops_agent_enroll_token ADD COLUMN cluster_id BIGINT;
ALTER TABLE ops_agent_enroll_token ADD CONSTRAINT fk_enroll_cluster FOREIGN KEY(cluster_id) REFERENCES ops_cluster(id);
CREATE INDEX idx_enroll_cluster_expiry ON ops_agent_enroll_token(cluster_id,expires_at,used_at);
