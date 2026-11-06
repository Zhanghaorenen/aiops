package com.aiops.backend.entity;
import java.time.Instant;
import com.baomidou.mybatisplus.annotation.*;
import com.aiops.backend.persistence.UtcInstantTypeHandler;
import org.apache.ibatis.type.JdbcType;
@TableName(value="ops_cluster_member",autoResultMap=true)
public class ClusterMemberEntity {
 @TableId(type=IdType.AUTO) private Long id;
 private Long clusterId;
 private Long userId;
 private String role;
 private String status;
 @TableField(jdbcType=JdbcType.TIMESTAMP,typeHandler=UtcInstantTypeHandler.class)
 private Instant createdAt;
 @TableField(jdbcType=JdbcType.TIMESTAMP,typeHandler=UtcInstantTypeHandler.class)
 private Instant updatedAt;
 protected ClusterMemberEntity() {}
 public ClusterMemberEntity(Long clusterId,Long userId,String role) {if(!java.util.Set.of("ADMIN","OPERATOR","VIEWER").contains(role))throw new IllegalArgumentException("Invalid cluster role");this.clusterId=clusterId;this.userId=userId;this.role=role;this.status="ACTIVE";this.createdAt=Instant.now();this.updatedAt=createdAt;}
 public Long getId() {return id;}
 public Long getClusterId() {return clusterId;}
 public Long getUserId() {return userId;}
 public String getRole() {return role;}
 public String getStatus() {return status;}
 public Instant getCreatedAt() {return createdAt;}
 public Instant getUpdatedAt() {return updatedAt;}
}
