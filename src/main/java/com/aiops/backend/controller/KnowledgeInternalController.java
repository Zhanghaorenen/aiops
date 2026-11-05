package com.aiops.backend.controller;
import com.aiops.backend.service.*;
import com.aiops.backend.dto.knowledge.KnowledgeRequests.IndexStatus;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
@RestController @RequestMapping("/api/internal/knowledge")
public class KnowledgeInternalController {
 private final KnowledgeBaseService service;private final KnowledgeServiceCredential credential;
 public KnowledgeInternalController(KnowledgeBaseService s,KnowledgeServiceCredential c){service=s;credential=c;}
 @PatchMapping("/documents/{doc}/index-status") public void status(@PathVariable Long doc,@RequestHeader(value="X-RAG-Service-Key",required=false) String key,@Valid @RequestBody IndexStatus r){credential.verify(key);service.updateIndexStatus(doc,r);}
 @DeleteMapping("/documents/{doc}") public void purge(@PathVariable Long doc,@RequestHeader(value="X-RAG-Service-Key",required=false) String key){credential.verify(key);service.purgeDocument(doc);}
 @DeleteMapping("/bases/{kb}") public void purgeBase(@PathVariable Long kb,@RequestHeader(value="X-RAG-Service-Key",required=false) String key){credential.verify(key);service.purgeBase(kb);}
}
