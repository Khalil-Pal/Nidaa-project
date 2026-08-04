package com.humanitarian.platform.service;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Organization;
import com.humanitarian.platform.model.Profile;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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
        List<Volunteer> volunteers = availableVolunteers == null ? List.of() : availableVolunteers;
        return findNearestProvider(request, volunteers, List.of())
                .flatMap(match -> volunteers.stream()
                        .filter(volunteer -> Objects.equals(
                                volunteer.getId(), match.providerId()))
                        .findFirst());
    }

    public List<Volunteer> rankByDistance(HelpRequest request, List<Volunteer> availableVolunteers) {
        List<Volunteer> volunteers = availableVolunteers == null ? List.of() : availableVolunteers;
        return rankProvidersByDistance(request, volunteers, List.of()).stream()
                .map(match -> volunteers.stream()
                        .filter(volunteer -> Objects.equals(
                                volunteer.getId(), match.providerId()))
                        .findFirst()
                        .orElseThrow())
                .toList();
    }

    public Optional<ProviderMatch> findNearestProvider(HelpRequest request,
                                                       List<Volunteer> volunteers,
                                                       List<Organization> organizations) {
        return rankProvidersByDistance(request, volunteers, organizations).stream().findFirst();
    }

    public List<ProviderMatch> rankProvidersByDistance(HelpRequest request,
                                                        List<Volunteer> volunteers,
                                                        List<Organization> organizations) {
        if (request == null || request.getLatitude() == null || request.getLongitude() == null) {
            return List.of();
        }

        List<ProviderMatch> candidates = new ArrayList<>();
        if (volunteers != null) {
            volunteers.stream()
                    .filter(volunteer -> Boolean.TRUE.equals(volunteer.getIsAvailable()))
                    .filter(volunteer -> Boolean.TRUE.equals(volunteer.getAvailabilityPreference()))
                    .map(volunteer -> volunteerMatch(request, volunteer))
                    .flatMap(Optional::stream)
                    .forEach(candidates::add);
        }
        if (organizations != null) {
            organizations.stream()
                    .filter(organization -> Boolean.TRUE.equals(organization.getIsAvailable()))
                    .filter(organization -> Boolean.TRUE.equals(organization.getAvailabilityPreference()))
                    .map(organization -> organizationMatch(request, organization))
                    .flatMap(Optional::stream)
                    .forEach(candidates::add);
        }

        return candidates.stream()
                .sorted(Comparator.comparingDouble(ProviderMatch::distanceKm)
                        .thenComparing(match -> match.providerType().name())
                        .thenComparing(ProviderMatch::providerId))
                .toList();
    }

    private Optional<ProviderMatch> volunteerMatch(HelpRequest request, Volunteer volunteer) {
        return coordinates(volunteer.getUser())
                .map(coordinates -> new ProviderMatch(
                        UserRole.VOLUNTEER,
                        volunteer.getId(),
                        volunteer.getUser().getId(),
                        providerName(volunteer.getUser(), "Volunteer", volunteer.getId()),
                        coordinates.latitude(),
                        coordinates.longitude(),
                        haversine(request.getLatitude(), request.getLongitude(),
                                coordinates.latitude(), coordinates.longitude())));
    }

    private Optional<ProviderMatch> organizationMatch(HelpRequest request,
                                                       Organization organization) {
        return coordinates(organization.getUser())
                .map(coordinates -> new ProviderMatch(
                        UserRole.ORGANIZATION,
                        organization.getId(),
                        organization.getUser().getId(),
                        organizationName(organization),
                        coordinates.latitude(),
                        coordinates.longitude(),
                        haversine(request.getLatitude(), request.getLongitude(),
                                coordinates.latitude(), coordinates.longitude())));
    }

    private Optional<Coordinates> coordinates(User user) {
        Profile profile = user == null ? null : user.getProfile();
        if (profile == null || profile.getLatitude() == null || profile.getLongitude() == null) {
            return Optional.empty();
        }
        return Optional.of(new Coordinates(profile.getLatitude(), profile.getLongitude()));
    }

    private String organizationName(Organization organization) {
        if (organization.getOfficialName() != null && !organization.getOfficialName().isBlank()) {
            return organization.getOfficialName();
        }
        return providerName(organization.getUser(), "Organization", organization.getId());
    }

    private String providerName(User user, String fallback, Long providerId) {
        if (user != null && user.getFullName() != null && !user.getFullName().isBlank()) {
            return user.getFullName();
        }
        return providerId == null ? fallback : fallback + " #" + providerId;
    }

    public record ProviderMatch(UserRole providerType,
                                Long providerId,
                                Long userId,
                                String providerName,
                                double latitude,
                                double longitude,
                                double distanceKm) {
    }

    private record Coordinates(double latitude, double longitude) {
    }
}
