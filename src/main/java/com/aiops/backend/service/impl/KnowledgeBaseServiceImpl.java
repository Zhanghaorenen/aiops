package com.aiops.backend.service.impl;

import com.aiops.backend.dto.knowledge.KnowledgeRequests.*;
import com.aiops.backend.entity.*;
import com.aiops.backend.mapper.*;
import com.aiops.backend.repository.UserRepository;
import com.aiops.backend.service.KnowledgeBaseService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.time.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {
  private final KnowledgeBaseMapper bases;
  private final KnowledgeDocumentMapper docs;
  private final UserRepository users;

  public KnowledgeBaseServiceImpl(
      KnowledgeBaseMapper bases, KnowledgeDocumentMapper docs, UserRepository users) {
    this.bases = bases;
    this.docs = docs;
    this.users = users;
  }

  private Long owner(String name) {
    return users
        .findByUsername(name)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED))
        .getId();
  }

  private static ResponseStatusException missing() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库或文档不存在");
  }

  private static void active(KnowledgeBaseEntity kb) {
    if (!"ACTIVE".equals(kb.status))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "知识库正在删除");
  }

  public KnowledgeBaseEntity getOne(String user, Long id) {
    var kb = bases.selectById(id);
    if (kb == null || !kb.ownerUserId.equals(owner(user))) throw missing();
    return kb;
  }

  public List<KnowledgeBaseEntity> listMine(String user) {
    return bases.selectList(
        new QueryWrapper<KnowledgeBaseEntity>().eq("owner_user_id", owner(user)).orderByDesc("id"));
  }

  public KnowledgeBaseEntity create(String user, Base r) {
    var kb = new KnowledgeBaseEntity();
    kb.ownerUserId = owner(user);
    kb.name = r.name().strip();
    kb.description = r.description();
    kb.status = "ACTIVE";
    kb.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    kb.updatedAt = kb.createdAt;
    bases.insert(kb);
    return kb;
  }

  public KnowledgeBaseEntity update(String user, Long id, Base r) {
    var kb = getOne(user, id);
    active(kb);
    kb.name = r.name().strip();
    kb.description = r.description();
    kb.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    bases.updateById(kb);
    return kb;
  }

  public void markDeleting(String user, Long id) {
    getOne(user, id);
    bases.update(
        null, new UpdateWrapper<KnowledgeBaseEntity>().eq("id", id).set("status", "DELETING"));
    docs.update(
        null,
        new UpdateWrapper<KnowledgeDocumentEntity>().eq("kb_id", id).set("status", "DELETING"));
  }

  public List<KnowledgeDocumentEntity> listDocuments(String user, Long kb) {
    getOne(user, kb);
    return docs.selectList(
        new QueryWrapper<KnowledgeDocumentEntity>().eq("kb_id", kb).orderByDesc("id"));
  }

  public KnowledgeDocumentEntity getDocument(String user, Long kb, Long doc) {
    getOne(user, kb);
    var d = docs.selectById(doc);
    if (d == null || !d.kbId.equals(kb)) throw missing();
    return d;
  }

  public KnowledgeDocumentEntity registerDocument(String user, Long id, Document r) {
    // Lock the KB row so upload registration and KB deletion cannot cross.
    var kb =
        bases.selectOne(new QueryWrapper<KnowledgeBaseEntity>().eq("id", id).last("FOR UPDATE"));
    if (kb == null || !kb.ownerUserId.equals(owner(user))) throw missing();
    active(kb);
    if (docs.selectCount(
            new QueryWrapper<KnowledgeDocumentEntity>().eq("kb_id", id).eq("sha256", r.sha256()))
        > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "相同内容的文档已经存在，请删除后重新上传");
    var d = new KnowledgeDocumentEntity();
    d.kbId = id;
    d.originalFilename = r.originalFilename();
    d.mimeType = r.mimeType();
    d.sha256 = r.sha256();
    d.sizeBytes = r.sizeBytes();
    d.storageKey = r.storageKey();
    d.status = "PENDING";
    d.indexVersion = 1;
    d.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    d.updatedAt = d.createdAt;
    docs.insert(d);
    return d;
  }

  public void markDocumentDeleting(String user, Long kb, Long doc) {
    getDocument(user, kb, doc);
    docs.update(
        null, new UpdateWrapper<KnowledgeDocumentEntity>().eq("id", doc).set("status", "DELETING"));
  }

  public KnowledgeDocumentEntity prepareReindex(String user, Long kb, Long doc) {
    var base =
        bases.selectOne(new QueryWrapper<KnowledgeBaseEntity>().eq("id", kb).last("FOR UPDATE"));
    if (base == null || !base.ownerUserId.equals(owner(user))) throw missing();
    active(base);
    var d =
        docs.selectOne(
            new QueryWrapper<KnowledgeDocumentEntity>()
                .eq("id", doc)
                .eq("kb_id", kb)
                .last("FOR UPDATE"));
    if (d == null) throw missing();
    if (!List.of("READY", "FAILED").contains(d.status))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "文档正在处理或删除");
    d.indexVersion++;
    d.status = "PENDING";
    d.chunkCount = 0;
    d.errorCode = null;
    d.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    docs.update(
        null,
        new UpdateWrapper<KnowledgeDocumentEntity>()
            .eq("id", doc)
            .set("index_version", d.indexVersion)
            .set("status", d.status)
            .set("chunk_count", 0)
            .set("error_code", null)
            .set("updated_at", d.updatedAt));
    return d;
  }

  public void updateIndexStatus(Long doc, IndexStatus r) {
    var d = docs.selectById(doc);
    if (d == null) throw missing();
    String expected = "PROCESSING".equals(r.status()) ? "PENDING" : "PROCESSING";
    if ("READY".equals(r.status())
        && (r.chunkCount() == null || r.chunkCount() < 1 || r.embeddingVersion() == null))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少索引信息");
    var u =
        new UpdateWrapper<KnowledgeDocumentEntity>()
            .eq("id", doc)
            .eq("status", expected)
            .set("status", r.status())
            .set("updated_at", LocalDateTime.now(ZoneOffset.UTC))
            .set("chunk_count", r.chunkCount())
            .set("embedding_version", r.embeddingVersion())
            .set("error_code", r.errorCode());
    if (docs.update(null, u) != 1)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "文档状态已变化");
  }

  public void purgeDocument(Long doc) {
    docs.delete(new QueryWrapper<KnowledgeDocumentEntity>().eq("id", doc).eq("status", "DELETING"));
  }

  public void purgeBase(Long kb) {
    if (docs.selectCount(new QueryWrapper<KnowledgeDocumentEntity>().eq("kb_id", kb)) != 0)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "仍有文档待清理");
    bases.delete(new QueryWrapper<KnowledgeBaseEntity>().eq("id", kb).eq("status", "DELETING"));
  }
}
