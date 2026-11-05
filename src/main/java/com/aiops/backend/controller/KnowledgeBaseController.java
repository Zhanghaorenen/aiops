package com.aiops.backend.controller;
import com.aiops.backend.service.*;
import com.aiops.backend.entity.KnowledgeBaseEntity;
import com.aiops.backend.dto.knowledge.KnowledgeRequests.Base;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import jakarta.validation.Valid;
import java.util.List;
@RestController @RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {
 private final KnowledgeBaseService service;
 public KnowledgeBaseController(KnowledgeBaseService service){this.service=service;}
 @GetMapping public List<KnowledgeBaseEntity> list(Authentication a){return service.listMine(a.getName());}
 @PostMapping public KnowledgeBaseEntity create(Authentication a,@Valid @RequestBody Base r){return service.create(a.getName(),r);}
 @GetMapping("/{id}") public KnowledgeBaseEntity get(Authentication a,@PathVariable Long id){return service.getOne(a.getName(),id);}
 @PutMapping("/{id}") public KnowledgeBaseEntity update(Authentication a,@PathVariable Long id,@Valid @RequestBody Base r){return service.update(a.getName(),id,r);}
 @DeleteMapping("/{id}") public void delete(Authentication a,@PathVariable Long id){service.markDeleting(a.getName(),id);}
}
