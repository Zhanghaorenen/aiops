package com.aiops.backend.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.aiops.backend.entity.UserEntity;
import com.aiops.backend.repository.UserRepository;
import com.aiops.backend.service.AuthService;

@SpringBootTest
@Transactional
class AuthPersistenceTests {
    private static final String ORIGINAL = "original-test-password-2026";
    private static final String REPLACEMENT = "replacement-test-password-2026";

    @Autowired private AuthService service;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder encoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void loadedUserPasswordMutationRequiresExplicitSave() {
        UserEntity user = createUser();
        String originalHash = passwordHash(user.getUsername());
        UserEntity loaded = users.findByUsername(user.getUsername()).orElseThrow();
        String replacementHash = encoder.encode(REPLACEMENT);

        loaded.setPasswordHash(replacementHash);

        assertEquals(originalHash, passwordHash(user.getUsername()));
        assertEquals(originalHash, users.findById(user.getId()).orElseThrow().getPasswordHash());

        users.save(loaded);

        assertEquals(replacementHash, passwordHash(user.getUsername()));
        assertTrue(encoder.matches(REPLACEMENT, passwordHash(user.getUsername())));
    }

    @Test
    void changePasswordPersistsHashAndNewPasswordWorksOnFreshLookup() {
        UserEntity user = createUser();
        String beforeHash = passwordHash(user.getUsername());
        LocalDateTime createdAt = jdbc.queryForObject(
                "select created_at from sys_user where id = ?", LocalDateTime.class, user.getId());

        service.changePassword(user.getUsername(), ORIGINAL, REPLACEMENT);

        String persistedHash = passwordHash(user.getUsername());
        assertNotEquals(beforeHash, persistedHash);
        assertNotEquals(REPLACEMENT, persistedHash);
        assertTrue(encoder.matches(REPLACEMENT, persistedHash));
        assertFalse(encoder.matches(ORIGINAL, persistedHash));
        assertEquals(persistedHash, users.findByUsername(user.getUsername()).orElseThrow().getPasswordHash());
        assertEquals(createdAt, jdbc.queryForObject(
                "select created_at from sys_user where id = ?", LocalDateTime.class, user.getId()));
        assertNotNull(service.login(user.getUsername(), REPLACEMENT, "127.0.0.8").accessToken());
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from sys_login_log where username = ? and success = true and remote_ip = ?",
                Integer.class, user.getUsername(), "127.0.0.8"));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.login(user.getUsername(), ORIGINAL, "127.0.0.9"));
        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
    }

    @Test
    void rejectedPasswordChangeLeavesStoredHashAndUpdateTimeUnchanged() {
        UserEntity user = createUser();
        String beforeHash = passwordHash(user.getUsername());
        LocalDateTime beforeTime = jdbc.queryForObject(
                "select updated_at from sys_user where id = ?", LocalDateTime.class, user.getId());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.changePassword(user.getUsername(), "wrong-password", REPLACEMENT));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals(beforeHash, passwordHash(user.getUsername()));
        assertEquals(beforeTime, jdbc.queryForObject(
                "select updated_at from sys_user where id = ?", LocalDateTime.class, user.getId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedLoginAuditSurvivesServiceExceptionForWrongUnknownAndDisabledUsers() {
        // No surrounding test transaction: noRollbackFor must really commit the log.
        String username = "auth-persistence-" + UUID.randomUUID();
        String unknown = "unknown-" + UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try {
            tx.executeWithoutResult(status -> users.save(new UserEntity(username, encoder.encode(ORIGINAL))));

            assertUnauthorized(() -> service.login(username, "wrong-password", "127.0.0.10"));
            assertUnauthorized(() -> service.login(unknown, ORIGINAL, "127.0.0.11"));
            tx.executeWithoutResult(status -> jdbc.update(
                    "update sys_user set status = 'DISABLED' where username = ?", username));
            assertUnauthorized(() -> service.login(username, ORIGINAL, "127.0.0.12"));

            assertEquals(2, jdbc.queryForObject(
                    "select count(*) from sys_login_log where username = ? and success = false",
                    Integer.class, username));
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from sys_login_log where username = ? and success = false and remote_ip = ? and created_at is not null",
                    Integer.class, unknown, "127.0.0.11"));
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from sys_login_log where username = ? and success = false and remote_ip = ?",
                    Integer.class, username, "127.0.0.10"));
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from sys_login_log where username = ? and success = false and remote_ip = ?",
                    Integer.class, username, "127.0.0.12"));
            assertEquals(0, jdbc.queryForObject(
                    "select count(*) from sys_login_log where username in (?, ?) and success = true",
                    Integer.class, username, unknown));
        } finally {
            tx.executeWithoutResult(status -> {
                jdbc.update("delete from sys_login_log where username in (?, ?)", username, unknown);
                jdbc.update("delete from sys_user where username = ?", username);
            });
        }
    }

    private UserEntity createUser() {
        return users.save(new UserEntity("auth-persistence-" + UUID.randomUUID(), encoder.encode(ORIGINAL)));
    }

    private String passwordHash(String username) {
        return jdbc.queryForObject("select password_hash from sys_user where username = ?", String.class, username);
    }

    private void assertUnauthorized(org.junit.jupiter.api.function.Executable action) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, action);
        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
    }
}
