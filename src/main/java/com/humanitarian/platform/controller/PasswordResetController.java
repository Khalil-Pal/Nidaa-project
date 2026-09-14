package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.ApiResponse;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.service.PasswordResetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")

public class PasswordResetController {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetController.class);

    @Autowired
    private PasswordResetService passwordResetService;

    // POST /api/auth/forgot-password
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@RequestBody Map<String, String> body) {
        // Always the same 200 and message, whether or not the account exists
        // and whether or not the email could be sent (failures are logged).
        try {
            passwordResetService.sendResetCode(body.get("email"));
        } catch (Exception e) {
            log.error("Password reset request failed", e);
        }
        return ResponseEntity.ok(ApiResponse.success(PasswordResetService.RESET_REQUEST_RESPONSE, null));
    }

    // POST /api/auth/verify-reset-code
    @PostMapping("/verify-reset-code")
    public ResponseEntity<ApiResponse<Void>> verifyCode(@RequestBody Map<String, String> body) {
        if (!passwordResetService.verifyCode(body.get("email"), body.get("code"))) {
            throw new BusinessException("Invalid or expired code.");
        }
        return ResponseEntity.ok(ApiResponse.success("Code verified successfully.", null));
    }

    // POST /api/auth/reset-password
    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@RequestBody Map<String, String> body) {
        passwordResetService.resetPassword(body.get("email"), body.get("code"), body.get("newPassword"));
        return ResponseEntity.ok(ApiResponse.success("Password reset successfully. You can now log in.", null));
    }
}