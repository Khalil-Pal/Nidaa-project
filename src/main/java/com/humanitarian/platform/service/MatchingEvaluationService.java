package com.humanitarian.platform.service;

import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.service.matching.MatchingStrategy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MatchingEvaluationService {

    private final List<MatchingStrategy> strategies;
    private final RequestRegionResolver regionResolver;

    public MatchingEvaluationService(List<MatchingStrategy> strategies,
                                     RequestRegionResolver regionResolver) {
        this.strategies = strategies;
        this.regionResolver = regionResolver;
    }

    public Map<String, Object> evaluate(List<HelpRequest> requests) {
        return evaluate(requests, List.of(), List.of());
    }

    public Map<String, Object> evaluate(List<HelpRequest> requests,
                                        List<Volunteer> volunteers,
                                        List<Assignment> assignments) {
        List<HelpRequest> pending = requests.stream()
                .filter(request -> "PENDING".equalsIgnoreCase(request.getStatus()))
                .toList();
        List<Volunteer> available = volunteers.stream()
                .filter(volunteer -> Boolean.TRUE.equals(volunteer.getIsAvailable()))
                .sorted(Comparator.comparing(
                        Volunteer::getId,
                        Comparator.nullsLast(Long::compareTo)))
                .toList();

        Map<String, Object> strategyResults = new LinkedHashMap<>();
        strategies.stream()
                .sorted(Comparator.comparingInt(this::strategyOrder))
                .forEach(strategy -> strategyResults.put(
                        strategy.getName(),
                        evaluateStrategy(strategy, pending, available)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", LocalDateTime.now());
        result.put("historicalMetrics", historicalMetrics(requests, volunteers, assignments));
        result.put("strategyComparison", strategyResults);
        result.put("metricDefinitions", metricDefinitions());
        return result;
    }

    private Map<String, Object> evaluateStrategy(MatchingStrategy strategy,
                                                 List<HelpRequest> requests,
                                                 List<Volunteer> volunteers) {
        LocalDateTime evaluationTime = LocalDateTime.now();
        List<HelpRequest> ranked = strategy.rank(requests, volunteers);
        List<Volunteer> remainingVolunteers = new ArrayList<>(volunteers);
        List<SimulatedMatch> matches = new ArrayList<>();

        for (HelpRequest request : ranked) {
            if (remainingVolunteers.isEmpty()) {
                break;
            }

            Volunteer volunteer = strategy.selectVolunteer(request, remainingVolunteers)
                    .orElse(null);
            if (volunteer == null) {
                continue;
            }

            remainingVolunteers.removeIf(candidate ->
                    java.util.Objects.equals(candidate.getId(), volunteer.getId()));
            matches.add(new SimulatedMatch(
                    request,
                    volunteer,
                    distance(request, volunteer)));
        }

        long totalUrgent = requests.stream().filter(this::isUrgent).count();
        List<SimulatedMatch> urgentMatches = matches.stream()
                .filter(match -> isUrgent(match.request()))
                .toList();

        double averageUrgentWaitHours = urgentMatches.stream()
                .map(SimulatedMatch::request)
                .filter(request -> request.getCreatedAt() != null)
                .mapToDouble(request -> Math.max(
                        0.0,
                        Duration.between(request.getCreatedAt(), evaluationTime).toMinutes() / 60.0))
                .average()
                .orElse(0.0);

        Set<Long> usedVolunteerIds = matches.stream()
                .map(match -> match.volunteer().getId())
                .collect(Collectors.toSet());
        Set<Long> servedRequestIds = matches.stream()
                .map(match -> match.request().getId())
                .collect(Collectors.toSet());

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("totalPendingRequests", requests.size());
        metrics.put("matchedRequests", matches.size());
        metrics.put("unmatchedRequests", Math.max(0, requests.size() - matches.size()));
        metrics.put("averageUrgentWaitingTimeHours", round(averageUrgentWaitHours));
        metrics.put("urgentCoveragePercent", percentage(urgentMatches.size(), totalUrgent));
        metrics.put("volunteerUtilizationPercent",
                percentage(usedVolunteerIds.size(), volunteers.size()));
        metrics.put("regionalFairnessPercent",
                regionalFairness(requests, servedRequestIds));
        metrics.put("averageTravelDistanceKm", round(matches.stream()
                .map(SimulatedMatch::distanceKm)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0)));
        metrics.put("rankedRequestIds", ranked.stream()
                .limit(20)
                .map(HelpRequest::getId)
                .toList());
        return metrics;
    }

    private Map<String, Object> historicalMetrics(List<HelpRequest> requests,
                                                  List<Volunteer> volunteers,
                                                  List<Assignment> assignments) {
        Map<Long, HelpRequest> requestById = requests.stream()
                .filter(request -> request.getId() != null)
                .collect(Collectors.toMap(
                        HelpRequest::getId,
                        Function.identity(),
                        (first, ignored) -> first));

        Map<Long, Assignment> firstHelpAssignment = assignments.stream()
                .filter(this::isHelpAssignment)
                .filter(assignment -> assignment.getAssignedAt() != null)
                .sorted(Comparator.comparing(Assignment::getAssignedAt))
                .collect(Collectors.toMap(
                        Assignment::getRequestId,
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));

        double averageUrgentWaitHours = firstHelpAssignment.entrySet().stream()
                .filter(entry -> {
                    HelpRequest request = requestById.get(entry.getKey());
                    return request != null && request.getCreatedAt() != null && isUrgent(request);
                })
                .mapToDouble(entry -> {
                    HelpRequest request = requestById.get(entry.getKey());
                    return Math.max(0.0, Duration.between(
                            request.getCreatedAt(), entry.getValue().getAssignedAt()).toMinutes() / 60.0);
                })
                .average()
                .orElse(0.0);

        Set<Long> usedVolunteers = assignments.stream()
                .filter(this::isHelpAssignment)
                .map(Assignment::getVolunteerId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        Set<Long> servedRequestIds = new HashSet<>(firstHelpAssignment.keySet());
        Set<Long> completedRequestIds = assignments.stream()
                .filter(this::isHelpAssignment)
                .filter(assignment -> "COMPLETED".equalsIgnoreCase(assignment.getStatus()))
                .map(Assignment::getRequestId)
                .collect(Collectors.toSet());
        long completedDurations = assignments.stream()
                .filter(this::isHelpAssignment)
                .filter(assignment -> assignment.getAssignedAt() != null
                        && assignment.getCompletedAt() != null)
                .count();
        double averageCompletionHours = assignments.stream()
                .filter(this::isHelpAssignment)
                .filter(assignment -> assignment.getAssignedAt() != null
                        && assignment.getCompletedAt() != null)
                .mapToDouble(assignment -> Math.max(0.0, Duration.between(
                        assignment.getAssignedAt(), assignment.getCompletedAt()).toMinutes() / 60.0))
                .average()
                .orElse(0.0);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("recordedHelpAssignments", firstHelpAssignment.size());
        metrics.put("averageUrgentWaitingTimeHours", round(averageUrgentWaitHours));
        metrics.put("volunteerUtilizationPercent",
                percentage(usedVolunteers.size(), volunteers.size()));
        metrics.put("regionalFairnessPercent",
                regionalFairness(requests, servedRequestIds));
        metrics.put("completionRatePercent",
                percentage(
                        completedRequestIds.stream()
                                .filter(firstHelpAssignment::containsKey)
                                .count(),
                        firstHelpAssignment.size()));
        metrics.put("averageCompletionTimeHours", round(averageCompletionHours));
        metrics.put("assignmentsWithCompletionTiming", completedDurations);
        return metrics;
    }

    private double regionalFairness(List<HelpRequest> requests, Set<Long> servedRequestIds) {
        Map<String, Long> totalByRegion = requests.stream()
                .collect(Collectors.groupingBy(regionResolver::resolve, Collectors.counting()));
        if (totalByRegion.isEmpty()) {
            return 100.0;
        }

        Map<String, Long> servedByRegion = requests.stream()
                .filter(request -> servedRequestIds.contains(request.getId()))
                .collect(Collectors.groupingBy(regionResolver::resolve, Collectors.counting()));

        List<Double> coverageRates = totalByRegion.entrySet().stream()
                .map(entry -> servedByRegion.getOrDefault(entry.getKey(), 0L)
                        / (double) entry.getValue())
                .toList();

        double sum = coverageRates.stream().mapToDouble(Double::doubleValue).sum();
        if (sum == 0.0) {
            return 0.0;
        }
        double sumSquares = coverageRates.stream()
                .mapToDouble(value -> value * value)
                .sum();
        return round((sum * sum) / (coverageRates.size() * sumSquares) * 100.0);
    }

    private Map<String, String> metricDefinitions() {
        Map<String, String> definitions = new LinkedHashMap<>();
        definitions.put("averageUrgentWaitingTimeHours",
                "Mean time from creation to assignment for HIGH and CRITICAL requests.");
        definitions.put("volunteerUtilizationPercent",
                "Share of available or recorded volunteers used by the strategy.");
        definitions.put("regionalFairnessPercent",
                "Jain fairness score over assignment coverage in geographic regions.");
        definitions.put("urgentCoveragePercent",
                "Share of pending HIGH and CRITICAL requests assigned in the simulation.");
        definitions.put("averageTravelDistanceKm",
                "Mean Haversine distance for simulated matches with coordinates.");
        return definitions;
    }

    private boolean isHelpAssignment(Assignment assignment) {
        return assignment.getRequestId() != null
                && !"PSYCHOLOGICAL_REQUEST".equals(assignment.getRequestType());
    }

    private boolean isUrgent(HelpRequest request) {
        return "CRITICAL".equalsIgnoreCase(request.getUrgencyLevel())
                || "HIGH".equalsIgnoreCase(request.getUrgencyLevel());
    }

    private Double distance(HelpRequest request, Volunteer volunteer) {
        if (request.getLatitude() == null || request.getLongitude() == null
                || volunteer.getLatitude() == null || volunteer.getLongitude() == null) {
            return null;
        }

        double earthRadiusKm = 6371.0;
        double latitudeDelta = Math.toRadians(volunteer.getLatitude() - request.getLatitude());
        double longitudeDelta = Math.toRadians(volunteer.getLongitude() - request.getLongitude());
        double a = Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2)
                + Math.cos(Math.toRadians(request.getLatitude()))
                * Math.cos(Math.toRadians(volunteer.getLatitude()))
                * Math.sin(longitudeDelta / 2) * Math.sin(longitudeDelta / 2);
        return earthRadiusKm * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private double percentage(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : round(numerator * 100.0 / denominator);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private int strategyOrder(MatchingStrategy strategy) {
        return switch (strategy.getName()) {
            case "FIFO" -> 0;
            case "WEIGHTED_SCORING" -> 1;
            default -> 2;
        };
    }

    private record SimulatedMatch(HelpRequest request,
                                  Volunteer volunteer,
                                  Double distanceKm) {
    }
}
