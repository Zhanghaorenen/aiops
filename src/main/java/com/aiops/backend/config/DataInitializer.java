package com.aiops.backend.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.aiops.backend.entity.UserEntity;
import com.aiops.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class DataInitializer {

    @Bean
    CommandLineRunner initializeAdmin(UserRepository users, PasswordEncoder encoder,
            @Value("${aiops.admin.username:}") String username,
            @Value("${aiops.admin.password:}") String password) {
        return args -> {
            if (username.isBlank() && password.isBlank()) return;
            if (username.isBlank() || password.isBlank() || password.length() < 12 || password.length() > 72) {
                throw new IllegalStateException("Set both AIOPS_ADMIN_USERNAME and AIOPS_ADMIN_PASSWORD; password must be 12-72 characters");
            }
            if (users.findByUsername(username).isEmpty()) {
                users.save(new UserEntity(username, encoder.encode(password)));
            }
        };
    }
}
