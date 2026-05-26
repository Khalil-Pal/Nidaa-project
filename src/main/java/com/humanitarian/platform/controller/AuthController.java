package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.AuthResponse;
import com.humanitarian.platform.dto.RefreshRequest;
import com.humanitarian.platform.dto.VerifyRegistrationRequest;
import com.humanitarian.platform.dto.LoginRequest;
import com.humanitarian.platform.dto.RegisterRequest;
import com.humanitarian.platform.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")

public class AuthController {

    @Autowired
    private AuthService authService;

    // POST /api/auth/register — Step 1: validate data and send verification code
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    // POST /api/auth/register/verify — Step 2: submit code and create account
    @PostMapping("/register/verify")
    public ResponseEntity<AuthResponse> verifyRegistration(
            @Valid @RequestBody VerifyRegistrationRequest request) {
        AuthResponse response = authService.verifyRegistration(request.getEmail(), request.getCode());
        return ResponseEntity.ok(response);
    }

    // POST /api/auth/login
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    // POST /api/auth/refresh — get new access token using refresh token
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request.getRefreshToken()));
    }

    // POST /api/auth/logout — invalidate refresh token server-side
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.getRefreshToken());
        return ResponseEntity.ok(java.util.Map.of("message", "Logged out successfully."));
    }
}
