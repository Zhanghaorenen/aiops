CREATE TABLE ops_resource_guard (
 node_id BIGINT PRIMARY KEY,
 policy_json TEXT NOT NULL,
 status_json TEXT,
 last_seen_at DATETIME(6),
 request_id VARCHAR(36),
 requested_at DATETIME(6),
 FOREIGN KEY(node_id) REFERENCES ops_node(id)
);
CREATE TABLE ops_resource_guard_event (
 event_id VARCHAR(36) PRIMARY KEY,
 node_id BIGINT NOT NULL,
 agent_id BIGINT NOT NULL,
 event_json TEXT NOT NULL,
 received_at DATETIME(6) NOT NULL,
 FOREIGN KEY(node_id) REFERENCES ops_node(id)
);
CREATE INDEX idx_guard_event_node ON ops_resource_guard_event(node_id,received_at);
