package com.aiops.backend.service;

import com.aiops.backend.security.ClusterPermission;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name = "aiops.checkpoint.enabled", havingValue = "true")
public class InvestigationCheckpointService {
  private final JdbcTemplate jdbc;
  private final ClusterPermissionService access;
  private final ObjectMapper json = new ObjectMapper();

  @Value("${aiops.checkpoint.success-days:7}")
  int successDays;

  @Value("${aiops.checkpoint.failure-days:14}")
  int failureDays;

  public InvestigationCheckpointService(JdbcTemplate jdbc, ClusterPermissionService access) {
    this.jdbc = jdbc;
    this.access = access;
  }

  private void lock(long id, String lease) {
    var rows =
        jdbc.queryForList(
            "SELECT i.server_id FROM ops_investigation_run r JOIN ops_incident i ON"
                + " i.id=r.incident_id WHERE r.id=? AND r.requested_by=? AND r.status='RUNNING' AND"
                + " r.lease_until>? AND r.lease_token=? FOR UPDATE",
            id,
            access.currentUser().getId(),
            LocalDateTime.now(ZoneOffset.UTC),
            lease);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "调查租约已失效");
    access.requireNode(
        ((Number) rows.get(0).get("server_id")).longValue(), ClusterPermission.AI_DIAGNOSE);
  }

  @Transactional
  public Object read(long id, String lease, String checkpoint) {
    lock(id, lease);
    var rows =
        checkpoint == null
            ? jdbc.queryForList(
                "SELECT * FROM ops_graph_checkpoint WHERE run_id=? ORDER BY checkpoint_id DESC"
                    + " LIMIT 1",
                id)
            : jdbc.queryForList(
                "SELECT * FROM ops_graph_checkpoint WHERE run_id=? AND checkpoint_id=?",
                id,
                checkpoint);
    if (rows.isEmpty()) return Map.of();
    var row = rows.get(0);
    var writes =
        jdbc.queryForList(
            "SELECT task_id,channel_name,value_json FROM ops_graph_write WHERE run_id=? AND"
                + " checkpoint_id=? ORDER BY task_path,task_id,write_index",
            id,
            row.get("checkpoint_id"));
    var result = new LinkedHashMap<String, Object>();
    result.put("checkpoint", json.readTree(row.get("checkpoint_json").toString()));
    result.put("metadata", json.readTree(row.get("metadata_json").toString()));
    result.put("parent", row.get("parent_id"));
    result.put(
        "writes",
        writes.stream()
            .map(
                w ->
                    List.of(
                        w.get("task_id"),
                        w.get("channel_name"),
                        json.readTree(w.get("value_json").toString())))
            .toList());
    return result;
  }

  public record Write(String task, String path, int idx, String channel, Object value) {}

  public record Entry(
      String id,
      String parent,
      Map<String, Object> checkpoint,
      Map<String, Object> metadata,
      List<Write> writes) {}

  @Transactional
  public void save(long id, String lease, Entry e) {
    lock(id, lease);
    if (e.id() == null || !e.id().matches("[a-zA-Z0-9-]{1,64}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    if (e.checkpoint() != null) {
      if (!e.id().equals(e.checkpoint().get("id")))
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
      if (jdbc.queryForObject(
              "SELECT COUNT(*) FROM ops_graph_checkpoint WHERE run_id=? AND checkpoint_id=?",
              Integer.class,
              id,
              e.id())
          == 0)
        jdbc.update(
            "INSERT INTO"
                + " ops_graph_checkpoint(run_id,checkpoint_id,parent_id,checkpoint_json,metadata_json)"
                + " VALUES(?,?,?,?,?)",
            id,
            e.id(),
            e.parent(),
            json.writeValueAsString(e.checkpoint()),
            json.writeValueAsString(e.metadata()));
    }
    if (e.writes() != null)
      for (var w : e.writes()) {
        var args = new Object[] {id, e.id(), w.task(), w.idx()};
        if (jdbc.queryForObject(
                "SELECT COUNT(*) FROM ops_graph_write WHERE run_id=? AND checkpoint_id=? AND"
                    + " task_id=? AND write_index=?",
                Integer.class,
                args)
            == 0)
          jdbc.update(
              "INSERT INTO"
                  + " ops_graph_write(run_id,checkpoint_id,task_id,task_path,write_index,channel_name,value_json)"
                  + " VALUES(?,?,?,?,?,?,?)",
              id,
              e.id(),
              w.task(),
              w.path(),
              w.idx(),
              w.channel(),
              json.writeValueAsString(w.value()));
        else if (w.idx() < 0)
          jdbc.update(
              "UPDATE ops_graph_write SET channel_name=?,value_json=? WHERE run_id=? AND"
                  + " checkpoint_id=? AND task_id=? AND write_index=?",
              w.channel(),
              json.writeValueAsString(w.value()),
              id,
              e.id(),
              w.task(),
              w.idx());
      }
  }

  @Scheduled(fixedDelayString = "${aiops.checkpoint.cleanup-ms:3600000}")
  @Transactional
  public void clean() {
    var now = LocalDateTime.now(ZoneOffset.UTC);
    for (String table : List.of("ops_graph_write", "ops_graph_checkpoint"))
      jdbc.update(
          "DELETE FROM "
              + table
              + " WHERE run_id IN (SELECT id FROM ops_investigation_run WHERE (status='SUCCEEDED'"
              + " AND finished_at<?) OR (status='FAILED' AND finished_at<?))",
          now.minusDays(Math.max(1, successDays)),
          now.minusDays(Math.max(1, failureDays)));
  }
}
