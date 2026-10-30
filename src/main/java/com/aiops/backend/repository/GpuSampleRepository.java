package com.aiops.backend.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Repository;

import com.aiops.backend.entity.GpuSampleEntity;
import com.aiops.backend.mapper.GpuSampleMapper;
import com.aiops.backend.persistence.UtcInstantTypeHandler;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

@Repository
public class GpuSampleRepository extends MapperRepository<GpuSampleEntity, GpuSampleMapper> {
    public GpuSampleRepository(GpuSampleMapper mapper) {
        super(mapper, GpuSampleEntity::getId);
    }

    public List<GpuSampleEntity> findAllBySnapshotId(Long snapshotId) {
        return mapper.selectList(new QueryWrapper<GpuSampleEntity>()
                .eq("snapshot_id", snapshotId).orderByAsc("gpu_index", "id"));
    }

    public GpuSampleEntity findBySnapshotIdAndGpuIndex(Long snapshotId, int gpuIndex) {
        return mapper.selectOne(new QueryWrapper<GpuSampleEntity>()
                .eq("snapshot_id", snapshotId).eq("gpu_index", gpuIndex).last("LIMIT 1"));
    }

    public List<GpuSampleEntity> findHistory(Long nodeId, int gpuIndex, Instant from, Instant to, int limit) {
        return mapper.findHistory(nodeId, gpuIndex, UtcInstantTypeHandler.ceilingToMicros(from),
                to.truncatedTo(java.time.temporal.ChronoUnit.MICROS), boundedLimit(limit, 1440));
    }
}
