package com.aiops.backend.service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
@Component public class KnowledgeServiceCredential {
 private final String secret;
 public KnowledgeServiceCredential(@Value("${aiops.rag.service-key:${AIOPS_RAG_SERVICE_KEY:}}") String secret) {this.secret=secret;}
 public void verify(String supplied) {if(secret.length()<32 || supplied==null || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"需要知识库服务身份");}
}
