package com.aiops.backend.repository;

import java.util.List;
import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aiops.backend.entity.NodeEntity;

public interface NodeRepository extends JpaRepository<NodeEntity, Long> {
    List<NodeEntity> findAllByOrderByIdAsc();
    java.util.Optional<NodeEntity> findByNodeKey(String nodeKey);
    List<NodeEntity> findAllByStatusAndLastSeenAtBefore(String status, Instant cutoff);
}
