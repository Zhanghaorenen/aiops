package com.aiops.backend.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import com.aiops.backend.entity.UserEntity;
import com.aiops.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class RepositorySaveTests {
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void unchangedSaveSucceedsButSavingDeletedRowStillFails() {
        UserEntity created = users.save(new UserEntity("same-save-" + UUID.randomUUID(), "existing-hash"));
        UserEntity loaded = users.findById(created.getId()).orElseThrow();

        // On MySQL useAffectedRows=true, this successful UPDATE can report zero changed rows.
        assertSame(loaded, users.save(loaded));
        assertSame(loaded, users.save(loaded));
        assertEquals(1, jdbc.queryForObject("select count(*) from sys_user where id = ?", Integer.class,
                loaded.getId()));
        assertEquals("existing-hash", jdbc.queryForObject(
                "select password_hash from sys_user where id = ?", String.class, loaded.getId()));

        users.delete(loaded);
        assertThrows(EmptyResultDataAccessException.class, () -> users.save(loaded));
    }
}
