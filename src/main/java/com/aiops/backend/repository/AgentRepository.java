package com.aiops.backend.repository;

import com.aiops.backend.entity.AgentEntity;
import com.aiops.backend.mapper.AgentMapper;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public class AgentRepository extends MapperRepository<AgentEntity, AgentMapper> {
    public AgentRepository(AgentMapper mapper) {
        super(mapper, AgentEntity::getId);
    }

    public List<AgentEntity> findAllByOrderByIdAsc() {
        return findAll();
    }
}
