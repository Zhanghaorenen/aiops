package com.aiops.backend.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.aiops.backend.entity.GpuProcessSampleEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface GpuProcessSampleMapper extends BaseMapper<GpuProcessSampleEntity> {}
