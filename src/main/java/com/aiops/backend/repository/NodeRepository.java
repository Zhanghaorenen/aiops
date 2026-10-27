package com.aiops.backend.repository;

import com.aiops.backend.entity.NodeEntity;
import com.aiops.backend.mapper.NodeMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.aiops.backend.persistence.UtcInstantTypeHandler;

@Repository
public class NodeRepository extends MapperRepository<NodeEntity, NodeMapper> {
    public NodeRepository(NodeMapper mapper) {
        super(mapper, NodeEntity::getId);
    }

    public List<NodeEntity> findAllByOrderByIdAsc() {
        return findAll();
    }

    public Optional<NodeEntity> findByNodeKey(String nodeKey) {
        return Optional.ofNullable(mapper.selectOne(new QueryWrapper<NodeEntity>().eq("node_key", nodeKey)));
    }

    public List<NodeEntity> findAllByStatusAndLastSeenAtBefore(String status, Instant cutoff) {
        return mapper.selectList(new QueryWrapper<NodeEntity>()
                .eq("status", status).lt("last_seen_at", UtcInstantTypeHandler.ceilingToMicros(cutoff))
                .orderByAsc("id"));
    }
}
