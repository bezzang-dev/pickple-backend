package com.pickple.auth.application.service;

import com.pickple.auth.application.domain.model.User;
import com.pickple.auth.application.security.JwtUtil;
import com.pickple.auth.application.security.UserDetailsImpl;
import com.pickple.auth.exception.AuthErrorCode;
import com.pickple.auth.infrastructure.feign.UserServiceClient;
import com.pickple.auth.presentation.request.LoginRequestDto;
import com.pickple.auth.presentation.request.SignUpRequestDto;
import org.springframework.test.util.ReflectionTestUtils;
import com.pickple.auth.presentation.response.UserResponseDto;
import com.pickple.common_module.exception.CustomException;
import com.pickple.common_module.presentation.dto.ApiResponse;
import feign.FeignException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private UserServiceClient userServiceClient;

    @InjectMocks
    private AuthService authService;

    private User testUser;
    private UserDetailsImpl userDetails;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .username("testuser")
                .password("encodedPassword")
                .roles(List.of("USER"))
                .build();
        userDetails = new UserDetailsImpl(testUser);
    }

    @Test
    @DisplayName("정상 로그인 시 UserResponseDto 반환")
    void login_success() {
        // Given
        LoginRequestDto loginDto = new LoginRequestDto();
        ReflectionTestUtils.setField(loginDto, "username", "testuser");
        ReflectionTestUtils.setField(loginDto, "password", "password123");
        HttpServletResponse response = mock(HttpServletResponse.class);

        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(userDetails);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(jwtUtil.createToken(eq("testuser"), any())).thenReturn("Bearer mockToken");
        doNothing().when(jwtUtil).addJwtToHeader(anyString(), any(HttpServletResponse.class));

        // When
        UserResponseDto result = authService.login(loginDto, response);

        // Then
        assertThat(result.getUsername()).isEqualTo("testuser");
        assertThat(result.getRoles()).contains("USER");
        verify(jwtUtil).addJwtToHeader(eq("Bearer mockToken"), eq(response));
    }

    @Test
    @DisplayName("잘못된 비밀번호로 로그인 시 BadCredentialsException 발생")
    void login_badCredentials() {
        // Given
        LoginRequestDto loginDto = new LoginRequestDto();
        ReflectionTestUtils.setField(loginDto, "username", "testuser");
        ReflectionTestUtils.setField(loginDto, "password", "wrongPassword");
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        // When & Then
        assertThatThrownBy(() -> authService.login(loginDto, response))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("정상 회원가입 시 ApiResponse 반환")
    void signup_success() {
        // Given
        SignUpRequestDto signUpDto = new SignUpRequestDto(
                "newuser", "Password1!", "newnick", "new@test.com", "USER");

        when(passwordEncoder.encode("Password1!")).thenReturn("encodedPassword");

        UserResponseDto userResponseDto = UserResponseDto.builder()
                .username("newuser")
                .roles(List.of("USER"))
                .build();
        ApiResponse<UserResponseDto> apiResponse =
                ApiResponse.success(HttpStatus.OK, "회원가입 성공", userResponseDto);

        when(userServiceClient.registerUser(any(SignUpRequestDto.class))).thenReturn(apiResponse);

        // When
        ApiResponse<UserResponseDto> result = authService.signup(signUpDto);

        // Then
        assertThat(result.getData().getUsername()).isEqualTo("newuser");
        verify(passwordEncoder).encode("Password1!");
        verify(userServiceClient).registerUser(any(SignUpRequestDto.class));
    }

    @Test
    @DisplayName("회원가입 시 UserService FeignException 발생하면 SIGNUP_FAILED CustomException")
    void signup_feignException() {
        // Given
        SignUpRequestDto signUpDto = new SignUpRequestDto(
                "newuser", "Password1!", "newnick", "new@test.com", "USER");

        when(passwordEncoder.encode(anyString())).thenReturn("encodedPassword");
        FeignException feignException = mock(FeignException.class);
        when(feignException.contentUTF8()).thenReturn("{\"message\":\"중복된 아이디\"}");
        when(userServiceClient.registerUser(any(SignUpRequestDto.class))).thenThrow(feignException);

        // When & Then
        assertThatThrownBy(() -> authService.signup(signUpDto))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> {
                    CustomException customEx = (CustomException) ex;
                    assertThat(customEx.getErrorCode()).isEqualTo(AuthErrorCode.SIGNUP_FAILED);
                });
    }
}
