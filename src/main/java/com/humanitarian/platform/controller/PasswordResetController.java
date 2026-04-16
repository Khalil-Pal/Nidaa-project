package com.humanitarian.platform.controller;

import com.humanitarian.platform.service.PasswordResetService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class PasswordResetController {

    @Autowired
    private PasswordResetService passwordResetService;

    // POST /api/auth/forgot-password
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, Object>> forgotPassword(@RequestBody Map<String, String> body) {
        Map<String, Object> res = new LinkedHashMap<>();
        try {
            String msg = passwordResetService.sendResetCode(body.get("email"));
            res.put("success", true);
            res.put("message", msg);
        } catch (Exception e) {
            res.put("success", false);
            res.put("message", e.getMessage());
        }
        return ResponseEntity.ok(res);
    }

    // POST /api/auth/verify-reset-code
    @PostMapping("/verify-reset-code")
    public ResponseEntity<Map<String, Object>> verifyCode(@RequestBody Map<String, String> body) {
        Map<String, Object> res = new LinkedHashMap<>();
        boolean valid = passwordResetService.verifyCode(body.get("email"), body.get("code"));
        res.put("success", valid);
        res.put("message", valid ? "Code verified successfully." : "Invalid or expired code.");
        return ResponseEntity.ok(res);
    }

    // POST /api/auth/reset-password
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(@RequestBody Map<String, String> body) {
        Map<String, Object> res = new LinkedHashMap<>();
        try {
            passwordResetService.resetPassword(body.get("email"), body.get("code"), body.get("newPassword"));
            res.put("success", true);
            res.put("message", "Password reset successfully. You can now log in.");
        } catch (Exception e) {
            res.put("success", false);
            res.put("message", e.getMessage());
        }
        return ResponseEntity.ok(res);
    }
}