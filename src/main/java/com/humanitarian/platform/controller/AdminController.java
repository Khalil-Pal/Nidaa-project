package com.humanitarian.platform.controller;

import com.humanitarian.platform.model.User;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.PsychologicalRequestRepository;
import com.humanitarian.platform.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository                 userRepository;
    @Autowired private HelpRequestRepository          helpRequestRepository;
    @Autowired private PsychologicalRequestRepository psychRepository;
    @Autowired private JdbcTemplate                   jdbc;
    @Autowired(required = false) private JavaMailSender mailSender;

    @GetMapping("/pending")
    public ResponseEntity<?> getPendingUsers() {
        List<Map<String, Object>> users = userRepository.findByIsActiveFalse().stream().map(u -> {
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
    public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        String name = user.getFullName(), email = user.getEmail();
        userRepository.delete(user);
        sendEmail(email, "[Nidaa] Your account has been removed",
                "Dear " + name + ",\n\nYour Nidaa account has been permanently deleted.\n\nContact: supp0rtnidaa@yandex.ru\n— Nidaa Team");
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true); res.put("message", "Deleted");
        return ResponseEntity.ok(res);
    }

    // All help + psychological requests — single SQL join, no lazy-load issues
    @GetMapping("/requests")
    public ResponseEntity<?> getAllRequests() {
        List<Map<String, Object>> result = new ArrayList<>();

        // Help requests
        String sql1 = """
            SELECT hr.request_id AS id,
                   hr.title, hr.help_type, hr.urgency_level, hr.status,
                   hr.address, hr.created_at, hr.description,
                   ru.full_name  AS requester_name,
                   ru.email      AS requester_email,
                   ru.phone      AS requester_phone,
                   COALESCE(vusr.full_name, ousr.full_name) AS worker_name
            FROM help_requests hr
            LEFT JOIN users ru           ON ru.user_id        = hr.beneficiary_id
            LEFT JOIN volunteers v        ON v.volunteer_id    = hr.assigned_volunteer_id
            LEFT JOIN users vusr          ON vusr.user_id      = v.user_id
            LEFT JOIN organizations o     ON o.organization_id = hr.assigned_organization_id
            LEFT JOIN users ousr          ON ousr.user_id      = o.user_id
            ORDER BY hr.created_at DESC""";

        jdbc.queryForList(sql1).forEach(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",             row.get("id"));
            item.put("requestType",    "HELP");
            item.put("title",          row.get("title"));
            item.put("helpType",       row.get("help_type"));
            item.put("urgencyLevel",   row.get("urgency_level"));
            item.put("status",         row.get("status"));
            item.put("address",        row.get("address"));
            item.put("createdAt",      row.get("created_at"));
            item.put("description",    row.get("description"));
            item.put("requesterName",  nvl(row.get("requester_name"),  "—"));
            item.put("requesterEmail", nvl(row.get("requester_email"), "—"));
            item.put("requesterPhone", nvl(row.get("requester_phone"), "—"));
            item.put("workerName",     nvl(row.get("worker_name"),     "Not assigned yet"));
            result.add(item);
        });

        // Psychological requests
        String sql2 = """
            SELECT pr.request_id AS id,
                   pr.category, pr.preferred_format, pr.urgency_level,
                   pr.status, pr.created_at, pr.description,
                   ru.full_name   AS requester_name,
                   ru.email       AS requester_email,
                   ru.phone       AS requester_phone,
                   pusr.full_name AS worker_name
            FROM psychological_requests pr
            LEFT JOIN users ru          ON ru.user_id        = pr.beneficiary_id
            LEFT JOIN psychologists p   ON p.psychologist_id = pr.assigned_psychologist_id
            LEFT JOIN users pusr        ON pusr.user_id      = p.user_id
            ORDER BY pr.created_at DESC""";

        jdbc.queryForList(sql2).forEach(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",             row.get("id"));
            item.put("requestType",    "PSYCHOLOGICAL");
            item.put("title",          nvl(row.get("category"), "Support") + " — " + nvl(row.get("preferred_format"), ""));
            item.put("helpType",       "PSYCHOLOGICAL");
            item.put("urgencyLevel",   row.get("urgency_level"));
            item.put("status",         row.get("status"));
            item.put("address",        null);
            item.put("createdAt",      row.get("created_at"));
            item.put("description",    row.get("description"));
            item.put("requesterName",  nvl(row.get("requester_name"),  "—"));
            item.put("requesterEmail", nvl(row.get("requester_email"), "—"));
            item.put("requesterPhone", nvl(row.get("requester_phone"), "—"));
            item.put("workerName",     nvl(row.get("worker_name"),     "Not assigned yet"));
            result.add(item);
        });

        return ResponseEntity.ok(result);
    }

    @PutMapping("/approve/{userId}")
