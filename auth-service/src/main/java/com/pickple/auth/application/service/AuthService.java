package com.pickple.auth.application.service;

import com.pickple.auth.application.domain.model.User;
import com.pickple.auth.application.security.JwtUtil;
import com.pickple.auth.application.security.UserDetailsImpl;
import com.pickple.auth.exception.AuthErrorCode;
import com.pickple.auth.infrastructure.feign.UserServiceClient;
import com.pickple.auth.presentation.request.LoginRequestDto;
import com.pickple.auth.presentation.request.SignUpRequestDto;
import com.pickple.auth.presentation.response.UserResponseDto;
import com.pickple.common_module.exception.CommonErrorCode;
import com.pickple.common_module.exception.CustomException;
import com.pickple.common_module.presentation.dto.ApiResponse;
import feign.FeignException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;

import static com.pickple.common_module.infrastructure.messaging.EventSerializer.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final UserServiceClient userServiceClient;

    public UserResponseDto login(LoginRequestDto loginRequestDto, HttpServletResponse response) {
        log.info("로그인 시도, username: {}", loginRequestDto.getUsername());
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginRequestDto.getUsername(), loginRequestDto.getPassword())
        );

        String username = ((UserDetailsImpl) authentication.getPrincipal()).getUsername();
        Collection<GrantedAuthority> roles = ((UserDetailsImpl) authentication.getPrincipal()).getAuthorities();

        User user = ((UserDetailsImpl) authentication.getPrincipal()).getUser();

        String jwt = jwtUtil.createToken(username, roles);
        jwtUtil.addJwtToHeader(jwt, response);
        log.info("로그인 성공, username: {}", username);
        return UserResponseDto.fromUser(user);
    }

    // 회원 가입
    public ApiResponse<UserResponseDto> signup(SignUpRequestDto signUpDto) {
        log.info("회원가입 시도, username: {}", signUpDto.getUsername());
        String password = passwordEncoder.encode(signUpDto.getPassword());
        signUpDto.setPassword(password);
        ApiResponse<UserResponseDto> user;
        try {
            user = userServiceClient.registerUser(signUpDto);
            log.info("회원가입 성공, username: {}", signUpDto.getUsername());
        } catch (FeignException ex) {
            String errorMessage = extractMessageFromFeignException(ex);
            log.error("회원가입 실패, username: {}, error: {}", signUpDto.getUsername(), errorMessage);
            throw new CustomException(AuthErrorCode.SIGNUP_FAILED);
        }
        return user;

    }

    private String extractMessageFromFeignException(FeignException ex) {
        try {
            Map<String, Object> errorResponse = objectMapper.readValue(ex.contentUTF8(), Map.class);
            return (String) errorResponse.get("message");
        } catch (Exception e) {
            throw new CustomException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
