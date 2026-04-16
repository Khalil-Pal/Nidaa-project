package com.humanitarian.platform.controller;

import com.humanitarian.platform.model.User;
import com.humanitarian.platform.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*")
public class AdminController {

    @Autowired
    private UserRepository userRepository;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    // GET /api/admin/pending — list all users waiting for approval
    @GetMapping("/pending")
    public ResponseEntity<?> getPendingUsers() {
        List<User> pending = userRepository.findByIsActiveFalse();
        return ResponseEntity.ok(pending);
    }

    // PUT /api/admin/approve/{userId} — approve a user application
    @PutMapping("/approve/{userId}")
    public ResponseEntity<Map<String, Object>> approveUser(@PathVariable Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        user.setIsActive(true);
        user.setIsVerified(true);
        userRepository.save(user);

        // Send approval email to the user
        sendApprovalEmail(user);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "User approved successfully");
        response.put("userId", userId);
        response.put("email", user.getEmail());
        return ResponseEntity.ok(response);
    }

    // PUT /api/admin/reject/{userId} — reject and delete application
    @PutMapping("/reject/{userId}")
    public ResponseEntity<Map<String, Object>> rejectUser(@PathVariable Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        sendRejectionEmail(user);
        userRepository.delete(user);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "User rejected and removed");
        return ResponseEntity.ok(response);
    }

    // GET /api/admin/users — list all users
    @GetMapping("/users")
    public ResponseEntity<?> getAllUsers() {
        return ResponseEntity.ok(userRepository.findAll());
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
                            user.getRole().name() + " has been approved.\n\n" +
                            "You can now log in to your account at:\n" +
                            "http://localhost:8081/login.html\n\n" +
                            "Welcome to the Nidaa community!\n\n" +
                            "— The Nidaa Team\n" +
                            "supp0rtnidaa@yandex.ru"
            );
            mailSender.send(msg);
        } catch (Exception e) {
            System.err.println("Could not send approval email: " + e.getMessage());
        }
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
                            "After reviewing your application, we are unable to approve " +
                            "your account at this time.\n\n" +
                            "If you have questions, please contact us at:\n" +
                            "supp0rtnidaa@yandex.ru\n\n" +
                            "— The Nidaa Team"
            );
            mailSender.send(msg);
        } catch (Exception e) {
            System.err.println("Could not send rejection email: " + e.getMessage());
        }
    }
}