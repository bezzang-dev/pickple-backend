package com.pickple.gateway.infrastructure.config;

import com.pickple.gateway.infrastructure.security.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;

import java.util.List;

@Slf4j
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private final JwtUtil jwtUtil;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Value("${gateway.auth.exclude-paths:/api/v1/auth/sign-up,/api/v1/auth/sign-in,/actuator/prometheus}")
    private List<String> excludePaths;

    public SecurityConfig(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .addFilterAt(jwtAuthenticationFilter(jwtUtil), SecurityWebFiltersOrder.HTTP_BASIC);

        return http.build();
    }

    private boolean isExcludedPath(String path) {
        // 정규화: 트레일링 슬래시 제거, 소문자 변환
        String normalizedPath = path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1) : path;
        normalizedPath = normalizedPath.toLowerCase();

        for (String pattern : excludePaths) {
            if (pathMatcher.match(pattern.toLowerCase(), normalizedPath)) {
                return true;
            }
        }
        return false;
    }

    public WebFilter jwtAuthenticationFilter(JwtUtil jwtUtil) {
        return (exchange, chain) -> {
            String path = exchange.getRequest().getURI().getPath();

            if (isExcludedPath(path)) {
                return chain.filter(exchange);
            }

            String tokenValue = jwtUtil.getTokenFromRequest(exchange.getRequest());

            if (StringUtils.hasText(tokenValue)) {
                if (!jwtUtil.validateToken(tokenValue)) {
                    exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                }

                Claims claims = jwtUtil.getUserInfoFromToken(tokenValue);
                String username = claims.getSubject();

                ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
                        .header("X-User-Name", username)
                        .header("X-User-Roles", String.join(",", claims.get("roles", List.class)))
                        .build();

                ServerWebExchange modifiedExchange = exchange.mutate().request(modifiedRequest).build();
                return chain.filter(modifiedExchange);
            }
            exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        };
    }
}
