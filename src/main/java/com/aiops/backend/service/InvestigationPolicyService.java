package com.aiops.backend.service;

import com.aiops.backend.security.ClusterPermission;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvestigationPolicyService {
  private final JdbcTemplate jdbc;
  private final ClusterPermissionService access;
  private final IncidentService incidents;
  public InvestigationPolicyService(JdbcTemplate jdbc,ClusterPermissionService access,IncidentService incidents) {
    this.jdbc=jdbc; this.access=access; this.incidents=incidents;
  }
  public record Policy(boolean enabled,@NotNull @Pattern(regexp="WARNING|CRITICAL") String minSeverity,
      @Min(60) @Max(86400) int cooldownSeconds,@Min(1) @Max(20) int maxPerDay) {}
  public Object get(long server) {
    access.requireNode(server,ClusterPermission.AGENT_MANAGE);
    var rows=jdbc.queryForList("SELECT enabled,min_severity,cooldown_seconds,max_per_day FROM ops_investigation_policy WHERE server_id=?",server);
    return rows.isEmpty()?Map.of("enabled",false,"min_severity","CRITICAL","cooldown_seconds",900,"max_per_day",3):rows.get(0);
  }
  @Transactional
  public void save(long server,Policy policy) {
    access.requireNode(server,ClusterPermission.AGENT_MANAGE);
    jdbc.queryForMap("SELECT id FROM ops_node WHERE id=? FOR UPDATE",server);
    int updated=jdbc.update("UPDATE ops_investigation_policy SET enabled=?,requested_by=?,min_severity=?,cooldown_seconds=?,max_per_day=? WHERE server_id=?",
        policy.enabled(),access.currentUser().getId(),policy.minSeverity(),policy.cooldownSeconds(),policy.maxPerDay(),server);
    if(updated==0) jdbc.update("INSERT INTO ops_investigation_policy(server_id,enabled,requested_by,min_severity,cooldown_seconds,max_per_day) VALUES(?,?,?,?,?,?)",
        server,policy.enabled(),access.currentUser().getId(),policy.minSeverity(),policy.cooldownSeconds(),policy.maxPerDay());
  }
  public List<Long> enabled() {
    return jdbc.queryForList("SELECT server_id FROM ops_investigation_policy WHERE enabled=TRUE",Long.class);
  }
  @Transactional
  public void trigger(long server) {
    var rows=jdbc.queryForList("SELECT p.*,u.username,u.status AS user_status FROM ops_investigation_policy p JOIN sys_user u ON u.id=p.requested_by WHERE p.server_id=? AND p.enabled=TRUE FOR UPDATE",server);
    if(rows.isEmpty()) return;
    var p=rows.get(0);
    if(!"ACTIVE".equals(p.get("user_status"))) return;
    var now=LocalDateTime.now(ZoneOffset.UTC);
    if(p.get("last_triggered_at")!=null && TrainingJobService.instant(p.get("last_triggered_at")).plusSeconds(((Number)p.get("cooldown_seconds")).longValue()).isAfter(Instant.now())) return;
    var previous=SecurityContextHolder.getContext();
    var context=SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new UsernamePasswordAuthenticationToken(p.get("username"),null,AuthorityUtils.createAuthorityList("ROLE_USER")));
    SecurityContextHolder.setContext(context);
    try {
      access.requireNode(server,ClusterPermission.AGENT_MANAGE);
      int used=jdbc.queryForObject("SELECT COUNT(*) FROM ops_investigation_run r JOIN ops_incident i ON i.id=r.incident_id WHERE i.server_id=? AND r.created_at>=?",Integer.class,server,now.toLocalDate().atStartOfDay());
      if(used>=((Number)p.get("max_per_day")).intValue()) return;
      var alerts=jdbc.queryForList("SELECT a.id FROM ops_alert_event a WHERE a.server_id=? AND a.status='FIRING' AND a.last_observed_at>=? AND (a.severity='CRITICAL' OR ?='WARNING') AND NOT EXISTS(SELECT 1 FROM ops_incident i WHERE i.source_alert_id=a.id) ORDER BY a.id DESC LIMIT 1",server,now.minusMinutes(5),p.get("min_severity"));
      if(alerts.isEmpty()) return;
      long id=((Number)alerts.get(0).get("id")).longValue();
      incidents.fromAlert(id,"auto_alert_"+id,false);
      jdbc.update("UPDATE ops_investigation_policy SET last_triggered_at=? WHERE server_id=?",now,server);
    } finally { SecurityContextHolder.setContext(previous); }
  }
}
