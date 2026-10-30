package com.aiops.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.stream.Stream;

import com.aiops.backend.entity.UserEntity;
import com.aiops.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RegistrationTests {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder encoder;

    private static final String PASSWORD = "registration-test-password";
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    @Test
    void anonymousUserRegistersAndLogsInButMonitoringStillRequiresJwt() throws Exception {
        mvc.perform(get("/api/servers")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(payload("newuser001", PASSWORD)))
                .andExpect(status().isCreated()).andExpect(content().string(""));
        UserEntity user = users.findByUsername("newuser001").orElseThrow();
        assertNotEquals(PASSWORD, user.getPasswordHash());
        assertTrue(encoder.matches(PASSWORD, user.getPasswordHash()));
        mvc.perform(get("/api/servers")).andExpect(status().isUnauthorized());
        String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(payload("newuser001", PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String jwt = json.readTree(response).get("accessToken").asText();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("newuser001"));
    }

    @Test
    void staleJwtDoesNotBlockPublicRegistration() throws Exception {
        mvc.perform(post("/api/auth/register").header("Authorization", "Bearer invalid-token")
                        .contentType(MediaType.APPLICATION_JSON).content(payload("newuser002", PASSWORD)))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/servers").header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/register")).andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateUsernameReturnsValidationErrorWithoutOverwritingPassword() throws Exception {
        UserEntity original = users.save(new UserEntity("duplicate001", encoder.encode(PASSWORD)));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(payload("duplicate001", "a-different-password")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("用户名已存在"));
        assertEquals(original.getPasswordHash(), users.findByUsername("duplicate001").orElseThrow().getPasswordHash());
    }

    @ParameterizedTest
    @MethodSource("invalidRegistrations")
    void invalidRegistrationReturns400InsteadOfLoginExpired(String username, String password) throws Exception {
        long before = users.count();
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(payload(username, password)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
        assertEquals(before, users.count());
    }

    static Stream<Arguments> invalidRegistrations() {
        return Stream.of(
                Arguments.of("ab", PASSWORD),
                Arguments.of("a".repeat(16), PASSWORD),
                Arguments.of("bad user", PASSWORD),
                Arguments.of("newuser003", "a".repeat(11)),
                Arguments.of("newuser003", "a".repeat(73)),
                Arguments.of("newuser003", "密".repeat(25)),
                Arguments.of("newuser003", "😀".repeat(6)),
                Arguments.of("newuser003", "😀".repeat(19)));
    }

    @Test
    void invalidPasswordResponseExplainsRuleInsteadOfGenericError() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(payload("newuser004", "a".repeat(7))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("12 至 72")));
    }

    @ParameterizedTest
    @MethodSource("validBoundaryPasswords")
    void validUnicodeAndAsciiBoundariesRegisterAndLogIn(String password) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(payload("boundary001", password))).andExpect(status().isCreated());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(payload("boundary001", password))).andExpect(status().isOk());
    }

    static Stream<String> validBoundaryPasswords() {
        return Stream.of("a".repeat(12), "a".repeat(72), "密".repeat(24), "😀".repeat(12));
    }

    private String payload(String username, String password) throws Exception {
        return json.writeValueAsString(java.util.Map.of("username", username, "password", password));
    }
}
