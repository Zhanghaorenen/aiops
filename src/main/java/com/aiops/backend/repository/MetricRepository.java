package com.aiops.backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.aiops.backend.entity.MetricEntity;

public interface MetricRepository extends JpaRepository<MetricEntity, Long> {

    List<MetricEntity> findAllByOrderByCollectedAtDescIdDesc(Pageable pageable);

    Optional<MetricEntity> findTopByAgentIdOrderByCollectedAtDescIdDesc(Long agentId);
}
