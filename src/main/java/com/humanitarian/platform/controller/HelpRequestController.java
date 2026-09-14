package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.ApiResponse;
import com.humanitarian.platform.dto.ContactInfoResponse;
import com.humanitarian.platform.dto.HelpRequestDto;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.service.ContactInfoService;
import com.humanitarian.platform.service.HelpRequestService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;

import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;

@RestController
@RequestMapping("/api/help-requests")
public class HelpRequestController {

    @Autowired private HelpRequestService helpRequestService;
    @Autowired private ContactInfoService contactInfoService;

    @PostMapping
    public ResponseEntity<ApiResponse<HelpRequest>> createRequest(
            @Valid @RequestBody HelpRequestDto dto) {
        return ResponseEntity.ok(ApiResponse.success("Request created",
                helpRequestService.createRequest(dto)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<?>> getAllRequests(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        if (size > 0) {
            Page<HelpRequest> paged = helpRequestService.getAllRequests(page, size);
            return ResponseEntity.ok(ApiResponse.success("Requests retrieved", paged));
        }
        return ResponseEntity.ok(ApiResponse.success("Requests retrieved",
                helpRequestService.getAllRequests()));
    }

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<HelpRequest>>> getMyRequests() {
        return ResponseEntity.ok(ApiResponse.success("My requests",
                helpRequestService.getMyRequests()));
    }

    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<Page<HelpRequest>>> getPendingRequests(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success("Pending requests",
                helpRequestService.getPendingByPriority(page, size)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<HelpRequest>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Request retrieved",
                helpRequestService.getRequestById(id)));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'BENEFICIARY', 'VOLUNTEER', 'ORGANIZATION')")
    @Transactional
    public ResponseEntity<ApiResponse<HelpRequest>> updateStatus(
            @PathVariable Long id, @RequestParam String status) {
        return ResponseEntity.ok(ApiResponse.success("Status updated",
                helpRequestService.updateStatus(id, status)));
    }

    @PutMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('VOLUNTEER', 'ORGANIZATION')")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> assignToMe(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Request assigned",
                helpRequestService.assignToMe(id)));
    }

    @GetMapping("/{id}/contact")
    @PreAuthorize("hasAnyRole('BENEFICIARY', 'VOLUNTEER', 'ORGANIZATION')")
    public ResponseEntity<ApiResponse<ContactInfoResponse>> getContact(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Contact retrieved",
                contactInfoService.getHelpRequestContact(id)));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<ApiResponse<?>> deleteRequest(@PathVariable Long id) {
        helpRequestService.deleteRequest(id);
        return ResponseEntity.ok(ApiResponse.success("Request deleted", null));
    }
}
