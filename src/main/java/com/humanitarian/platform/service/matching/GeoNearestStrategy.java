package com.humanitarian.platform.service.matching;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.service.GeoMatchingService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Nearest-first dispatch (EV-1's fourth strategy): the request closest to any
 * available volunteer goes first and gets that volunteer. It minimises travel
 * greedily and ignores urgency and arrival order entirely, which makes it the
 * efficiency pole of the comparison. Requests without coordinates, or with no
 * volunteer to measure against, fall to the end in arrival order.
 */
@Component
public class GeoNearestStrategy implements MatchingStrategy {

    private final GeoMatchingService geoMatchingService;

    public GeoNearestStrategy(GeoMatchingService geoMatchingService) {
        this.geoMatchingService = geoMatchingService;
    }

    @Override
    public List<HelpRequest> rank(List<HelpRequest> requests) {
        return rank(requests, List.of());
    }

    @Override
    public List<HelpRequest> rank(List<HelpRequest> requests, List<Volunteer> volunteers) {
        return requests.stream()
                .sorted(Comparator
                        .comparingDouble((HelpRequest request) -> nearestDistance(request, volunteers))
                        .thenComparing(HelpRequest::getCreatedAt,
                                Comparator.nullsLast(LocalDateTime::compareTo)))
                .toList();
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
        return "GEO_NEAREST";
    }

    private double nearestDistance(HelpRequest request, List<Volunteer> volunteers) {
        return geoMatchingService.findNearestProvider(request, volunteers, List.of())
                .map(GeoMatchingService.ProviderMatch::distanceKm)
                .orElse(Double.MAX_VALUE);
    }
}
