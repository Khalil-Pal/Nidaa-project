package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.RankedRequestDTO;
import com.humanitarian.platform.dto.AssignmentHistoryDTO;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.PsychologicalRequest;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.PsychologicalRequestRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import com.humanitarian.platform.service.HelpRequestService;
import com.humanitarian.platform.service.AssignmentHistoryService;
import com.humanitarian.platform.service.MatchingEvaluationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminV1Controller {

    private final HelpRequestService helpRequestService;
    private final HelpRequestRepository helpRequestRepository;
    private final VolunteerRepository volunteerRepository;
    private final PsychologicalRequestRepository psychologicalRequestRepository;
    private final MatchingEvaluationService matchingEvaluationService;
    private final AssignmentHistoryService assignmentHistoryService;
    private final AssignmentRepository assignmentRepository;

    public AdminV1Controller(HelpRequestService helpRequestService,
                             HelpRequestRepository helpRequestRepository,
                             VolunteerRepository volunteerRepository,
                             PsychologicalRequestRepository psychologicalRequestRepository,
                             MatchingEvaluationService matchingEvaluationService,
                             AssignmentHistoryService assignmentHistoryService,
                             AssignmentRepository assignmentRepository) {
        this.helpRequestService = helpRequestService;
        this.helpRequestRepository = helpRequestRepository;
        this.volunteerRepository = volunteerRepository;
        this.psychologicalRequestRepository = psychologicalRequestRepository;
        this.matchingEvaluationService = matchingEvaluationService;
        this.assignmentHistoryService = assignmentHistoryService;
        this.assignmentRepository = assignmentRepository;
    }

    @GetMapping("/assignments")
    public ResponseEntity<List<AssignmentHistoryDTO>> getAssignmentHistory() {
        return ResponseEntity.ok(assignmentHistoryService.getAllHistory());
    }

    @GetMapping("/evaluation")
    public ResponseEntity<Map<String, Object>> runEvaluation() {
        return ResponseEntity.ok(matchingEvaluationService.evaluate(
                helpRequestRepository.findAll(),
                volunteerRepository.findAll(),
                assignmentRepository.findAll()));
    }

    @GetMapping("/dashboard/ranked")
    public ResponseEntity<Map<String, Object>> getRankedDashboard() {
        List<RankedRequestDTO> ranked = helpRequestService
                .getRankedWithSuggestions(volunteerRepository.findByIsAvailableTrue());
        List<PsychologicalRequest> crisisCases = psychologicalRequestRepository.findByIsCrisisTrue();

        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("rankedMaterialRequests", ranked);
        dashboard.put("crisisPsychologicalCases", crisisCases);
        dashboard.put("totalPending", ranked.size());
        dashboard.put("totalCrisis", crisisCases.size());
        return ResponseEntity.ok(dashboard);
    }
}
