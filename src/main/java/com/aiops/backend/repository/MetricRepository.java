package com.aiops.backend.repository;

import com.aiops.backend.entity.MetricEntity;
import com.aiops.backend.mapper.MetricMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class MetricRepository extends MapperRepository<MetricEntity, MetricMapper> {
    public MetricRepository(MetricMapper mapper) {
        super(mapper, MetricEntity::getId);
    }

    public List<MetricEntity> findAllByOrderByCollectedAtDescIdDesc(int limit) {
        return mapper.findRecent(boundedLimit(limit, 200));
    }

    public Optional<MetricEntity> findTopByAgentIdOrderByCollectedAtDescIdDesc(Long agentId) {
        return mapper.findByAgent(agentId, 1).stream().findFirst();
    }

    public List<MetricEntity> findAllByAgentIdOrderByCollectedAtDescIdDesc(Long agentId, int limit) {
        return mapper.findByAgent(agentId, boundedLimit(limit, 200));
    }
}
