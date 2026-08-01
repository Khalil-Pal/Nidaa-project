package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.RankedPsychologicalRequestDTO;
import com.humanitarian.platform.model.PsychologicalRequest;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.PsychologicalRequestRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import com.humanitarian.platform.service.AssignmentHistoryService;
import com.humanitarian.platform.service.HelpRequestService;
import com.humanitarian.platform.service.MatchingEvaluationService;
import com.humanitarian.platform.service.PriorityScoreService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminV1ControllerTest {

    @Test
    void rankedDashboardIncludesAllPsychologicalRequestsWithScores() {
        HelpRequestService helpRequestService = mock(HelpRequestService.class);
        HelpRequestRepository helpRequestRepository = mock(HelpRequestRepository.class);
        VolunteerRepository volunteerRepository = mock(VolunteerRepository.class);
        PsychologicalRequestRepository psychologicalRequestRepository = mock(PsychologicalRequestRepository.class);
        MatchingEvaluationService matchingEvaluationService = mock(MatchingEvaluationService.class);
        AssignmentHistoryService assignmentHistoryService = mock(AssignmentHistoryService.class);
        AssignmentRepository assignmentRepository = mock(AssignmentRepository.class);

        PsychologicalRequest crisis = PsychologicalRequest.builder()
                .id(1L)
                .urgencyLevel("CRITICAL")
                .isCrisis(true)
                .createdAt(LocalDateTime.now())
                .build();
        PsychologicalRequest nonCrisis = PsychologicalRequest.builder()
                .id(2L)
                .urgencyLevel("LOW")
                .isCrisis(false)
                .createdAt(LocalDateTime.now())
                .build();

        when(volunteerRepository.findByIsAvailableTrue()).thenReturn(List.of());
        when(helpRequestService.getRankedWithSuggestions(List.of())).thenReturn(List.of());
        when(psychologicalRequestRepository.findAll()).thenReturn(List.of(nonCrisis, crisis));

        AdminV1Controller controller = new AdminV1Controller(
                helpRequestService,
                helpRequestRepository,
                volunteerRepository,
                psychologicalRequestRepository,
                matchingEvaluationService,
                assignmentHistoryService,
                assignmentRepository,
                new PriorityScoreService());

        ResponseEntity<Map<String, Object>> response = controller.getRankedDashboard();
        Map<String, Object> body = response.getBody();

        assertNotNull(body);
        List<?> ranked = assertInstanceOf(List.class, body.get("psychologicalRequests"));
        assertEquals(2, ranked.size());
        RankedPsychologicalRequestDTO first = assertInstanceOf(
                RankedPsychologicalRequestDTO.class, ranked.get(0));
        RankedPsychologicalRequestDTO second = assertInstanceOf(
                RankedPsychologicalRequestDTO.class, ranked.get(1));
        assertEquals(crisis, first.getRequest());
        assertTrue(first.getPriorityScore() > second.getPriorityScore());
        assertEquals(List.of(crisis), body.get("crisisPsychologicalCases"));
        assertEquals(2, body.get("totalPsychological"));
        assertEquals(1, body.get("totalCrisis"));
    }
}
