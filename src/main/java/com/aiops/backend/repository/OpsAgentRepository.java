package com.aiops.backend.repository;

import com.aiops.backend.entity.OpsAgentEntity;
import com.aiops.backend.mapper.OpsAgentMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.aiops.backend.persistence.UtcInstantTypeHandler;

@Repository
public class OpsAgentRepository extends MapperRepository<OpsAgentEntity, OpsAgentMapper> {
    public OpsAgentRepository(OpsAgentMapper mapper) {
        super(mapper, OpsAgentEntity::getId);
    }

    public Optional<OpsAgentEntity> findByCredentialHash(String credentialHash) {
        return Optional.ofNullable(mapper.selectOne(
                new QueryWrapper<OpsAgentEntity>().eq("credential_hash", credentialHash)));
    }

    public boolean existsByAgentKey(String agentKey) {
        return mapper.selectCount(new QueryWrapper<OpsAgentEntity>().eq("agent_key", agentKey)) > 0;
    }

    public Optional<OpsAgentEntity> findByAgentKey(String agentKey) {
        return Optional.ofNullable(mapper.selectOne(
                new QueryWrapper<OpsAgentEntity>().eq("agent_key", agentKey)));
    }

    public long countByStatus(String status) {
        return mapper.selectCount(new QueryWrapper<OpsAgentEntity>().eq("status", status));
    }

    public List<OpsAgentEntity> findAllByStatusAndLastHeartbeatAtBefore(String status, Instant cutoff) {
        return mapper.selectList(new QueryWrapper<OpsAgentEntity>()
                .eq("status", status).lt("last_heartbeat_at", UtcInstantTypeHandler.ceilingToMicros(cutoff))
                .orderByAsc("id"));
    }
}
