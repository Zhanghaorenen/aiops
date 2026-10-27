package com.aiops.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.aiops.backend.entity.UserEntity;
import com.aiops.backend.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    @Transactional
    void loginIssuesJwtAndProtectsUserEndpoints() throws Exception {
        users.saveAndFlush(new UserEntity("auth-test", passwordEncoder.encode("a-long-test-password")));

        mvc.perform(get("/api/servers")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/agent/heartbeat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentVersion\":\"0.1.0\"}"))
                .andExpect(status().isUnauthorized());
        String response = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"auth-test","password":"a-long-test-password"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String token = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response).get("accessToken").asText();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/servers").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());

        String enrollmentTokenResponse = mvc.perform(post("/api/agent/enrollment-tokens")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String enrollmentToken = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(enrollmentTokenResponse).get("token").asText();
        String enrollmentResponse = mvc.perform(post("/api/agent/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enrollmentToken":"%s","nodeKey":"test-node","displayName":"Test Node","hostname":"test-host","ip":"127.0.0.1","osName":"Windows","osVersion":"11","architecture":"amd64","cpuCores":8,"memoryBytes":17179869184,"agentKey":"test-agent","agentVersion":"0.1.0"}
                                """.formatted(enrollmentToken)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String agentCredential = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(enrollmentResponse).get("credential").asText();
        long nodeId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(enrollmentResponse).get("nodeId").asLong();

        mvc.perform(post("/api/agent/heartbeat")
                        .header("Authorization", "Bearer " + agentCredential)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentVersion\":\"0.1.1\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/agent/metrics")
                        .header("Authorization", "Bearer " + agentCredential)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cpuPercent":23.5,"memoryTotalBytes":1000,"memoryUsedBytes":500,"diskTotalBytes":2000,"diskUsedBytes":1000}
                                """))
                .andExpect(status().isAccepted());
        mvc.perform(get("/api/servers").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/servers/" + nodeId + "/metrics/latest").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        String nextTokenResponse = mvc.perform(post("/api/agent/enrollment-tokens")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String nextEnrollmentToken = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(nextTokenResponse).get("token").asText();
        String rotatedResponse = mvc.perform(post("/api/agent/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enrollmentToken":"%s","nodeKey":"test-node","displayName":"Test Node","hostname":"test-host","agentKey":"test-agent","agentVersion":"0.1.2"}
                                """.formatted(nextEnrollmentToken)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String rotatedCredential = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(rotatedResponse).get("credential").asText();
        mvc.perform(post("/api/agent/heartbeat").header("Authorization", "Bearer " + agentCredential))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/agent/heartbeat").header("Authorization", "Bearer " + rotatedCredential))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/agent/enroll").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enrollmentToken":"%s","nodeKey":"again","displayName":"Again","hostname":"again","agentKey":"again","agentVersion":"0.1.0"}
                                """.formatted(enrollmentToken)))
                .andExpect(status().isUnauthorized());
    }
}
