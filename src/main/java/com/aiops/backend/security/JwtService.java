package com.aiops.backend.security;

import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {
    private final SecretKey key;
    private final java.time.Duration expiration;

    public JwtService(@Value("${aiops.jwt.secret}") String secret,
            @Value("${aiops.jwt.expiration:PT2H}") java.time.Duration expiration) {
        try {
            this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("AIOPS_JWT_SECRET must be base64 encoded and at least 256 bits", exception);
        }
        this.expiration = expiration;
    }

    public String issue(Long userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder().subject(username).claim("uid", userId)
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(expiration)))
                .signWith(key).compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public Instant expiresAt() { return Instant.now().plus(expiration); }
}
