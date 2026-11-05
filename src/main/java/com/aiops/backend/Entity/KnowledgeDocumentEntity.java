package com.aiops.backend.entity;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
@TableName("knowledge_document")
public class KnowledgeDocumentEntity {
 @TableId(type=IdType.AUTO) public Long id;
 public Long kbId;
 public String originalFilename;
 public String mimeType;
 public String sha256;
 public Long sizeBytes;
 public String storageKey;
 public String status;
 public Integer chunkCount;
 public String embeddingVersion;
 public Integer indexVersion;
 public String errorCode;
 public LocalDateTime createdAt;
 public LocalDateTime updatedAt;
}
