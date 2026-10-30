package com.aiops.backend.repository;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.aiops.backend.entity.GpuSnapshotEntity;
import com.aiops.backend.mapper.GpuSnapshotMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

@Repository
public class GpuSnapshotRepository extends MapperRepository<GpuSnapshotEntity, GpuSnapshotMapper> {
    public GpuSnapshotRepository(GpuSnapshotMapper mapper) {
        super(mapper, GpuSnapshotEntity::getId);
    }

    public Optional<GpuSnapshotEntity> findLatestByNodeId(Long nodeId) {
        return Optional.ofNullable(mapper.selectOne(new QueryWrapper<GpuSnapshotEntity>()
                .eq("node_id", nodeId).orderByDesc("collected_at", "id").last("LIMIT 1")));
    }
}
