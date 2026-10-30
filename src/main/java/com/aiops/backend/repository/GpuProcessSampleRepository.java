package com.aiops.backend.repository;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.aiops.backend.entity.GpuProcessSampleEntity;
import com.aiops.backend.mapper.GpuProcessSampleMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

@Repository
public class GpuProcessSampleRepository extends MapperRepository<GpuProcessSampleEntity, GpuProcessSampleMapper> {
    public GpuProcessSampleRepository(GpuProcessSampleMapper mapper) {
        super(mapper, GpuProcessSampleEntity::getId);
    }

    public List<GpuProcessSampleEntity> findBySnapshotId(Long snapshotId) {
        return mapper.selectList(new QueryWrapper<GpuProcessSampleEntity>()
                .eq("snapshot_id", snapshotId).orderByAsc("gpu_index", "id"));
    }
}
