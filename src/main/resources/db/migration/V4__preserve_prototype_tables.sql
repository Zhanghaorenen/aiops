CREATE TABLE IF NOT EXISTS agents (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    hostname VARCHAR(100) NOT NULL,
    ip VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version VARCHAR(32) NOT NULL,
    last_heartbeat_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS metrics (
    id BIGINT NOT NULL AUTO_INCREMENT,
    agent_id BIGINT NOT NULL,
    hostname VARCHAR(100) NOT NULL,
    cpu_usage DOUBLE NOT NULL,
    memory_usage DOUBLE NOT NULL,
    disk_usage DOUBLE NOT NULL,
    gpu_usage DOUBLE,
    network_in_mbps DOUBLE NOT NULL,
    network_out_mbps DOUBLE NOT NULL,
    collected_at DATETIME(6) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_metrics_agent_collected (agent_id, collected_at, id),
    INDEX idx_metrics_collected (collected_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
