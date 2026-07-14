package com.humanitarian.platform.service;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Volunteer;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class GeoMatchingService {

    private static final int EARTH_RADIUS_KM = 6371;

    public double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public Optional<Volunteer> findNearestVolunteer(HelpRequest request, List<Volunteer> availableVolunteers) {
        if (availableVolunteers == null || availableVolunteers.isEmpty()) {
            return Optional.empty();
        }

        List<Volunteer> filtered = availableVolunteers.stream()
                .filter(volunteer -> Boolean.TRUE.equals(volunteer.getIsAvailable()))
                .toList();

        if (request.getLatitude() == null || request.getLongitude() == null) {
            return filtered.stream().findFirst();
        }

        return filtered.stream()
                .filter(volunteer -> volunteer.getLatitude() != null && volunteer.getLongitude() != null)
                .min(Comparator.comparingDouble(volunteer ->
                        haversine(request.getLatitude(), request.getLongitude(),
                                volunteer.getLatitude(), volunteer.getLongitude())));
    }
}
