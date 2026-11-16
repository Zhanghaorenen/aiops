ALTER TABLE ops_investigation_run ADD COLUMN trace_parent VARCHAR(55);
CREATE INDEX ix_investigation_trace ON ops_investigation_run(trace_parent);
