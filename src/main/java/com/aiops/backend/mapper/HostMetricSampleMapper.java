package com.aiops.backend.mapper;

import com.aiops.backend.entity.HostMetricSampleEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;
import java.time.Instant;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.JdbcType;
import com.aiops.backend.persistence.UtcInstantTypeHandler;

@Mapper
public interface HostMetricSampleMapper extends BaseMapper<HostMetricSampleEntity> {
    @Select("SELECT * FROM metric_host_sample WHERE node_id = #{nodeId} ORDER BY collected_at DESC, id DESC LIMIT 1")
    @Results(id = "hostMetricSample", value = {
            @Result(column = "id", property = "id", id = true),
            @Result(column = "node_id", property = "nodeId"),
            @Result(column = "agent_id", property = "agentId"),
            @Result(column = "cpu_percent", property = "cpuPercent"),
            @Result(column = "memory_total_bytes", property = "memoryTotalBytes"),
            @Result(column = "memory_used_bytes", property = "memoryUsedBytes"),
            @Result(column = "memory_percent", property = "memoryPercent"),
            @Result(column = "disk_total_bytes", property = "diskTotalBytes"),
            @Result(column = "disk_used_bytes", property = "diskUsedBytes"),
            @Result(column = "disk_percent", property = "diskPercent"),
            @Result(column = "collected_at", property = "collectedAt", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class),
            @Result(column = "received_at", property = "receivedAt", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class)
    })
    HostMetricSampleEntity findLatest(@Param("nodeId") Long nodeId);

    @Select("""
            SELECT * FROM metric_host_sample
            WHERE node_id = #{nodeId}
              AND collected_at BETWEEN #{from,typeHandler=com.aiops.backend.persistence.UtcInstantTypeHandler}
                                   AND #{to,typeHandler=com.aiops.backend.persistence.UtcInstantTypeHandler}
            ORDER BY collected_at ASC, id ASC LIMIT #{limit}
            """)
    @ResultMap("hostMetricSample")
    List<HostMetricSampleEntity> findHistoryAscending(@Param("nodeId") Long nodeId,
            @Param("from") Instant from, @Param("to") Instant to, @Param("limit") int limit);

    @Select("""
            SELECT * FROM metric_host_sample
            WHERE node_id = #{nodeId}
              AND collected_at BETWEEN #{from,typeHandler=com.aiops.backend.persistence.UtcInstantTypeHandler}
                                   AND #{to,typeHandler=com.aiops.backend.persistence.UtcInstantTypeHandler}
            ORDER BY collected_at DESC, id DESC LIMIT #{limit}
            """)
    @ResultMap("hostMetricSample")
    List<HostMetricSampleEntity> findHistoryDescending(@Param("nodeId") Long nodeId,
            @Param("from") Instant from, @Param("to") Instant to, @Param("limit") int limit);

}
