-- 预约的时间、用户、GPU 仍由 ops_gpu_usage 管理，训练表只扩展业务信息。
CREATE TABLE ops_training_job (
 id BIGINT PRIMARY KEY, mode VARCHAR(16) NOT NULL, state VARCHAR(20) NOT NULL,
 request_json MEDIUMTEXT NOT NULL, admission_json MEDIUMTEXT,
 pid BIGINT, process_started_at VARCHAR(64), linux_username VARCHAR(160),
 FOREIGN KEY(id) REFERENCES ops_gpu_usage(id)
);
CREATE TABLE ops_training_event (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, job_id BIGINT NOT NULL, actor_id BIGINT NOT NULL,
 event_type VARCHAR(40) NOT NULL, detail VARCHAR(2000), created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(job_id) REFERENCES ops_training_job(id), FOREIGN KEY(actor_id) REFERENCES sys_user(id)
);
CREATE TABLE ops_training_progress (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, job_id BIGINT NOT NULL, payload_json TEXT NOT NULL,
 collected_at DATETIME(6) NOT NULL, FOREIGN KEY(job_id) REFERENCES ops_training_job(id)
);
CREATE TABLE ops_training_policy (
 server_id BIGINT PRIMARY KEY, ram_reserve_mb DOUBLE NOT NULL DEFAULT 2048,
 vram_reserve_mb DOUBLE NOT NULL DEFAULT 512, psi_threshold DOUBLE NOT NULL DEFAULT 10,
 disk_read_mbps DOUBLE, disk_write_mbps DOUBLE, network_mbps DOUBLE,
 FOREIGN KEY(server_id) REFERENCES ops_node(id)
);
CREATE TABLE ops_training_linux_identity (
 server_id BIGINT NOT NULL,user_id BIGINT NOT NULL,linux_username VARCHAR(160) NOT NULL,
 PRIMARY KEY(server_id,user_id), FOREIGN KEY(server_id) REFERENCES ops_node(id),
 FOREIGN KEY(user_id) REFERENCES sys_user(id)
);
CREATE INDEX idx_training_state ON ops_training_job(state,id);
CREATE INDEX idx_training_events ON ops_training_event(job_id,id);
CREATE INDEX idx_training_progress ON ops_training_progress(job_id,collected_at);
