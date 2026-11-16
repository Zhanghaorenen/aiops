ALTER TABLE ops_investigation_run ADD COLUMN recovery_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE ops_investigation_run ADD COLUMN result_trace_id VARCHAR(32);
CREATE INDEX ix_investigation_result_trace ON ops_investigation_run(requested_by,result_trace_id);
CREATE TABLE ops_graph_checkpoint (
 run_id BIGINT NOT NULL, checkpoint_id VARCHAR(64) NOT NULL, parent_id VARCHAR(64),
 checkpoint_json MEDIUMTEXT NOT NULL, metadata_json TEXT NOT NULL,
 PRIMARY KEY(run_id,checkpoint_id), FOREIGN KEY(run_id) REFERENCES ops_investigation_run(id) ON DELETE CASCADE
);
CREATE TABLE ops_graph_write (
 run_id BIGINT NOT NULL, checkpoint_id VARCHAR(64) NOT NULL, task_id VARCHAR(64) NOT NULL,
 task_path VARCHAR(500) NOT NULL, write_index INT NOT NULL, channel_name VARCHAR(160) NOT NULL, value_json MEDIUMTEXT NOT NULL,
 PRIMARY KEY(run_id,checkpoint_id,task_id,write_index),
 FOREIGN KEY(run_id) REFERENCES ops_investigation_run(id) ON DELETE CASCADE
);
