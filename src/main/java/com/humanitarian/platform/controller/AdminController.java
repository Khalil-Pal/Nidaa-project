package com.humanitarian.platform.controller;

import com.humanitarian.platform.model.User;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.service.AdminReportService;
import com.humanitarian.platform.service.UserApprovalService;
import com.humanitarian.platform.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * Administrator endpoints. Holds no SQL and no business rules: approval,
 * rejection, reporting and deletion live in services (A-2).
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    @Autowired private UserRepository      userRepository;
    @Autowired private UserService         userService;
    @Autowired private UserApprovalService userApprovalService;
    @Autowired private AdminReportService  adminReportService;
    @Autowired(required = false) private JavaMailSender mailSender;

    @GetMapping("/pending")
    public ResponseEntity<?> getPendingUsers() {
        List<Map<String, Object>> users = userRepository.findByIsActiveFalseAndDeletedAtIsNull().stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",        u.getId());
            m.put("fullName",  u.getFullName());
            m.put("email",     u.getEmail());
            m.put("phone",     u.getPhone());
            m.put("role",      u.getRole() != null ? u.getRole().name() : "");
            m.put("isActive",  u.getIsActive());
            m.put("createdAt", u.getCreatedAt());
            return m;
        }).collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(users);
    }

    @GetMapping("/users")
    public ResponseEntity<?> getAllUsers() {
        // Return safe maps instead of raw User entities to avoid lazy-loading
        // serialization failures from @OneToMany collections (notifications etc.)
        List<Map<String, Object>> users = userRepository.findAll().stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",         u.getId());
            m.put("fullName",   u.getFullName());
            m.put("email",      u.getEmail());
            m.put("phone",      u.getPhone());
            m.put("role",       u.getRole() != null ? u.getRole().name() : "");
            m.put("isActive",   u.getIsActive());
            m.put("isVerified", u.getIsVerified());
            m.put("isLocked",   u.getIsLocked());
            m.put("createdAt",  u.getCreatedAt());
            m.put("lastLogin",  u.getLastLogin());
            return m;
        }).collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(users);
    }

    @DeleteMapping("/users/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new com.humanitarian.platform.exception.ResourceNotFoundException("User not found"));
        String name = user.getFullName(), email = user.getEmail();
        userService.deleteAccount(userId);   // anonymise in place (D-2), never a hard delete
        sendEmail(email, "[Nidaa] Your account has been removed",
                "Dear " + name + ",\n\nYour Nidaa account has been closed and your personal details removed.\n\nContact: supp0rtnidaa@yandex.ru\n— Nidaa Team");
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true); res.put("message", "Deleted");
        return ResponseEntity.ok(res);
    }

    // All help + psychological requests, joined to their people
    @GetMapping("/requests")
    public ResponseEntity<?> getAllRequests() {
        return ResponseEntity.ok(adminReportService.allRequests());
    }

    @PutMapping("/approve/{userId}")
    public ResponseEntity<Map<String, Object>> approveUser(@PathVariable Long userId) {
        User user = userApprovalService.approve(userId);
        String role = user.getRole().name();
        sendEmail(
                user.getEmail(),
                "[Nidaa] Your application has been approved! \u2705",
                "Dear " + user.getFullName() + ",\n\nYour application as a "
                        + role.toLowerCase()
                        + " has been approved.\nYou can now log in at: http://localhost:8081/login.html\n\n"
                        + "Welcome to Nidaa!\n\u2014 The Nidaa Team"
        );

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("message", "Approved");
        res.put("userId", userId);
        return ResponseEntity.ok(res);
    }

    @PutMapping("/reject/{userId}")
    public ResponseEntity<Map<String, Object>> rejectUser(@PathVariable Long userId) {
        User user = userApprovalService.reject(userId);
        sendEmail(user.getEmail(), "[Nidaa] Update on your application",
                "Dear " + user.getFullName() + ",\n\nWe are unable to approve your account at this time.\n\n" +
                        "Contact: supp0rtnidaa@yandex.ru\n\u2014 The Nidaa Team");
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true); res.put("message", "Rejected");
        return ResponseEntity.ok(res);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(adminReportService.stats());
    }

    private void sendEmail(String to, String subject, String body) {
        if (mailSender == null) return;
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom("supp0rtnidaa@yandex.ru");
            msg.setTo(to); msg.setSubject(subject); msg.setText(body);
            mailSender.send(msg);
        } catch (Exception e) { log.error("Email to {} failed: {}", to, e.getMessage()); }
    }
}