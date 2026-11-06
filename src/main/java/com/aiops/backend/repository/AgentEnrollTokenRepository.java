package com.aiops.backend.repository;

import com.aiops.backend.entity.AgentEnrollTokenEntity;
import com.aiops.backend.mapper.AgentEnrollTokenMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AgentEnrollTokenRepository
    extends MapperRepository<AgentEnrollTokenEntity, AgentEnrollTokenMapper> {
  public AgentEnrollTokenRepository(AgentEnrollTokenMapper mapper) {
    super(mapper, AgentEnrollTokenEntity::getId);
  }

  @Transactional
  public Optional<AgentEnrollTokenEntity> lockByTokenHash(String hash) {
    return Optional.ofNullable(mapper.lockByTokenHash(hash));
  }

  public Optional<AgentEnrollTokenEntity> findByTokenHash(String tokenHash) {
    return Optional.ofNullable(
        mapper.selectOne(new QueryWrapper<AgentEnrollTokenEntity>().eq("token_hash", tokenHash)));
  }

  public Optional<AgentEnrollTokenEntity> lockById(Long id) {
    return Optional.ofNullable(
        mapper.selectOne(
            new QueryWrapper<AgentEnrollTokenEntity>().eq("id", id).last("FOR UPDATE")));
  }
}
