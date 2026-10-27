package com.aiops.backend.repository;

import com.aiops.backend.entity.HostMetricSampleEntity;
import com.aiops.backend.mapper.HostMetricSampleMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import com.aiops.backend.persistence.UtcInstantTypeHandler;

@Repository
public class HostMetricSampleRepository extends MapperRepository<HostMetricSampleEntity, HostMetricSampleMapper> {
    public HostMetricSampleRepository(HostMetricSampleMapper mapper) {
        super(mapper, HostMetricSampleEntity::getId);
    }

    public Optional<HostMetricSampleEntity> findTopByNodeIdOrderByCollectedAtDescIdDesc(Long nodeId) {
        return Optional.ofNullable(mapper.findLatest(nodeId));
    }

    public List<HostMetricSampleEntity> findAllByNodeIdAndCollectedAtBetweenOrderByCollectedAtAscIdAsc(
            Long nodeId, Instant from, Instant to, int limit) {
        return mapper.findHistoryAscending(nodeId, UtcInstantTypeHandler.ceilingToMicros(from), to,
                boundedLimit(limit, 1440));
    }

    public List<HostMetricSampleEntity> findAllByNodeIdAndCollectedAtBetweenOrderByCollectedAtDescIdDesc(
            Long nodeId, Instant from, Instant to, int limit) {
        return mapper.findHistoryDescending(nodeId, UtcInstantTypeHandler.ceilingToMicros(from), to,
                boundedLimit(limit, 1440));
    }
}
