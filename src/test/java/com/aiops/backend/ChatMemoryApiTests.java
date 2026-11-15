package com.aiops.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.aiops.backend.security.JwtService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties="aiops.ai-service.service-key=test-memory-service-credential-1234567890")
class ChatMemoryApiTests extends ClusterTestSupport {
  @Autowired MockMvc mvc;
  @Autowired JwtService jwt;
  private final ObjectMapper json=new ObjectMapper();
  private String token(String name) { return "Bearer "+jwt.issue(testJdbc.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name),name); }
  @Test void newConsentEndpointIsIndependentAndRequiresExplicitBoolean() throws Exception {
    String auth=token("fixtureAdmin");
    assertEquals(401,mvc.perform(get("/api/ai/chat-memory/settings")).andReturn().getResponse().getStatus());
    assertEquals(400,mvc.perform(put("/api/ai/chat-memory/settings").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus());
    assertEquals(200,mvc.perform(put("/api/ai/chat-memory/settings").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON).content("{\"automatic\":true}")).andReturn().getResponse().getStatus());
    var response=mvc.perform(get("/api/ai/memory/settings").header("Authorization",auth)).andReturn().getResponse();
    assertFalse(json.readTree(response.getContentAsString()).get("automatic").asBoolean());
  }
  @Test void memoryCrudAndVersionValidationUseJwtOwnerOnly() throws Exception {
    String auth=token("fixtureAdmin");
    assertEquals(200,mvc.perform(post("/api/ai/memories").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"ENVIRONMENT\",\"content\":\"我使用 PyTorch\"}")).andReturn().getResponse().getStatus());
    var list=mvc.perform(get("/api/ai/memories").header("Authorization",auth)).andReturn().getResponse();
    String id=json.readTree(list.getContentAsString()).get(0).get("id").asText();
    assertEquals(200,mvc.perform(patch("/api/ai/memories/"+id).header("Authorization",auth).contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"ENVIRONMENT\",\"content\":\"我使用 JAX\"}")).andReturn().getResponse().getStatus());
    String refs=json.writeValueAsString(Map.of("references",List.of(Map.of("id",id,"version",1))));
    assertEquals("[]",mvc.perform(post("/api/ai/memories/verify").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON).content(refs)).andReturn().getResponse().getContentAsString());
    testJdbc.update("INSERT INTO sys_user(username,password_hash,status,created_at,updated_at) VALUES('memory-api-other','test','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    String other=token("memory-api-other");
    assertEquals(404,mvc.perform(get("/api/ai/memories/"+id).header("Authorization",other)).andReturn().getResponse().getStatus());
    assertEquals(404,mvc.perform(delete("/api/ai/memories/"+id).header("Authorization",other)).andReturn().getResponse().getStatus());
    assertEquals(200,mvc.perform(delete("/api/ai/memories/"+id).header("Authorization",auth)).andReturn().getResponse().getStatus());
  }
  @Test void workerInputNeedsServiceCredentialInAdditionToUserJwt() throws Exception {
    String path="/api/ai/chat-memory/tasks/"+UUID.randomUUID(),auth=token("fixtureAdmin"),lease=UUID.randomUUID().toString();
    assertEquals(403,mvc.perform(get(path).header("Authorization",auth).header("X-Memory-Lease",lease)).andReturn().getResponse().getStatus());
    assertEquals(409,mvc.perform(get(path).header("Authorization",auth).header("X-Memory-Lease",lease).header("X-AI-Service-Key","test-memory-service-credential-1234567890")).andReturn().getResponse().getStatus());
  }
}
