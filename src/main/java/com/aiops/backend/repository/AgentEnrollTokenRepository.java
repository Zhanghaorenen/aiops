package com.aiops.backend.repository;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import com.aiops.backend.entity.AgentEnrollTokenEntity;
public interface AgentEnrollTokenRepository extends JpaRepository<AgentEnrollTokenEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from AgentEnrollTokenEntity token where token.tokenHash = :hash")
    Optional<AgentEnrollTokenEntity> lockByTokenHash(@Param("hash") String hash);
    Optional<AgentEnrollTokenEntity> findByTokenHash(String tokenHash);
}
