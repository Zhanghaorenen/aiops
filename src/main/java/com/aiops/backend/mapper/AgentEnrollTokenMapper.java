package com.aiops.backend.mapper;

import com.aiops.backend.entity.AgentEnrollTokenEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.JdbcType;
import com.aiops.backend.persistence.UtcInstantTypeHandler;

@Mapper
public interface AgentEnrollTokenMapper extends BaseMapper<AgentEnrollTokenEntity> {
    @Select("SELECT * FROM ops_agent_enroll_token WHERE token_hash = #{hash} FOR UPDATE")
    @Results(id = "enrollToken", value = {
            @Result(column = "id", property = "id", id = true),
            @Result(column = "token_hash", property = "tokenHash"),
            @Result(column = "expires_at", property = "expiresAt", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class),
            @Result(column = "used_at", property = "usedAt", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class),
            @Result(column = "created_at", property = "createdAt", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class)
    })
    AgentEnrollTokenEntity lockByTokenHash(@Param("hash") String hash);

}
