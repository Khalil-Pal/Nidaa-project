package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.ApiResponse;
import com.humanitarian.platform.dto.PsychologistVerificationDto;
import com.humanitarian.platform.dto.PsychologistVerificationResponse;
import com.humanitarian.platform.model.Psychologist;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.repository.PsychologistRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.service.AdminReportService;
import com.humanitarian.platform.service.UserApprovalService;
import com.humanitarian.platform.service.UserService;
import jakarta.validation.Valid;
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
    @Autowired private PsychologistRepository psychologistRepository;
    @Autowired private UserService         userService;
    @Autowired private UserApprovalService userApprovalService;
    @Autowired private AdminReportService  adminReportService;
    @Autowired(required = false) private JavaMailSender mailSender;

    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getPendingUsers() {
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
        return ResponseEntity.ok(ApiResponse.success("Pending applications retrieved", users));
    }

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAllUsers() {
        // Return safe maps instead of raw User entities to avoid lazy-loading
        // serialization failures from @OneToMany collections (notifications etc.)
        // Psychologist rows carry the two flags crisis routing needs, so the
        // administrator can see who still has to be verified (one query, not one per row).
        Map<Long, Psychologist> psychologists = new HashMap<>();
        for (Psychologist p : psychologistRepository.findAll()) {
            if (p.getUser() != null) psychologists.put(p.getUser().getId(), p);
        }
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
            Psychologist p = psychologists.get(u.getId());
            if (p != null) {
                m.put("credentialsVerified", Boolean.TRUE.equals(p.getIsVerified()));
                m.put("onDuty",              Boolean.TRUE.equals(p.getIsOnDuty()));
            }
            return m;
        }).collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success("Users retrieved", users));
    }

    @DeleteMapping("/users/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new com.humanitarian.platform.exception.ResourceNotFoundException("User not found"));
        String name = user.getFullName(), email = user.getEmail();
        userService.deleteAccount(userId);   // anonymise in place (D-2), never a hard delete
        sendEmail(email, "[Nidaa] Your account has been removed",
                "Dear " + name + ",\n\nYour Nidaa account has been closed and your personal details removed.\n\nContact: supp0rtnidaa@yandex.ru\n— Nidaa Team");
        return ResponseEntity.ok(ApiResponse.success("Account deleted", null));
    }

    // All help + psychological requests, joined to their people
    @GetMapping("/requests")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAllRequests() {
        return ResponseEntity.ok(ApiResponse.success("Requests retrieved", adminReportService.allRequests()));
    }

    @PutMapping("/approve/{userId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> approveUser(@PathVariable Long userId) {
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

        return ResponseEntity.ok(ApiResponse.success("Approved", Map.of("userId", userId)));
    }

    /**
     * Records the administrator's check of a psychologist's professional credentials.
     * Separate from approval; together with the psychologist's own duty toggle it
     * decides whether crisis routing may select them.
     */
    @PutMapping("/psychologists/{userId}/verification")
    public ResponseEntity<ApiResponse<PsychologistVerificationResponse>> setPsychologistVerification(
            @PathVariable Long userId, @Valid @RequestBody PsychologistVerificationDto body) {
        PsychologistVerificationResponse result = userApprovalService.setPsychologistVerification(userId, body.getVerified());
        if (result.verified()) {
            userRepository.findById(userId).ifPresent(u -> sendEmail(u.getEmail(),
                    "[Nidaa] Your professional credentials are verified \u2705",
                    "Dear " + u.getFullName() + ",\n\nAn administrator has verified your credentials.\n"
                            + "Go on duty in Settings to receive crisis cases.\n\n\u2014 The Nidaa Team"));
        }
        return ResponseEntity.ok(ApiResponse.success(
                result.verified() ? "Credentials verified" : "Credential verification revoked", result));
    }

    @PutMapping("/reject/{userId}")
    public ResponseEntity<ApiResponse<Void>> rejectUser(@PathVariable Long userId) {
        User user = userApprovalService.reject(userId);
        sendEmail(user.getEmail(), "[Nidaa] Update on your application",
                "Dear " + user.getFullName() + ",\n\nWe are unable to approve your account at this time.\n\n" +
                        "Contact: supp0rtnidaa@yandex.ru\n\u2014 The Nidaa Team");
        return ResponseEntity.ok(ApiResponse.success("Rejected", null));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats() {
        return ResponseEntity.ok(ApiResponse.success("Statistics retrieved", adminReportService.stats()));
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