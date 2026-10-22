package com.aiops.backend.repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.tomcat.util.http.parser.Host;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import com.aiops.backend.entity.HostMetricSampleEntity;
public interface HostMetricSampleRepository extends JpaRepository<HostMetricSampleEntity, Long> {
    Optional<HostMetricSampleEntity> findTopByNodeIdOrderByCollectedAtDescIdDesc(Long nodeId);
    List<HostMetricSampleEntity> findAllByNodeIdAndCollectedAtBetweenOrderByCollectedAtAscIdAsc(
            Long nodeId, Instant from, Instant to, Pageable pageable);
    List<HostMetricSampleEntity> findAllByNodeIdAndCollectedAtBetweenOrderByCollectedAtDescIdDesc(
                Long nodeId,
                Instant from,
                Instant to,
                Pageable pageable
            );
}
