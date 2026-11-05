package com.aiops.backend.entity;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
@TableName("knowledge_base")
public class KnowledgeBaseEntity {
 @TableId(type=IdType.AUTO) public Long id;
 public Long ownerUserId;
 public String name;
 public String description;
 public String status;
 public LocalDateTime createdAt;
 public LocalDateTime updatedAt;
}
