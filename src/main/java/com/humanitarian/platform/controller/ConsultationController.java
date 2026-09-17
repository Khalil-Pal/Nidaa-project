package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.ApiResponse;
import com.humanitarian.platform.dto.ConsultationFeedbackRequest;
import com.humanitarian.platform.dto.ConsultationRequest;
import com.humanitarian.platform.dto.ConsultationResponse;
import com.humanitarian.platform.service.ConsultationService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consultation records on psychological cases (CS-1), one per session. The URL
 * rule already limits /api/psychological-requests/** to BENEFICIARY, PSYCHOLOGIST
 * and ADMIN; the role gates here are the coarse check, and the service decides
 * who the assigned psychologist and the beneficiary are, answers 404 to anyone
 * outside the case, and keeps the psychologist's private note from every other
 * reader.
 */
@RestController
@RequestMapping("/api/psychological-requests")
public class ConsultationController {

    private final ConsultationService consultationService;

    public ConsultationController(ConsultationService consultationService) {
        this.consultationService = consultationService;
    }

    @PostMapping("/{id}/consultations")
    @PreAuthorize("hasRole('PSYCHOLOGIST')")
    public ResponseEntity<ApiResponse<ConsultationResponse>> record(
            @PathVariable Long id, @Valid @RequestBody ConsultationRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Session recorded", consultationService.record(id, body)));
    }

    @PostMapping("/{id}/consultations/{consultationId}/feedback")
    @PreAuthorize("hasRole('BENEFICIARY')")
    public ResponseEntity<ApiResponse<ConsultationResponse>> submitFeedback(
            @PathVariable Long id, @PathVariable Long consultationId,
            @Valid @RequestBody ConsultationFeedbackRequest body) {
        return ResponseEntity.ok(ApiResponse.success("Thank you for rating this session",
                consultationService.submitFeedback(id, consultationId, body)));
    }

    @GetMapping("/{id}/consultations")
    public ResponseEntity<ApiResponse<List<ConsultationResponse>>> listConsultations(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Sessions", consultationService.listConsultations(id)));
    }
}
