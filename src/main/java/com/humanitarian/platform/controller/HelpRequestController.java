package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.ApiResponse;
import com.humanitarian.platform.dto.HelpRequestDto;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import com.humanitarian.platform.repository.OrganizationRepository;
import com.humanitarian.platform.service.HelpRequestService;
import com.humanitarian.platform.service.UserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/help-requests")
@CrossOrigin(origins = "*")
public class HelpRequestController {

    @Autowired private HelpRequestService     helpRequestService;
    @Autowired private UserService            userService;
    @Autowired private HelpRequestRepository  helpRequestRepository;
    @Autowired private UserRepository         userRepository;
    @Autowired private VolunteerRepository    volunteerRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JdbcTemplate           jdbc;

    @PostMapping
    public ResponseEntity<ApiResponse<HelpRequest>> createRequest(
            @Valid @RequestBody HelpRequestDto dto) {
        return ResponseEntity.ok(ApiResponse.success("Request created",
                helpRequestService.createRequest(dto)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<HelpRequest>>> getAllRequests() {
        return ResponseEntity.ok(ApiResponse.success("Requests retrieved",
                helpRequestService.getAllRequests()));
    }

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<HelpRequest>>> getMyRequests() {
        return ResponseEntity.ok(ApiResponse.success("My requests",
                helpRequestService.getMyRequests()));
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
    public ResponseEntity<ApiResponse<HelpRequest>> updateStatus(
            @PathVariable Long id, @RequestParam String status) {
        return ResponseEntity.ok(ApiResponse.success("Status updated",
                helpRequestService.updateStatus(id, status)));
    }

    @PutMapping("/{id}/assign")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> assignToMe(@PathVariable Long id) {
        User currentUser = userService.getCurrentUser();
        String role = currentUser.getRole().name().toLowerCase();
        int updated = 0;

        if (role.equals("volunteer")) {
            // Must store the volunteer_id (PK of volunteers table), not user_id
            // volunteers table has FK constraint: assigned_volunteer_id → volunteers.volunteer_id
            Long volunteerId = jdbc.queryForObject(
                    "SELECT volunteer_id FROM volunteers WHERE user_id = ?",
                    Long.class, currentUser.getId());
            if (volunteerId == null) {
                throw new RuntimeException("Volunteer profile not found. Contact admin.");
            }
            updated = helpRequestRepository.assignVolunteer(id, volunteerId, "ASSIGNED", "PENDING");

        } else if (role.equals("organization")) {
            // Must store organization_id (PK of organizations table), not user_id
            Long organizationId = jdbc.queryForObject(
                    "SELECT organization_id FROM organizations WHERE user_id = ?",
                    Long.class, currentUser.getId());
            if (organizationId == null) {
                throw new RuntimeException("Organization profile not found. Contact admin.");
            }
            updated = helpRequestRepository.assignOrganization(id, organizationId, "ASSIGNED", "PENDING");

        } else {
            helpRequestRepository.updateStatusNative(id, "ASSIGNED");
            updated = 1;
        }

        if (updated == 0) {
            throw new RuntimeException("Request is no longer available or already assigned.");
        }

        HelpRequest saved = helpRequestService.getRequestById(id);
        Map<String, Object> result = new HashMap<>();
        result.put("requestId",  id);
        result.put("status",     saved.getStatus());
        result.put("workerName", currentUser.getFullName());

        userRepository.findById(saved.getBeneficiaryId()).ifPresent(b -> {
            result.put("requesterName",  b.getFullName());
            result.put("requesterEmail", b.getEmail());
            result.put("requesterPhone", b.getPhone() != null ? b.getPhone() : "Not provided");
        });

        return ResponseEntity.ok(ApiResponse.success("Request assigned", result));
    }

    @GetMapping("/{id}/contact")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getContact(@PathVariable Long id) {
        HelpRequest req = helpRequestService.getRequestById(id);
        Map<String, Object> contact = new HashMap<>();
        userRepository.findById(req.getBeneficiaryId()).ifPresent(b -> {
            contact.put("name",  b.getFullName());
            contact.put("email", b.getEmail());
            contact.put("phone", b.getPhone() != null ? b.getPhone() : "Not provided");
        });
        return ResponseEntity.ok(ApiResponse.success("Contact retrieved", contact));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<?>> deleteRequest(@PathVariable Long id) {
        helpRequestService.deleteRequest(id);
        return ResponseEntity.ok(ApiResponse.success("Request deleted", null));
    }
}