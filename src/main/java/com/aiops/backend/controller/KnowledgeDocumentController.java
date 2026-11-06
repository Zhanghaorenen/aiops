package com.aiops.backend.controller;

import com.aiops.backend.dto.knowledge.KnowledgeRequests.*;
import com.aiops.backend.entity.KnowledgeDocumentEntity;
import com.aiops.backend.service.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/knowledge-bases/{kb}/documents")
public class KnowledgeDocumentController {
  private final KnowledgeBaseService service;
  private final KnowledgeServiceCredential credential;

  public KnowledgeDocumentController(KnowledgeBaseService s, KnowledgeServiceCredential c) {
    service = s;
    credential = c;
  }

  @GetMapping
  public List<KnowledgeDocumentEntity> list(Authentication a, @PathVariable Long kb) {
    return service.listDocuments(a.getName(), kb);
  }

  @GetMapping("/{doc}")
  public KnowledgeDocumentEntity get(
      Authentication a, @PathVariable Long kb, @PathVariable Long doc) {
    return service.getDocument(a.getName(), kb, doc);
  }

  @PostMapping
  public KnowledgeDocumentEntity register(
      Authentication a,
      @PathVariable Long kb,
      @RequestHeader(value = "X-RAG-Service-Key", required = false) String key,
      @Valid @RequestBody Document r) {
    credential.verify(key);
    return service.registerDocument(a.getName(), kb, r);
  }

  @DeleteMapping("/{doc}")
  public void delete(Authentication a, @PathVariable Long kb, @PathVariable Long doc) {
    service.markDocumentDeleting(a.getName(), kb, doc);
  }

  @PostMapping("/{doc}/reindex")
  public KnowledgeDocumentEntity reindex(
      Authentication a,
      @PathVariable Long kb,
      @PathVariable Long doc,
      @RequestHeader(value = "X-RAG-Service-Key", required = false) String key) {
    credential.verify(key);
    return service.prepareReindex(a.getName(), kb, doc);
  }
}
