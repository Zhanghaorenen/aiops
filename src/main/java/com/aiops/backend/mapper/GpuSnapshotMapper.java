package com.aiops.backend.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.aiops.backend.entity.GpuSnapshotEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface GpuSnapshotMapper extends BaseMapper<GpuSnapshotEntity> {}
