package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.ApiResponse;
import com.humanitarian.platform.dto.HelpRequestDto;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.service.HelpRequestService;
import com.humanitarian.platform.service.UserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;

import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;

@RestController
@RequestMapping("/api/help-requests")
public class HelpRequestController {

    @Autowired private HelpRequestService helpRequestService;
    @Autowired private UserService        userService;
    @Autowired private JdbcTemplate       jdbc;

    @PostMapping
    public ResponseEntity<ApiResponse<HelpRequest>> createRequest(
            @Valid @RequestBody HelpRequestDto dto) {
        return ResponseEntity.ok(ApiResponse.success("Request created",
                helpRequestService.createRequest(dto)));
    }

    @GetMapping
    public ResponseEntity<?> getAllRequests(
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
     public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMyRequests() {
        com.humanitarian.platform.model.User me = userService.getCurrentUser();
        // Use raw SQL with explicit ::text casts to bypass PostgreSQL enum-to-String
        // mapping issues that cause 500 errors when Hibernate reads enum columns.
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT request_id AS id," +
            "       request_id AS \"requestId\"," +
            "       title, description," +
            "       help_type::text    AS \"helpType\"," +
            "       urgency_level::text AS \"urgencyLevel\"," +
            "       status, address, people_count AS \"peopleCount\"," +
            "       has_children AS \"hasChildren\"," +
            "       created_at   AS \"createdAt\"" +
            " FROM help_requests" +
            " WHERE beneficiary_id = ?" +
            " ORDER BY created_at DESC LIMIT 50",
            me.getId());
        return ResponseEntity.ok(ApiResponse.success("My requests", rows));
    }

    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<HelpRequest>>> getPendingRequests() {
        return ResponseEntity.ok(ApiResponse.success("Pending requests",
                helpRequestService.getPendingByPriority()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<HelpRequest>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Request retrieved",
                helpRequestService.getRequestById(id)));
    }

    @PutMapping("/{id}/status")
    @Transactional
    public ResponseEntity<ApiResponse<HelpRequest>> updateStatus(
            @PathVariable Long id, @RequestParam String status) {
        return ResponseEntity.ok(ApiResponse.success("Status updated",
                helpRequestService.updateStatus(id, status)));
    }

    @PutMapping("/{id}/assign")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> assignToMe(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Request assigned",
                helpRequestService.assignToMe(id)));
    }

    @GetMapping("/{id}/contact")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getContact(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Contact retrieved",
                helpRequestService.getContactInfo(id)));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<ApiResponse<?>> deleteRequest(@PathVariable Long id) {
        helpRequestService.deleteRequest(id);
        return ResponseEntity.ok(ApiResponse.success("Request deleted", null));
    }
}