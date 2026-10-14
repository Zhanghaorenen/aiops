package com.aiops.backend.repository;
import java.util.Optional;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.aiops.backend.entity.OpsAgentEntity;
public interface OpsAgentRepository extends JpaRepository<OpsAgentEntity, Long> {
    Optional<OpsAgentEntity> findByCredentialHash(String credentialHash);
    boolean existsByAgentKey(String agentKey);
    Optional<OpsAgentEntity> findByAgentKey(String agentKey);
    long countByStatus(String status);
    List<OpsAgentEntity> findAllByStatusAndLastHeartbeatAtBefore(String status, Instant cutoff);
}
