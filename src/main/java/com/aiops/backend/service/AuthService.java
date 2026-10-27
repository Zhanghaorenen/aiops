package com.aiops.backend.service;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.aiops.backend.entity.LoginLogEntity;
import com.aiops.backend.entity.UserEntity;
import com.aiops.backend.repository.LoginLogRepository;
import com.aiops.backend.repository.UserRepository;
import com.aiops.backend.security.JwtService;

@Service
public class AuthService {
    private final UserRepository users;
    private final LoginLogRepository loginLogs;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository users, LoginLogRepository loginLogs,
            PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.loginLogs = loginLogs;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public LoginResult login(String username, String password, String remoteIp) {
        UserEntity user = users.findByUsername(username).orElse(null);
        boolean valid = user != null && "ACTIVE".equals(user.getStatus())
                && passwordEncoder.matches(password, user.getPasswordHash());
        loginLogs.save(new LoginLogEntity(username, valid, remoteIp));
        if (!valid) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        return new LoginResult(jwtService.issue(user.getId(), user.getUsername()), jwtService.expiresAt());
    }

    @Transactional(readOnly = true)
    public UserProfile currentUser(String username) {
        UserEntity user = users.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!"ACTIVE".equals(user.getStatus())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return new UserProfile(user.getId(), user.getUsername(), user.getStatus());
    }

    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword) {
        UserEntity user = users.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前密码不正确");
        }
        if (newPassword == null || newPassword.length() < 12 || newPassword.length() > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码长度须为 12 至 72 个字符");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
    }
    @Transactional
    public void register(String username, String password) {
        if (users.findByUsername(username).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名已存在");
        }
        if (password == null || password.length() < 12 || password.length() > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码长度须为 12 至 72 个字符");
        }
        UserEntity user = new UserEntity(username, passwordEncoder.encode(password));
        users.save(user);
    }
    public record LoginResult(String accessToken, Instant expiresAt) {}
    public record UserProfile(Long id, String username, String status) {}
}
