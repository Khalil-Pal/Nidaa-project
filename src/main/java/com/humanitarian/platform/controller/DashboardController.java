package com.humanitarian.platform.controller;

import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.PsychologicalRequestRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.model.UserRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin(origins = "*")
public class DashboardController {

    @Autowired
    private HelpRequestRepository helpRequestRepository;

    @Autowired
    private PsychologicalRequestRepository psychologicalRequestRepository;

    @Autowired
    private UserRepository userRepository;

    // GET /api/dashboard/stats — real numbers for dashboard
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        long total      = helpRequestRepository.count();
        long pending    = helpRequestRepository.countByStatus("PENDING");
        long completed  = helpRequestRepository.countByStatus("COMPLETED");
        long psych      = psychologicalRequestRepository.count();

        stats.put("total",         total);
        stats.put("pending",       pending);
        stats.put("completed",     completed);
        stats.put("psychological", psych);

        return ResponseEntity.ok(stats);
    }

    // GET /api/dashboard/public-stats — for landing page (no auth needed)
    @GetMapping("/public-stats")
    public ResponseEntity<Map<String, Object>> getPublicStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        long completedRequests = helpRequestRepository.countByStatus("COMPLETED");
        long totalRequests     = helpRequestRepository.count();
        long volunteers        = userRepository.findByRole(UserRole.VOLUNTEER).size();
        long psychologists     = userRepository.findByRole(UserRole.PSYCHOLOGIST).size();

        // Completion rate as percentage
        long completionRate = totalRequests > 0
                ? Math.round((completedRequests * 100.0) / totalRequests)
                : 0;

        stats.put("requestsAnswered",  completedRequests);
        stats.put("activeVolunteers",  volunteers);
        stats.put("psychologists",     psychologists);
        stats.put("completionRate",    completionRate);

        return ResponseEntity.ok(stats);
    }
}