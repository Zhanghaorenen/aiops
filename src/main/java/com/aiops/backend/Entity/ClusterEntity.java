package com.aiops.backend.entity;
import java.time.Instant;
import com.baomidou.mybatisplus.annotation.*;
import com.aiops.backend.persistence.UtcInstantTypeHandler;
import org.apache.ibatis.type.JdbcType;
@TableName(value="ops_cluster",autoResultMap=true)
public class ClusterEntity {
 @TableId(type=IdType.AUTO) private Long id;
 private String clusterName;
 private String description;
 private String status;
 @TableField(jdbcType=JdbcType.TIMESTAMP,typeHandler=UtcInstantTypeHandler.class)
 private Instant bootstrapCompletedAt;
 private Long bootstrapUserId;
 @TableField(jdbcType=JdbcType.TIMESTAMP,typeHandler=UtcInstantTypeHandler.class)
 private Instant createdAt;
 @TableField(jdbcType=JdbcType.TIMESTAMP,typeHandler=UtcInstantTypeHandler.class)
 private Instant updatedAt;
 protected ClusterEntity() {}
 public ClusterEntity(String name,String description) {this.clusterName=name;this.description=description;this.status="ACTIVE";this.createdAt=Instant.now();this.updatedAt=createdAt;}
 public void completeBootstrap(Long userId) {if(bootstrapCompletedAt!=null)throw new IllegalStateException("Bootstrap already completed");bootstrapUserId=userId;bootstrapCompletedAt=Instant.now();updatedAt=bootstrapCompletedAt;}
 public Long getId() {return id;}
 public String getClusterName() {return clusterName;}
 public String getDescription() {return description;}
 public String getStatus() {return status;}
 public Instant getBootstrapCompletedAt() {return bootstrapCompletedAt;}
 public Long getBootstrapUserId() {return bootstrapUserId;}
 public Instant getCreatedAt() {return createdAt;}
 public Instant getUpdatedAt() {return updatedAt;}
}
