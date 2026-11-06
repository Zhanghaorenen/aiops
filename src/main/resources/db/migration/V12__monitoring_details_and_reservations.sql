-- 增量保留原采样和使用登记；扩展字段允许旧 Agent、旧记录继续读取。
ALTER TABLE metric_host_sample ADD COLUMN details_json MEDIUMTEXT;
ALTER TABLE metric_gpu_sample ADD COLUMN details_json TEXT;
ALTER TABLE metric_gpu_process_sample ADD COLUMN details_json TEXT;
ALTER TABLE ops_gpu_usage ADD COLUMN reservation_kind VARCHAR(16) NOT NULL DEFAULT 'LEGACY';
CREATE INDEX idx_gpu_reservation_window ON ops_gpu_usage(server_id,gpu_index,status,starts_at,expected_end_at);
ALTER TABLE ops_alert_rule ADD COLUMN preset_key VARCHAR(40);
CREATE UNIQUE INDEX uk_alert_preset ON ops_alert_rule(server_id,preset_key);
-- 一条告警最多作为一个自动故障事件的入口，手工关联表继续兼容。
ALTER TABLE ops_incident ADD COLUMN source_alert_id BIGINT;
CREATE UNIQUE INDEX uk_incident_source_alert ON ops_incident(source_alert_id);
ALTER TABLE ops_incident ADD CONSTRAINT fk_incident_source_alert FOREIGN KEY(source_alert_id) REFERENCES ops_alert_event(id);
