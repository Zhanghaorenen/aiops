package com.aiops.backend.dto.knowledge;
import jakarta.validation.constraints.*;
public final class KnowledgeRequests {
 public record Base(@NotBlank @Size(max=160) String name, @Size(max=1000) String description) {}
 public record Document(@NotBlank @Size(max=255) String originalFilename,
  @NotBlank @Pattern(regexp="application/pdf|application/vnd.openxmlformats-officedocument.wordprocessingml.document|text/plain|text/markdown") String mimeType,
  @NotBlank @Pattern(regexp="[a-f0-9]{64}") String sha256, @Min(1) @Max(10485760) long sizeBytes,
  @NotBlank @Pattern(regexp="[a-f0-9]{32}") String storageKey) {}
 public record IndexStatus(@NotBlank @Pattern(regexp="PROCESSING|READY|FAILED") String status,
  @Min(0) @Max(10000) Integer chunkCount, @Size(max=100) String embeddingVersion,
  @Pattern(regexp="[A-Z_]{1,80}") String errorCode) {}
}
