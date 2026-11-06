package com.aiops.backend.service;

import com.aiops.backend.dto.knowledge.KnowledgeRequests.*;
import com.aiops.backend.entity.*;
import java.util.List;

public interface KnowledgeBaseService {
  KnowledgeBaseEntity create(String user, Base req);

  List<KnowledgeBaseEntity> listMine(String user);

  KnowledgeBaseEntity getOne(String user, Long id);

  KnowledgeBaseEntity update(String user, Long id, Base req);

  void markDeleting(String user, Long id);

  List<KnowledgeDocumentEntity> listDocuments(String user, Long id);

  KnowledgeDocumentEntity registerDocument(String user, Long id, Document req);

  KnowledgeDocumentEntity getDocument(String user, Long kb, Long doc);

  void markDocumentDeleting(String user, Long kb, Long doc);

  KnowledgeDocumentEntity prepareReindex(String user, Long kb, Long doc);

  void updateIndexStatus(Long doc, IndexStatus req);

  void purgeDocument(Long doc);

  void purgeBase(Long kb);
}
