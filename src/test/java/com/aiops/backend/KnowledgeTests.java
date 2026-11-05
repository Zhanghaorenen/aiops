package com.aiops.backend;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.aiops.backend.service.*;
import com.aiops.backend.repository.UserRepository;
import com.aiops.backend.entity.UserEntity;
import com.aiops.backend.dto.knowledge.KnowledgeRequests.*;
@SpringBootTest @Transactional
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class KnowledgeTests {
 @Autowired org.springframework.test.web.servlet.MockMvc mvc;
 @Test void publicAndUserRequestsCannotForgeServiceStatus() throws Exception {
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/knowledge-bases")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/internal/knowledge/documents/1/index-status").contentType("application/json").content("{\"status\":\"READY\",\"chunkCount\":1,\"embeddingVersion\":\"v1\"}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
 }
 @Autowired KnowledgeBaseService service; @Autowired UserRepository users;
 private void user(String name){users.save(new UserEntity(name,"unused-test-hash"));}
 @Test void ownersCannotAccessOrModifyOthersKnowledge(){user("ragAlice");user("ragBob");var kb=service.create("ragAlice",new Base("GPU","manual"));assertEquals(0,service.listMine("ragBob").size());assertThrows(ResponseStatusException.class,()->service.getOne("ragBob",kb.id));assertThrows(ResponseStatusException.class,()->service.markDeleting("ragBob",kb.id));}
 @Test void lifecycleDedupAndDeletingCannotBecomeReady(){user("ragLife");var kb=service.create("ragLife",new Base("GPU",null));var r=new Document("gpu.md","text/markdown","a".repeat(64),10,"b".repeat(32));var d=service.registerDocument("ragLife",kb.id,r);assertThrows(ResponseStatusException.class,()->service.registerDocument("ragLife",kb.id,r));service.updateIndexStatus(d.id,new IndexStatus("PROCESSING",null,"model-v1",null));service.updateIndexStatus(d.id,new IndexStatus("READY",1,"model-v1",null));assertEquals("READY",service.getDocument("ragLife",kb.id,d.id).status);service.markDeleting("ragLife",kb.id);assertThrows(ResponseStatusException.class,()->service.updateIndexStatus(d.id,new IndexStatus("READY",1,"model-v1",null)));service.purgeDocument(d.id);service.purgeBase(kb.id);assertEquals(0,service.listMine("ragLife").size());}
 @Test void independentCredentialIsMandatory(){var credential=new KnowledgeServiceCredential("k".repeat(32));assertThrows(ResponseStatusException.class,()->credential.verify(null));assertThrows(ResponseStatusException.class,()->credential.verify("user-jwt"));assertDoesNotThrow(()->credential.verify("k".repeat(32)));assertThrows(ResponseStatusException.class,()->new KnowledgeServiceCredential("").verify(""));}
}
