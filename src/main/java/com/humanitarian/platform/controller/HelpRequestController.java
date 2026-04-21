package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.ApiResponse;
import com.humanitarian.platform.dto.HelpRequestDto;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.service.HelpRequestService;
import com.humanitarian.platform.service.UserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/help-requests")
@CrossOrigin(origins = "*")
public class HelpRequestController {

    @Autowired private HelpRequestService helpRequestService;
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;

    @PostMapping
    public ResponseEntity<ApiResponse<HelpRequest>> createRequest(@Valid @RequestBody HelpRequestDto dto) {
        return ResponseEntity.ok(ApiResponse.success("Help request created", helpRequestService.createRequest(dto)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<HelpRequest>>> getAllRequests() {
        return ResponseEntity.ok(ApiResponse.success("Requests retrieved", helpRequestService.getAllRequests()));
    }

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<HelpRequest>>> getMyRequests() {
        return ResponseEntity.ok(ApiResponse.success("My requests retrieved", helpRequestService.getMyRequests()));
    }

    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<HelpRequest>>> getPendingRequests() {
        return ResponseEntity.ok(ApiResponse.success("Pending requests retrieved", helpRequestService.getPendingByPriority()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<HelpRequest>> getRequestById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Request retrieved", helpRequestService.getRequestById(id)));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<ApiResponse<List<HelpRequest>>> getByStatus(@PathVariable String status) {
        return ResponseEntity.ok(ApiResponse.success("Requests retrieved", helpRequestService.getRequestsByStatus(status)));
    }

    @GetMapping("/type/{type}")
    public ResponseEntity<ApiResponse<List<HelpRequest>>> getByType(@PathVariable String type) {
        return ResponseEntity.ok(ApiResponse.success("Requests retrieved", helpRequestService.getRequestsByType(type)));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<ApiResponse<HelpRequest>> updateStatus(
            @PathVariable Long id, @RequestParam String status) {
        return ResponseEntity.ok(ApiResponse.success("Status updated", helpRequestService.updateStatus(id, status)));
    }

    // PUT /api/help-requests/{id}/assign — volunteer/org starts working on a request
    @PutMapping("/{id}/assign")
    public ResponseEntity<ApiResponse<Map<String, Object>>> assignToMe(@PathVariable Long id) {
        User currentUser = userService.getCurrentUser();
        HelpRequest request = helpRequestService.getRequestById(id);

        // Assign the worker
        String role = currentUser.getRole().name().toLowerCase();
        if (role.equals("volunteer")) {
            request.setAssignedVolunteerId(currentUser.getId());
        } else if (role.equals("organization")) {
            request.setAssignedOrganizationId(currentUser.getId());
        }
        request.setStatus("ASSIGNED");
        helpRequestService.saveRequest(request);

        // Return requester contact info
        Map<String, Object> result = new HashMap<>();
        result.put("requestId", id);
        result.put("status", "ASSIGNED");
        result.put("workerName", currentUser.getFullName());

        // Look up beneficiary info to show contact
        userRepository.findById(request.getBeneficiaryId()).ifPresent(beneficiary -> {
            result.put("requesterName",  beneficiary.getFullName());
            result.put("requesterEmail", beneficiary.getEmail());
            result.put("requesterPhone", beneficiary.getPhone() != null ? beneficiary.getPhone() : "Not provided");
        });

        return ResponseEntity.ok(ApiResponse.success("Request assigned", result));
    }

    // GET /api/help-requests/{id}/contact — get requester contact info
    @GetMapping("/{id}/contact")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRequesterContact(@PathVariable Long id) {
        HelpRequest request = helpRequestService.getRequestById(id);
        Map<String, Object> contact = new HashMap<>();
        userRepository.findById(request.getBeneficiaryId()).ifPresent(b -> {
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