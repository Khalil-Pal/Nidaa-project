package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.ApiResponse;
import com.humanitarian.platform.dto.AuthResponse;
import com.humanitarian.platform.dto.RefreshRequest;
import com.humanitarian.platform.dto.VerifyRegistrationRequest;
import com.humanitarian.platform.dto.LoginRequest;
import com.humanitarian.platform.dto.RegisterRequest;
import com.humanitarian.platform.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")

public class AuthController {

    @Autowired
    private AuthService authService;

    // POST /api/auth/register — Step 1: validate data and send verification code
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Map<String, Object>>> register(@Valid @RequestBody RegisterRequest request) {
        Map<String, Object> result = authService.register(request);
        return ResponseEntity.ok(ApiResponse.success((String) result.get("message"),
                Map.of("email", result.get("email"))));
    }

    // POST /api/auth/register/verify — Step 2: submit code and create account
    @PostMapping("/register/verify")
    public ResponseEntity<ApiResponse<AuthResponse>> verifyRegistration(
            @Valid @RequestBody VerifyRegistrationRequest request) {
        AuthResponse response = authService.verifyRegistration(request.getEmail(), request.getCode());
        return ResponseEntity.ok(ApiResponse.success("Registration complete", response));
    }

    // POST /api/auth/login
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request,
                                                           HttpServletRequest http) {
        AuthResponse response = authService.login(request, http.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.success("Signed in", response));
    }

    // POST /api/auth/refresh — get new access token using refresh token
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Session renewed", authService.refresh(request.getRefreshToken())));
    }

    // POST /api/auth/logout — invalidate refresh token server-side
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.success("Logged out successfully.", null));
    }
}
