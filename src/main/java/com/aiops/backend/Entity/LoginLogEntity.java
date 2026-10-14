package com.aiops.backend.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sys_login_log")
public class LoginLogEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 64) private String username;
    @Column(nullable = false) private boolean success;
    @Column(name = "remote_ip", length = 64) private String remoteIp;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected LoginLogEntity() {}
    public LoginLogEntity(String username, boolean success, String remoteIp) {
        this.username = username;
        this.success = success;
        this.remoteIp = remoteIp;
        this.createdAt = Instant.now();
    }
}
