package com.pickple.common_module.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * 모든 서비스에서 공통으로 사용하는 사전 인증 필터입니다.
 * Gateway에서 전달된 X-User-Name, X-User-Roles 헤더를 기반으로
 * SecurityContext에 인증 정보를 설정합니다.
 *
 * <p>각 서비스에서 이 필터를 빈으로 등록하여 사용합니다.</p>
 */
@Slf4j
public class CommonPreAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String username = request.getHeader("X-User-Name");
        String roles = request.getHeader("X-User-Roles");

        if (username != null && roles != null) {
            // 빈 문자열 또는 공백만 있는 헤더 값 검증
            if (username.isBlank() || roles.isBlank()) {
                log.warn("빈 인증 헤더 수신. X-User-Name: '{}', X-User-Roles: '{}'", username, roles);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }

            List<SimpleGrantedAuthority> authorities = Arrays.stream(roles.split(","))
                    .map(role -> new SimpleGrantedAuthority(role.trim()))
                    .filter(auth -> !auth.getAuthority().isEmpty())
                    .toList();

            if (authorities.isEmpty()) {
                log.warn("유효한 권한이 없는 요청. username: {}", username);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);

            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }
}
