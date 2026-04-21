package com.humanitarian.platform.controller;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    @Autowired private UserRepository userRepository;
    @Autowired private HelpRequestRepository helpRequestRepository;
    @Autowired(required = false) private JavaMailSender mailSender;

    @GetMapping("/pending")
    public ResponseEntity<?> getPendingUsers() {
        return ResponseEntity.ok(userRepository.findByIsActiveFalse());
    }

    @GetMapping("/users")
    public ResponseEntity<?> getAllUsers() {
        return ResponseEntity.ok(userRepository.findAll());
    }

    // GET /api/admin/requests — all help requests with requester info
    @GetMapping("/requests")
    public ResponseEntity<?> getAllRequests() {
        List<HelpRequest> requests = helpRequestRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();

        for (HelpRequest r : requests) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",           r.getId());
            item.put("title",        r.getTitle());
            item.put("helpType",     r.getHelpType());
            item.put("urgencyLevel", r.getUrgencyLevel());
            item.put("status",       r.getStatus());
            item.put("address",      r.getAddress());
            item.put("createdAt",    r.getCreatedAt());
            item.put("description",  r.getDescription());

            // Requester info
            userRepository.findById(r.getBeneficiaryId()).ifPresent(u -> {
                item.put("requesterName",  u.getFullName());
                item.put("requesterEmail", u.getEmail());
                item.put("requesterPhone", u.getPhone() != null ? u.getPhone() : "—");
            });

            // Worker info (volunteer or organization)
            if (r.getAssignedVolunteerId() != null) {
                userRepository.findById(r.getAssignedVolunteerId()).ifPresent(u -> {
                    item.put("workerName",  u.getFullName());
                    item.put("workerRole",  "Volunteer");
                    item.put("workerEmail", u.getEmail());
                });
            } else if (r.getAssignedOrganizationId() != null) {
                userRepository.findById(r.getAssignedOrganizationId()).ifPresent(u -> {
                    item.put("workerName",  u.getFullName());
                    item.put("workerRole",  "Organization");
                    item.put("workerEmail", u.getEmail());
                });
            } else {
                item.put("workerName", "—");
                item.put("workerRole", "—");
            }

            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    @PutMapping("/approve/{userId}")
    public ResponseEntity<Map<String, Object>> approveUser(@PathVariable Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setIsActive(true);
        user.setIsVerified(true);
        userRepository.save(user);
        sendApprovalEmail(user);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("message", "User approved successfully");
        res.put("userId",  userId);
        res.put("email",   user.getEmail());
        return ResponseEntity.ok(res);
    }

    @PutMapping("/reject/{userId}")
    public ResponseEntity<Map<String, Object>> rejectUser(@PathVariable Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        sendRejectionEmail(user);
        userRepository.delete(user);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("message", "User rejected and removed");
        return ResponseEntity.ok(res);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        List<HelpRequest> allRequests = helpRequestRepository.findAll();
        List<User>        allUsers    = userRepository.findAll();
        LocalDateTime     weekAgo     = LocalDateTime.now().minusDays(7);

        Map<String,Long> byStatus = allRequests.stream().collect(
                Collectors.groupingBy(r -> r.getStatus() != null ? r.getStatus() : "UNKNOWN", Collectors.counting()));
        Map<String,Long> byType = allRequests.stream().collect(
                Collectors.groupingBy(r -> r.getHelpType() != null ? r.getHelpType() : "OTHER", Collectors.counting()));
        Map<String,Long> byRegion = allRequests.stream()
                .filter(r -> r.getAddress() != null && !r.getAddress().isBlank())
                .collect(Collectors.groupingBy(r -> r.getAddress().trim(), Collectors.counting()));

        long thisWeek = allRequests.stream()
                .filter(r -> r.getCreatedAt() != null && r.getCreatedAt().isAfter(weekAgo)).count();
        long completedThisWeek = allRequests.stream()
                .filter(r -> "COMPLETED".equals(r.getStatus()) && r.getCompletedAt() != null && r.getCompletedAt().isAfter(weekAgo)).count();
        Map<String,Long> usersByRole = allUsers.stream()
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .collect(Collectors.groupingBy(u -> u.getRole() != null ? u.getRole().name() : "UNKNOWN", Collectors.counting()));
        long activeUsers = allUsers.stream().filter(u -> Boolean.TRUE.equals(u.getIsActive())).count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalRequests",     allRequests.size());
        stats.put("byStatus",          byStatus);
        stats.put("byType",            byType);
        stats.put("byRegion",          byRegion);
        stats.put("thisWeek",          thisWeek);
        stats.put("completedThisWeek", completedThisWeek);
        stats.put("totalUsers",        allUsers.size());
        stats.put("activeUsers",       activeUsers);
        stats.put("usersByRole",       usersByRole);
        return ResponseEntity.ok(stats);
    }

    private void sendApprovalEmail(User user) {
        if (mailSender == null) return;
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setTo(user.getEmail());
            msg.setSubject("[Nidaa] Your application has been approved! ✅");
            msg.setText(
                    "Dear " + user.getFullName() + ",\n\n" +
                            "Great news! Your application to join Nidaa as a " +
                            user.getRole().name().toLowerCase() + " has been approved.\n\n" +
                            "You can now log in to your account.\n\n" +
                            "Welcome to the Nidaa community!\n\n" +
                            "— The Nidaa Team\nsupp0rtnidaa@yandex.ru"
            );
            mailSender.send(msg);
        } catch (Exception e) { System.err.println("Approval email failed: " + e.getMessage()); }
    }

    private void sendRejectionEmail(User user) {
        if (mailSender == null) return;
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setTo(user.getEmail());
            msg.setSubject("[Nidaa] Update on your application");
            msg.setText(
                    "Dear " + user.getFullName() + ",\n\n" +
                            "Thank you for your interest in joining Nidaa.\n\n" +
                            "After reviewing your application, we are unable to approve your account at this time.\n\n" +
                            "For questions: supp0rtnidaa@yandex.ru\n\n" +
                            "— The Nidaa Team"
            );
            mailSender.send(msg);
        } catch (Exception e) { System.err.println("Rejection email failed: " + e.getMessage()); }
    }
}