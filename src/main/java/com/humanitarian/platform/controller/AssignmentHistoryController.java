package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.ApiResponse;
import com.humanitarian.platform.dto.AssignmentHistoryDTO;
import com.humanitarian.platform.service.AssignmentHistoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/assignments")
public class AssignmentHistoryController {

    private final AssignmentHistoryService assignmentHistoryService;

    public AssignmentHistoryController(AssignmentHistoryService assignmentHistoryService) {
        this.assignmentHistoryService = assignmentHistoryService;
    }

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<AssignmentHistoryDTO>>> getMyHistory() {
        return ResponseEntity.ok(ApiResponse.success(
                "Assignment history retrieved", assignmentHistoryService.getMyHistory()));
    }

    @GetMapping("/help-requests/{requestId}")
    public ResponseEntity<ApiResponse<List<AssignmentHistoryDTO>>> getHelpRequestHistory(
            @PathVariable Long requestId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Help-request assignment history retrieved",
                assignmentHistoryService.getHelpRequestHistory(requestId)));
    }

    @GetMapping("/psychological-requests/{requestId}")
    public ResponseEntity<ApiResponse<List<AssignmentHistoryDTO>>> getPsychologicalRequestHistory(
            @PathVariable Long requestId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Psychological-request assignment history retrieved",
                assignmentHistoryService.getPsychologicalRequestHistory(requestId)));
    }
}