@Transactional
public ResponseEntity<Map<String, Object>> approveUser(@PathVariable Long userId) {

    User user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

    user.setIsActive(true);
    user.setIsVerified(true);
    userRepository.save(user);

    String role = user.getRole().name();

    if (role.equals("VOLUNTEER")) {

        jdbcTemplate.update("""
            INSERT INTO volunteers (user_id, is_available)
            VALUES (?, true)
            ON CONFLICT (user_id) DO NOTHING
        """, user.getId());

    } else if (role.equals("PSYCHOLOGIST")) {

        jdbcTemplate.update("""
            INSERT INTO psychologists (user_id, is_on_duty)
            VALUES (?, true)
            ON CONFLICT (user_id) DO NOTHING
        """, user.getId());

    } else if (role.equals("ORGANIZATION")) {

        jdbcTemplate.update("""
            INSERT INTO organizations (user_id, official_name, is_verified)
            VALUES (?, ?, false)
            ON CONFLICT (user_id) DO NOTHING
        """, user.getId(), user.getFullName());
    }

    sendEmail(
        user.getEmail(),
        "[Nidaa] Your application has been approved! ✅",
        "Dear " + user.getFullName() + ",\n\nYour application as a "
        + role.toLowerCase()
        + " has been approved.\nYou can now log in at: http://localhost:8081/login.html\n\n"
        + "Welcome to Nidaa!\n— The Nidaa Team"
    );

    Map<String, Object> res = new LinkedHashMap<>();
    res.put("success", true);
    res.put("message", "Approved");
    res.put("userId", userId);

    return ResponseEntity.ok(res);
}

    @PutMapping("/reject/{userId}")
    public ResponseEntity<Map<String, Object>> rejectUser(@PathVariable Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        sendEmail(user.getEmail(), "[Nidaa] Update on your application",
                "Dear " + user.getFullName() + ",\n\nWe are unable to approve your account at this time.\n\n" +
                        "Contact: supp0rtnidaa@yandex.ru\n— The Nidaa Team");
        userRepository.delete(user);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true); res.put("message", "Rejected");
        return ResponseEntity.ok(res);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        var allRequests = helpRequestRepository.findAll();
        var allUsers    = userRepository.findAll();
        var weekAgo     = LocalDateTime.now().minusDays(7);
        Map<String,Long> byStatus = allRequests.stream().collect(Collectors.groupingBy(
                r -> r.getStatus() != null ? r.getStatus() : "UNKNOWN", Collectors.counting()));
        Map<String,Long> byType = allRequests.stream().collect(Collectors.groupingBy(
                r -> r.getHelpType() != null ? r.getHelpType() : "OTHER", Collectors.counting()));
        Map<String,Long> byRegion = allRequests.stream()
                .filter(r -> r.getAddress() != null && !r.getAddress().isBlank())
                .collect(Collectors.groupingBy(r -> r.getAddress().trim(), Collectors.counting()));
        long thisWeek = allRequests.stream()
                .filter(r -> r.getCreatedAt() != null && r.getCreatedAt().isAfter(weekAgo)).count();
        long completedThisWeek = allRequests.stream()
                .filter(r -> "COMPLETED".equals(r.getStatus()) && r.getCompletedAt() != null
                        && r.getCompletedAt().isAfter(weekAgo)).count();
        Map<String,Long> usersByRole = allUsers.stream().filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .collect(Collectors.groupingBy(u -> u.getRole() != null ? u.getRole().name() : "UNKNOWN", Collectors.counting()));
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalRequests",     allRequests.size());
        stats.put("byStatus",          byStatus);
        stats.put("byType",            byType);
        stats.put("byRegion",          byRegion);
        stats.put("thisWeek",          thisWeek);
        stats.put("completedThisWeek", completedThisWeek);
        stats.put("totalUsers",        allUsers.size());
        stats.put("activeUsers",       allUsers.stream().filter(u -> Boolean.TRUE.equals(u.getIsActive())).count());
        stats.put("usersByRole",       usersByRole);
        return ResponseEntity.ok(stats);
    }

    private String nvl(Object v, String fallback) {
        return v != null ? v.toString() : fallback;
    }

    private void sendEmail(String to, String subject, String body) {
        if (mailSender == null) return;
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom("supp0rtnidaa@yandex.ru");
            msg.setTo(to); msg.setSubject(subject); msg.setText(body);
            mailSender.send(msg);
        } catch (Exception e) { System.err.println("Email failed: " + e.getMessage()); }
    }
}