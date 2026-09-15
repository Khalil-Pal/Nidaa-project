package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.ApiResponse;
import com.humanitarian.platform.dto.AssignmentFeedbackRequest;
import com.humanitarian.platform.dto.AssignmentReportRequest;
import com.humanitarian.platform.dto.AssignmentReportResponse;
import com.humanitarian.platform.service.AssignmentReportService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Completion reports on assignments (R-1). Role gates here are the coarse
 * check; the service decides who the assigned volunteer and the beneficiary
 * are, and answers 404 to anyone outside the request.
 */
@RestController
@RequestMapping("/api/assignments")
public class AssignmentReportController {

    private final AssignmentReportService reportService;

    public AssignmentReportController(AssignmentReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/{id}/report")
    @PreAuthorize("hasAnyRole('VOLUNTEER', 'ORGANIZATION')")
    public ResponseEntity<ApiResponse<AssignmentReportResponse>> submitReport(
            @PathVariable Long id, @Valid @RequestBody AssignmentReportRequest body) {
        return ResponseEntity.ok(ApiResponse.success("Delivery recorded", reportService.submitReport(id, body)));
    }

    @PostMapping("/{id}/feedback")
    @PreAuthorize("hasRole('BENEFICIARY')")
    public ResponseEntity<ApiResponse<AssignmentReportResponse>> submitFeedback(
            @PathVariable Long id, @Valid @RequestBody AssignmentFeedbackRequest body) {
        return ResponseEntity.ok(ApiResponse.success("Thank you for rating this help", reportService.submitFeedback(id, body)));
    }

    @GetMapping("/{id}/report")
    public ResponseEntity<ApiResponse<AssignmentReportResponse>> getReport(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Report", reportService.getReport(id)));
    }
}
