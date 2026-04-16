package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.ApiResponse;
import com.humanitarian.platform.dto.HelpRequestDto;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.service.HelpRequestService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/help-requests")
@CrossOrigin(origins = "*")
public class HelpRequestController {

    @Autowired
    private HelpRequestService helpRequestService;

    // POST /api/help-requests — create new request (any authenticated user)
    @PostMapping
    public ResponseEntity<ApiResponse<HelpRequest>> createRequest(
            @Valid @RequestBody HelpRequestDto dto) {
        HelpRequest request = helpRequestService.createRequest(dto);
        return ResponseEntity.ok(ApiResponse.success("Help request created", request));
    }

    // GET /api/help-requests — get all requests
    @GetMapping
    public ResponseEntity<ApiResponse<List<HelpRequest>>> getAllRequests() {
        List<HelpRequest> requests = helpRequestService.getAllRequests();
        return ResponseEntity.ok(ApiResponse.success("Requests retrieved", requests));
    }

    // GET /api/help-requests/my — get MY requests only
    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<HelpRequest>>> getMyRequests() {
        List<HelpRequest> requests = helpRequestService.getMyRequests();
        return ResponseEntity.ok(ApiResponse.success("My requests retrieved", requests));
    }

    // GET /api/help-requests/pending
    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<HelpRequest>>> getPendingRequests() {
        List<HelpRequest> requests = helpRequestService.getPendingByPriority();
        return ResponseEntity.ok(ApiResponse.success("Pending requests retrieved", requests));
    }

    // GET /api/help-requests/{id}
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<HelpRequest>> getRequestById(@PathVariable Long id) {
        HelpRequest request = helpRequestService.getRequestById(id);
        return ResponseEntity.ok(ApiResponse.success("Request retrieved", request));
    }

    // GET /api/help-requests/status/{status}
    @GetMapping("/status/{status}")
    public ResponseEntity<ApiResponse<List<HelpRequest>>> getByStatus(@PathVariable String status) {
        List<HelpRequest> requests = helpRequestService.getRequestsByStatus(status);
        return ResponseEntity.ok(ApiResponse.success("Requests retrieved", requests));
    }

    // GET /api/help-requests/type/{type}
    @GetMapping("/type/{type}")
    public ResponseEntity<ApiResponse<List<HelpRequest>>> getByType(@PathVariable String type) {
        List<HelpRequest> requests = helpRequestService.getRequestsByType(type);
        return ResponseEntity.ok(ApiResponse.success("Requests retrieved", requests));
    }

    // PUT /api/help-requests/{id}/status
    @PutMapping("/{id}/status")
    public ResponseEntity<ApiResponse<HelpRequest>> updateStatus(
            @PathVariable Long id, @RequestParam String status) {
        HelpRequest request = helpRequestService.updateStatus(id, status);
        return ResponseEntity.ok(ApiResponse.success("Status updated", request));
    }

    // DELETE /api/help-requests/{id}
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<?>> deleteRequest(@PathVariable Long id) {
        helpRequestService.deleteRequest(id);
        return ResponseEntity.ok(ApiResponse.success("Request deleted", null));
    }
}