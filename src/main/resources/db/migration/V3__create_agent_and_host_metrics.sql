CREATE TABLE ops_agent_enroll_token (
    id BIGINT NOT NULL AUTO_INCREMENT,
    token_hash CHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ops_enroll_token_hash UNIQUE (token_hash),
    INDEX idx_ops_enroll_expiry (expires_at, used_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ops_agent (
    id BIGINT NOT NULL AUTO_INCREMENT,
    node_id BIGINT NOT NULL,
    agent_key VARCHAR(100) NOT NULL,
    credential_hash CHAR(64) NOT NULL,
    version VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OFFLINE',
    last_heartbeat_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ops_agent_key UNIQUE (agent_key),
    CONSTRAINT uk_ops_agent_credential UNIQUE (credential_hash),
    CONSTRAINT fk_ops_agent_node FOREIGN KEY (node_id) REFERENCES ops_node (id),
    INDEX idx_ops_agent_node (node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE metric_host_sample (
    id BIGINT NOT NULL AUTO_INCREMENT,
    node_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    cpu_percent DECIMAL(5,2) NOT NULL,
    memory_total_bytes BIGINT NOT NULL,
    memory_used_bytes BIGINT NOT NULL,
    memory_percent DECIMAL(5,2) NOT NULL,
    disk_total_bytes BIGINT NOT NULL,
    disk_used_bytes BIGINT NOT NULL,
    disk_percent DECIMAL(5,2) NOT NULL,
    collected_at DATETIME(6) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_metric_host_node FOREIGN KEY (node_id) REFERENCES ops_node (id),
    CONSTRAINT fk_metric_host_agent FOREIGN KEY (agent_id) REFERENCES ops_agent (id),
    INDEX idx_metric_node_time (node_id, collected_at, id),
    INDEX idx_metric_received_time (received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
