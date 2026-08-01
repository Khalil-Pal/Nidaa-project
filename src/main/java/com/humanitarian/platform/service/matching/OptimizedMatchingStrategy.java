package com.humanitarian.platform.service.matching;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.service.GeoMatchingService;
import com.humanitarian.platform.service.PriorityScoreService;
import com.humanitarian.platform.service.RequestRegionResolver;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class OptimizedMatchingStrategy implements MatchingStrategy {

    private final PriorityScoreService priorityScoreService;
    private final GeoMatchingService geoMatchingService;
    private final RequestRegionResolver regionResolver;

    public OptimizedMatchingStrategy(PriorityScoreService priorityScoreService,
                                     GeoMatchingService geoMatchingService,
                                     RequestRegionResolver regionResolver) {
        this.priorityScoreService = priorityScoreService;
        this.geoMatchingService = geoMatchingService;
        this.regionResolver = regionResolver;
    }

    @Override
    public List<HelpRequest> rank(List<HelpRequest> requests) {
        return rank(requests, List.of());
    }

    @Override
    public List<HelpRequest> rank(List<HelpRequest> requests, List<Volunteer> volunteers) {
        List<HelpRequest> remaining = new ArrayList<>(requests);
        List<HelpRequest> ranked = new ArrayList<>(requests.size());
        Map<String, Integer> selectedPerRegion = new HashMap<>();

        while (!remaining.isEmpty()) {
            HelpRequest selected = remaining.stream()
                    .max(Comparator
                            .comparingDouble((HelpRequest request) ->
                                    optimizationScore(request, volunteers, selectedPerRegion))
                            .thenComparing(
                                    HelpRequest::getCreatedAt,
                                    Comparator.nullsLast(Comparator.reverseOrder())))
                    .orElseThrow();

            ranked.add(selected);
            remaining.remove(selected);
            selectedPerRegion.merge(regionResolver.resolve(selected), 1, Integer::sum);
        }

        return ranked;
    }

    @Override
    public Optional<Volunteer> selectVolunteer(HelpRequest request,
                                               List<Volunteer> availableVolunteers) {
        Optional<Volunteer> nearest = geoMatchingService.findNearestVolunteer(
                request, availableVolunteers);
        return nearest.isPresent() ? nearest : availableVolunteers.stream().findFirst();
    }

    @Override
    public String getName() {
        return "MULTI_OBJECTIVE_OPTIMIZATION";
    }

    private double optimizationScore(HelpRequest request,
                                     List<Volunteer> volunteers,
                                     Map<String, Integer> selectedPerRegion) {
        double score = priorityScoreService.calculate(request) * 2.0;

        score += switch (normalize(request.getUrgencyLevel())) {
            case "CRITICAL" -> 80.0;
            case "HIGH" -> 35.0;
            default -> 0.0;
        };

        int alreadySelected = selectedPerRegion.getOrDefault(regionResolver.resolve(request), 0);
        score += 35.0 / (alreadySelected + 1);

        score += geoMatchingService.findNearestVolunteer(request, volunteers)
                .map(volunteer -> geoMatchingService.haversine(
                        request.getLatitude(), request.getLongitude(),
                        volunteer.getLatitude(), volunteer.getLongitude()))
                .map(distance -> Math.max(-20.0, 30.0 - distance * 0.4))
                .orElse(-10.0);

        LocalDateTime createdAt = request.getCreatedAt();
        if (createdAt == null) {
            score -= 1.0;
        }
        return score;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
