package com.aiops.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aiops.backend.entity.LoginLogEntity;

public interface LoginLogRepository extends JpaRepository<LoginLogEntity, Long> {}
