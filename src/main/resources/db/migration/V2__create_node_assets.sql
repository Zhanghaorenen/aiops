CREATE TABLE ops_node (
    id BIGINT NOT NULL AUTO_INCREMENT,
    node_key VARCHAR(100) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    hostname VARCHAR(160) NOT NULL,
    ip VARCHAR(64),
    os_name VARCHAR(120),
    os_version VARCHAR(160),
    architecture VARCHAR(80),
    cpu_cores INT,
    memory_bytes BIGINT,
    status VARCHAR(16) NOT NULL DEFAULT 'OFFLINE',
    last_seen_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ops_node_key UNIQUE (node_key),
    INDEX idx_ops_node_status_seen (status, last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
