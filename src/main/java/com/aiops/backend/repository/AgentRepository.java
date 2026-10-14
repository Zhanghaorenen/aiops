package com.aiops.backend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aiops.backend.entity.AgentEntity;

public interface AgentRepository extends JpaRepository<AgentEntity, Long> {

    List<AgentEntity> findAllByOrderByIdAsc();
}
