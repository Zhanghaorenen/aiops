package com.aiops.backend.mapper;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.JdbcType;

import com.aiops.backend.entity.GpuSampleEntity;
import com.aiops.backend.persistence.UtcInstantTypeHandler;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface GpuSampleMapper extends BaseMapper<GpuSampleEntity> {
    @Select("SELECT * FROM metric_gpu_sample WHERE node_id = #{nodeId} AND gpu_index = #{gpuIndex} "
            + "AND collected_at BETWEEN #{from,typeHandler=com.aiops.backend.persistence.UtcInstantTypeHandler} "
            + "AND #{to,typeHandler=com.aiops.backend.persistence.UtcInstantTypeHandler} "
            + "ORDER BY collected_at ASC, id ASC LIMIT #{limit}")
    @Results(id = "gpuSample", value = {
            @Result(column = "id", property = "id", id = true),
            @Result(column = "snapshot_id", property = "snapshotId"),
            @Result(column = "node_id", property = "nodeId"),
            @Result(column = "agent_id", property = "agentId"),
            @Result(column = "gpu_index", property = "gpuIndex"),
            @Result(column = "gpu_uuid", property = "gpuUuid"),
            @Result(column = "gpu_name", property = "gpuName"),
            @Result(column = "memory_total_bytes", property = "memoryTotalBytes"),
            @Result(column = "memory_used_bytes", property = "memoryUsedBytes"),
            @Result(column = "memory_free_bytes", property = "memoryFreeBytes"),
            @Result(column = "utilization_percent", property = "utilizationPercent"),
            @Result(column = "memory_utilization_percent", property = "memoryUtilizationPercent"),
            @Result(column = "temperature_c", property = "temperatureC"),
            @Result(column = "power_usage_w", property = "powerUsageW"),
            @Result(column = "fan_speed_percent", property = "fanSpeedPercent"),
            @Result(column = "clock_speed_mhz", property = "clockSpeedMhz"),
            @Result(column = "collected_at", property = "collectedAt", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class),
            @Result(column = "received_at", property = "receivedAt", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class)
    })
    List<GpuSampleEntity> findHistory(@Param("nodeId") Long nodeId, @Param("gpuIndex") int gpuIndex,
            @Param("from") Instant from, @Param("to") Instant to, @Param("limit") int limit);
}
