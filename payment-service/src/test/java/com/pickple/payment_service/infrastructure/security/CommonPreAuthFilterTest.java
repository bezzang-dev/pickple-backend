package com.pickple.payment_service.infrastructure.security;

import com.pickple.common_module.infrastructure.security.CommonPreAuthFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CommonPreAuthFilterTest {

    private CommonPreAuthFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        filter = new CommonPreAuthFilter();
        filterChain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ──────────────────────────────────────────────
    // 정상 케이스
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("유효한 X-User-Name, X-User-Roles 헤더가 있으면 SecurityContext에 인증 정보 설정")
    void validHeaders_setsAuthentication() throws Exception {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Name", "testuser");
        request.addHeader("X-User-Roles", "ROLE_USER");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // When
        filter.doFilter(request, response, filterChain);

        // Then
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("testuser");
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("다중 역할(쉼표 구분)이 모두 권한으로 등록된다")
    void multipleRoles_allGranted() throws Exception {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Name", "admin");
        request.addHeader("X-User-Roles", "ROLE_ADMIN,ROLE_USER");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // When
        filter.doFilter(request, response, filterChain);

        // Then
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getAuthorities()).extracting("authority")
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    @DisplayName("X-User-Name, X-User-Roles 헤더가 없으면 필터 통과 (인증 정보 없음)")
    void noHeaders_passesThrough() throws Exception {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // When
        filter.doFilter(request, response, filterChain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    // ──────────────────────────────────────────────
    // 변조/비정상 케이스
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("X-User-Name이 빈 문자열이면 401 반환하고 필터 체인 중단")
    void blankUsername_returns401() throws Exception {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Name", "   ");
        request.addHeader("X-User-Roles", "ROLE_USER");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // When
        filter.doFilter(request, response, filterChain);

        // Then
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        verify(filterChain, never()).doFilter(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("X-User-Roles가 빈 문자열이면 401 반환하고 필터 체인 중단")
    void blankRoles_returns401() throws Exception {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Name", "testuser");
        request.addHeader("X-User-Roles", "");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // When
        filter.doFilter(request, response, filterChain);

        // Then
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("X-User-Roles에 유효한 역할이 없으면(공백만) 401 반환")
    void rolesOnlyWhitespace_returns401() throws Exception {
        // Given — 쉼표로 구분된 공백 토큰 → trim 후 모두 빈 문자열 → authorities 비어 있음
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Name", "testuser");
        request.addHeader("X-User-Roles", " , , ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // When
        filter.doFilter(request, response, filterChain);

        // Then
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("X-User-Name만 있고 X-User-Roles가 없으면 필터 통과 (인증 정보 없음)")
    void onlyUsername_noRoles_passesThrough() throws Exception {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Name", "testuser");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // When
        filter.doFilter(request, response, filterChain);

        // Then — username && roles 모두 non-null 이어야 인증 처리 → 헤더 하나만 있으면 통과
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }
}
