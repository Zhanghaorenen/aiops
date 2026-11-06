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
    private final ClusterRepository clusters;
    public NodeRepository(NodeMapper mapper, ClusterRepository clusters) {
        super(mapper, NodeEntity::getId);
        this.clusters=clusters;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public NodeEntity save(NodeEntity node){
        // Compatibility for the current single-cluster registration protocol. Stage 4 will bind from the token.
        if(node.getId()==null && node.getClusterId()==null)node.assignCluster(clusters.requireDefault().getId());
        return super.save(node);
    }

    public List<NodeEntity> findByClusterIds(List<Long> ids){
        if(ids.isEmpty())return List.of();
        return mapper.selectList(new QueryWrapper<NodeEntity>().in("cluster_id",ids).orderByAsc("id"));
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
