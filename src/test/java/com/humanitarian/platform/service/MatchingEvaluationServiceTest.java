package com.humanitarian.platform.service;

import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.service.matching.FifoMatchingStrategy;
import com.humanitarian.platform.service.matching.OptimizedMatchingStrategy;
import com.humanitarian.platform.service.matching.WeightedScoringStrategy;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchingEvaluationServiceTest {

    private final PriorityScoreService priorityScoreService = new PriorityScoreService();
    private final GeoMatchingService geoMatchingService = new GeoMatchingService();
    private final RequestRegionResolver regionResolver = new RequestRegionResolver();
    private final MatchingEvaluationService service = new MatchingEvaluationService(
            List.of(
                    new FifoMatchingStrategy(),
                    new WeightedScoringStrategy(priorityScoreService),
                    new OptimizedMatchingStrategy(
                            priorityScoreService, geoMatchingService, regionResolver)),
            regionResolver);

    @Test
    @SuppressWarnings("unchecked")
    void comparesAllStrategiesWithOperationalMetrics() {
        LocalDateTime now = LocalDateTime.now();
        HelpRequest oldLow = request(1L, "LOW", now.minusHours(24), 55.75, 37.62);
        HelpRequest critical = request(2L, "CRITICAL", now.minusHours(1), 59.93, 30.32);
        HelpRequest high = request(3L, "HIGH", now.minusHours(5), 59.94, 30.31);

        Volunteer first = Volunteer.builder()
                .id(10L).isAvailable(true).latitude(55.76).longitude(37.63).build();
        Volunteer second = Volunteer.builder()
                .id(11L).isAvailable(true).latitude(59.95).longitude(30.30).build();

        Assignment historical = Assignment.builder()
                .requestId(3L)
                .requestType("HELP_REQUEST")
                .volunteerId(11L)
                .status("COMPLETED")
                .assignedAt(high.getCreatedAt().plusHours(2))
                .completedAt(high.getCreatedAt().plusHours(4))
                .build();

        Map<String, Object> result = service.evaluate(
                List.of(oldLow, critical, high),
                List.of(first, second),
                List.of(historical));

        Map<String, Object> comparison =
                (Map<String, Object>) result.get("strategyComparison");
        assertEquals(List.of("FIFO", "WEIGHTED_SCORING", "MULTI_OBJECTIVE_OPTIMIZATION"),
                comparison.keySet().stream().toList());

        Map<String, Object> fifo = (Map<String, Object>) comparison.get("FIFO");
        Map<String, Object> weighted =
                (Map<String, Object>) comparison.get("WEIGHTED_SCORING");
        assertEquals(1L, ((List<Long>) fifo.get("rankedRequestIds")).get(0));
        assertEquals(2L, ((List<Long>) weighted.get("rankedRequestIds")).get(0));
        assertTrue((Double) weighted.get("volunteerUtilizationPercent") > 0);
        assertTrue(weighted.containsKey("averageUrgentWaitingTimeHours"));
        assertTrue(weighted.containsKey("regionalFairnessPercent"));

        Map<String, Object> historicalMetrics =
                (Map<String, Object>) result.get("historicalMetrics");
        assertEquals(2.0, historicalMetrics.get("averageUrgentWaitingTimeHours"));
        assertEquals(1, historicalMetrics.get("recordedHelpAssignments"));
    }

    private HelpRequest request(Long id,
                                String urgency,
                                LocalDateTime createdAt,
                                double latitude,
                                double longitude) {
        return HelpRequest.builder()
                .id(id)
                .title("Request " + id)
                .urgencyLevel(urgency)
                .peopleCount(1)
                .status("PENDING")
                .createdAt(createdAt)
                .latitude(latitude)
                .longitude(longitude)
                .build();
    }
}
