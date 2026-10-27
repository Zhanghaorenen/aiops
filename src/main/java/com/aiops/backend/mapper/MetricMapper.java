package com.aiops.backend.mapper;

import com.aiops.backend.entity.MetricEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.JdbcType;
import com.aiops.backend.persistence.UtcInstantTypeHandler;

@Mapper
public interface MetricMapper extends BaseMapper<MetricEntity> {
    @Select("SELECT * FROM metrics ORDER BY collected_at DESC, id DESC LIMIT #{limit}")
    @Results(id = "metric", value = {
            @Result(column = "id", property = "id", id = true),
            @Result(column = "agent_id", property = "agentId"),
            @Result(column = "hostname", property = "hostname"),
            @Result(column = "cpu_usage", property = "cpuUsage"),
            @Result(column = "memory_usage", property = "memoryUsage"),
            @Result(column = "disk_usage", property = "diskUsage"),
            @Result(column = "gpu_usage", property = "gpuUsage"),
            @Result(column = "network_in_mbps", property = "networkInMbps"),
            @Result(column = "network_out_mbps", property = "networkOutMbps"),
            @Result(column = "collected_at", property = "collectedAt", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class),
            @Result(column = "received_at", property = "receivedAt", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class)
    })
    List<MetricEntity> findRecent(@Param("limit") int limit);

    @Select("SELECT * FROM metrics WHERE agent_id = #{agentId} ORDER BY collected_at DESC, id DESC LIMIT #{limit}")
    @ResultMap("metric")
    List<MetricEntity> findByAgent(@Param("agentId") Long agentId, @Param("limit") int limit);

}
