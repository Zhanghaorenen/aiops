CREATE TABLE ops_evaluation_run (
 evaluation_run_id VARCHAR(36) NOT NULL, agent_mode VARCHAR(8) NOT NULL,
 dataset_version VARCHAR(64) NOT NULL, model_name VARCHAR(160) NOT NULL, prompt_version VARCHAR(64) NOT NULL,
 retrieval_config VARCHAR(160) NOT NULL, execution_time BIGINT NOT NULL, metrics_json MEDIUMTEXT NOT NULL,
 result_status VARCHAR(32) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(evaluation_run_id,agent_mode)
);
