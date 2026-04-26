package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.ApiResponse;
import com.humanitarian.platform.dto.PsychologicalRequestDto;
import com.humanitarian.platform.model.PsychologicalRequest;
import com.humanitarian.platform.service.PsychologicalRequestService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/psychological-requests")
@CrossOrigin(origins = "*")
public class PsychologicalRequestController {

    private final PsychologicalRequestService psychologicalRequestService;

    // Constructor injection — no @Autowired on field
    public PsychologicalRequestController(PsychologicalRequestService psychologicalRequestService) {
        this.psychologicalRequestService = psychologicalRequestService;
    }

    // POST /api/psychological-requests
    @PostMapping
    public ResponseEntity<ApiResponse<PsychologicalRequest>> createRequest(
            @Valid @RequestBody PsychologicalRequestDto dto) {
        return ResponseEntity.ok(ApiResponse.success("Request created",
                psychologicalRequestService.createRequest(dto)));
    }

    // GET /api/psychological-requests
    @GetMapping
    @PreAuthorize("hasAnyRole('PSYCHOLOGIST', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<PsychologicalRequest>>> getAllRequests() {
        return ResponseEntity.ok(ApiResponse.success("Requests retrieved",
                psychologicalRequestService.getAllRequests()));
    }

    // GET /api/psychological-requests/pending
    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('PSYCHOLOGIST', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<PsychologicalRequest>>> getPendingRequests() {
        return ResponseEntity.ok(ApiResponse.success("Pending requests retrieved",
                psychologicalRequestService.getPendingRequests()));
    }

    // GET /api/psychological-requests/my — beneficiary sees their own requests
    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<PsychologicalRequest>>> getMyRequests() {
        return ResponseEntity.ok(ApiResponse.success("My requests retrieved",
                psychologicalRequestService.getMyRequests()));
    }

    // GET /api/psychological-requests/my-assigned — psychologist sees sessions they are working on
    @GetMapping("/my-assigned")
    @PreAuthorize("hasAnyRole('PSYCHOLOGIST', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<PsychologicalRequest>>> getMyAssignedRequests() {
        return ResponseEntity.ok(ApiResponse.success("Assigned requests retrieved",
                psychologicalRequestService.getMyAssignedRequests()));
    }

    // GET /api/psychological-requests/{id}
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PsychologicalRequest>> getRequestById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Request retrieved",
                psychologicalRequestService.getRequestById(id)));
    }

    // PUT /api/psychological-requests/{id}/accept
    @PutMapping("/{id}/accept")
    @PreAuthorize("hasRole('PSYCHOLOGIST')")
    public ResponseEntity<ApiResponse<PsychologicalRequest>> acceptRequest(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Request accepted",
                psychologicalRequestService.acceptRequest(id)));
    }

    // PUT /api/psychological-requests/{id}/status
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('PSYCHOLOGIST', 'ADMIN')")
    public ResponseEntity<ApiResponse<PsychologicalRequest>> updateStatus(
            @PathVariable Long id, @RequestParam String status) {
        return ResponseEntity.ok(ApiResponse.success("Status updated",
                psychologicalRequestService.updateStatus(id, status)));
    }
}