package com.aiops.backend.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.aiops.backend.service.AuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) { this.authService = authService; }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        var result = authService.login(request.username(), request.password(), httpRequest.getRemoteAddr());
        return new LoginResponse(result.accessToken(), "Bearer", result.expiresAt());
    }
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
        // No action needed; the client should simply discard the token.
        
    }
    @GetMapping("/me")
    public AuthService.UserProfile me(Authentication authentication) {
        return authService.currentUser(authentication.getName());
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(Authentication authentication, @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(authentication.getName(), request.currentPassword(), request.newPassword());
    }
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public void register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request.username(), request.password());
    }
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record RegisterRequest(
            @NotBlank(message = "请输入用户名") @Pattern(regexp = "[a-zA-Z0-9]{3,15}", message = "用户名须为 3 至 15 位字母或数字") String username,
            @NotBlank(message = "请输入密码") @Size(min = 12, max = 72, message = "密码长度须为 12 至 72 个字符") String password) {}
    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}
    public record LoginResponse(String accessToken, String tokenType, java.time.Instant expiresAt) {}//  record的作用是创建一个不可变的类，通常用于表示数据的结构。
}
