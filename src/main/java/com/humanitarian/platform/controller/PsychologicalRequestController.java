package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.ApiResponse;
import com.humanitarian.platform.dto.PsychologicalRequestDto;
import com.humanitarian.platform.model.PsychologicalRequest;
import com.humanitarian.platform.service.PsychologicalRequestService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/psychological-requests")
@CrossOrigin(origins = "*")
public class PsychologicalRequestController {

    @Autowired
    private PsychologicalRequestService psychologicalRequestService;

    // POST /api/psychological-requests — create request
    @PostMapping
    public ResponseEntity<ApiResponse<PsychologicalRequest>> createRequest(
            @Valid @RequestBody PsychologicalRequestDto dto) {
        PsychologicalRequest request = psychologicalRequestService.createRequest(dto);
        return ResponseEntity.ok(ApiResponse.success("Psychological request created", request));
    }

    // GET /api/psychological-requests — get all (admin/psychologist)
    @GetMapping
    @PreAuthorize("hasAnyRole('PSYCHOLOGIST', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<PsychologicalRequest>>> getAllRequests() {
        List<PsychologicalRequest> requests = psychologicalRequestService.getAllRequests();
        return ResponseEntity.ok(ApiResponse.success("Requests retrieved", requests));
    }

    // GET /api/psychological-requests/pending — get unassigned pending requests
    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('PSYCHOLOGIST', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<PsychologicalRequest>>> getPendingRequests() {
        List<PsychologicalRequest> requests = psychologicalRequestService.getPendingRequests();
        return ResponseEntity.ok(ApiResponse.success("Pending requests retrieved", requests));
    }

    // GET /api/psychological-requests/my — get my requests as beneficiary
    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<PsychologicalRequest>>> getMyRequests() {
        List<PsychologicalRequest> requests = psychologicalRequestService.getMyRequests();
        return ResponseEntity.ok(ApiResponse.success("My requests retrieved", requests));
    }

    // GET /api/psychological-requests/assigned — get assigned to me as psychologist
    @GetMapping("/assigned")
    @PreAuthorize("hasRole('PSYCHOLOGIST')")
    public ResponseEntity<ApiResponse<List<PsychologicalRequest>>> getMyAssignedRequests() {
        List<PsychologicalRequest> requests = psychologicalRequestService.getMyAssignedRequests();
        return ResponseEntity.ok(ApiResponse.success("Assigned requests retrieved", requests));
    }

    // GET /api/psychological-requests/{id}
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PsychologicalRequest>> getRequestById(@PathVariable Long id) {
        PsychologicalRequest request = psychologicalRequestService.getRequestById(id);
        return ResponseEntity.ok(ApiResponse.success("Request retrieved", request));
    }

    // PUT /api/psychological-requests/{id}/accept — psychologist accepts request
    @PutMapping("/{id}/accept")
    @PreAuthorize("hasRole('PSYCHOLOGIST')")
    public ResponseEntity<ApiResponse<PsychologicalRequest>> acceptRequest(@PathVariable Long id) {
        PsychologicalRequest request = psychologicalRequestService.acceptRequest(id);
        return ResponseEntity.ok(ApiResponse.success("Request accepted", request));
    }

    // PUT /api/psychological-requests/{id}/status — update status
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('PSYCHOLOGIST', 'ADMIN')")
    public ResponseEntity<ApiResponse<PsychologicalRequest>> updateStatus(
            @PathVariable Long id, @RequestParam String status) {
        PsychologicalRequest request = psychologicalRequestService.updateStatus(id, status);
        return ResponseEntity.ok(ApiResponse.success("Status updated", request));
    }
}