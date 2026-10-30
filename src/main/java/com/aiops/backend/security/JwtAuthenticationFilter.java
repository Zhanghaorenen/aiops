package com.aiops.backend.security;

import java.io.IOException;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.aiops.backend.repository.UserRepository;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserRepository users;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override //override注解用于标识一个方法是重写父类的方法，编译器会检查方法签名是否正确。
    //protected 是访问修饰符，表示该方法可以被同一个包中的类以及子类访问。
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("/api/agent/enroll".equals(request.getRequestURI())
                || "/api/agent/heartbeat".equals(request.getRequestURI())
                || "/api/agent/metrics".equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.parse(authorization.substring(7));
                if (users.findByUsername(claims.getSubject()).filter(user -> "ACTIVE".equals(user.getStatus())).isEmpty()) {
                    SecurityContextHolder.clearContext();
                    chain.doFilter(request, response);
                    return;
                }
                var auth = new UsernamePasswordAuthenticationToken(claims.getSubject(), null,
                        AuthorityUtils.createAuthorityList("ROLE_USER"));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (RuntimeException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
