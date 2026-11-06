package com.aiops.backend.repository;

import com.aiops.backend.entity.OpsAgentEntity;
import com.aiops.backend.mapper.OpsAgentMapper;
import com.aiops.backend.persistence.UtcInstantTypeHandler;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class OpsAgentRepository extends MapperRepository<OpsAgentEntity, OpsAgentMapper> {
  public long countForNodes(java.util.List<Long> nodeIds, String status) {
    if (nodeIds.isEmpty()) return 0;
    var query = new QueryWrapper<OpsAgentEntity>().in("node_id", nodeIds);
    if (status != null) query.eq("status", status);
    return mapper.selectCount(query);
  }

  public OpsAgentRepository(OpsAgentMapper mapper) {
    super(mapper, OpsAgentEntity::getId);
  }

  public Optional<OpsAgentEntity> findByCredentialHash(String credentialHash) {
    return Optional.ofNullable(
        mapper.selectOne(new QueryWrapper<OpsAgentEntity>().eq("credential_hash", credentialHash)));
  }

  public Optional<OpsAgentEntity> lockById(Long id) {
    return Optional.ofNullable(
        mapper.selectOne(new QueryWrapper<OpsAgentEntity>().eq("id", id).last("FOR UPDATE")));
  }

  public Optional<OpsAgentEntity> lockDisconnectedById(Long id, Instant cutoff) {
    return Optional.ofNullable(
        mapper.selectOne(
            new QueryWrapper<OpsAgentEntity>()
                .eq("id", id)
                .eq("status", "ONLINE")
                .lt("last_heartbeat_at", UtcInstantTypeHandler.ceilingToMicros(cutoff))
                .last("FOR UPDATE")));
  }

  public boolean existsByAgentKey(String agentKey) {
    return mapper.selectCount(new QueryWrapper<OpsAgentEntity>().eq("agent_key", agentKey)) > 0;
  }

  public Optional<OpsAgentEntity> findByAgentKey(String agentKey) {
    return Optional.ofNullable(
        mapper.selectOne(new QueryWrapper<OpsAgentEntity>().eq("agent_key", agentKey)));
  }

  public long countByStatus(String status) {
    return mapper.selectCount(new QueryWrapper<OpsAgentEntity>().eq("status", status));
  }

  public List<OpsAgentEntity> findAllByStatusAndLastHeartbeatAtBefore(
      String status, Instant cutoff) {
    return mapper.selectList(
        new QueryWrapper<OpsAgentEntity>()
            .eq("status", status)
            .lt("last_heartbeat_at", UtcInstantTypeHandler.ceilingToMicros(cutoff))
            .orderByAsc("id"));
  }
}
