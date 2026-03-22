package com.pickple.gateway.infrastructure.config;

import com.pickple.gateway.infrastructure.security.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;

import java.util.List;

@Slf4j
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private final JwtUtil jwtUtil;

    public SecurityConfig(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable) // CSRF 비활성화
                .addFilterAt(jwtAuthenticationFilter(jwtUtil), SecurityWebFiltersOrder.HTTP_BASIC);

        return http.build();
    }

    public WebFilter jwtAuthenticationFilter(JwtUtil jwtUtil) {
        return (exchange, chain) -> {

            // /auth/login/prometheus 경로는 필터를 적용하지 않음
            String path = exchange.getRequest().getURI().getPath();
            if (path.equals("/api/v1/auth/sign-up") || path.equals("/api/v1/auth/sign-in")
                    || path.equals("/actuator/prometheus")) {
                return chain.filter(exchange);
            }

            String tokenValue = jwtUtil.getTokenFromRequest(exchange.getRequest());

            if (StringUtils.hasText(tokenValue)) {
                // JWT 토큰 substring
                // tokenValue = jwtUtil.substringToken(tokenValue);

                if (!jwtUtil.validateToken(tokenValue)) {
                    exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                }

                Claims claims = jwtUtil.getUserInfoFromToken(tokenValue);

                String username = claims.getSubject();

                // 사용자 정보를 새로운 헤더에 추가
                ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
                        .header("X-User-Name", username) // 사용자명 헤더 추가
                        .header("X-User-Roles", String.join(",", claims.get("roles", List.class))) // roles 리스트를 문자열로 변환
                        .build();

                // 수정된 요청으로 필터 체인 계속 처리
                ServerWebExchange modifiedExchange = exchange.mutate().request(modifiedRequest).build();

                return chain.filter(modifiedExchange);
            }
            exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        };
    }
}
