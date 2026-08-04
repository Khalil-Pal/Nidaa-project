package com.humanitarian.platform.service;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Organization;
import com.humanitarian.platform.model.Profile;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoMatchingServiceTest {

    private final GeoMatchingService service = new GeoMatchingService();

    @Test
    void moscowToSaintPetersburgIsApproxSevenHundredKm() {
        double distance = service.haversine(55.75, 37.62, 59.93, 30.32);

        assertTrue(distance > 600 && distance < 800, "Expected ~700km, got " + distance);
    }

    @Test
    void samePointIsZeroDistance() {
        double distance = service.haversine(55.75, 37.62, 55.75, 37.62);

        assertEquals(0.0, distance, 0.01);
    }

    @Test
    void nearestVolunteerIgnoresUnavailableVolunteers() {
        HelpRequest request = HelpRequest.builder()
                .latitude(55.75)
                .longitude(37.62)
                .build();
        Volunteer unavailableNearby = Volunteer.builder()
                .id(1L)
                .isAvailable(false)
                .user(userAt(10L, 55.75, 37.62))
                .build();
        Volunteer availableFarther = Volunteer.builder()
                .id(2L)
                .isAvailable(true)
                .user(userAt(20L, 55.80, 37.70))
                .build();

        assertEquals(2L, service.findNearestVolunteer(
                request,
                List.of(unavailableNearby, availableFarther)).orElseThrow().getId());
    }

    @Test
    void requestWithoutCoordinatesIsNotGeoMatched() {
        HelpRequest request = HelpRequest.builder().build();
        Volunteer available = Volunteer.builder()
                .id(1L)
                .isAvailable(true)
                .user(userAt(10L, 55.75, 37.62))
                .build();

        assertTrue(service.findNearestVolunteer(request, List.of(available)).isEmpty());
    }

    @Test
    void combinedRankingUsesCanonicalProfileCoordinates() {
        HelpRequest request = HelpRequest.builder()
                .latitude(55.75)
                .longitude(37.62)
                .build();
        Volunteer volunteer = Volunteer.builder()
                .id(1L)
                .isAvailable(true)
                .user(userAt(10L, 55.90, 37.90))
                .build();
        User organizationUser = userAt(20L, 55.751, 37.621);
        Organization organization = Organization.builder()
                .id(2L)
                .user(organizationUser)
                .officialName("Nearby Aid")
                .isAvailable(true)
                .build();

        var match = service.findNearestProvider(
                request, List.of(volunteer), List.of(organization)).orElseThrow();

        assertEquals(UserRole.ORGANIZATION, match.providerType());
        assertEquals(2L, match.providerId());
    }

    private User userAt(Long id, double latitude, double longitude) {
        User user = User.builder().id(id).fullName("Provider " + id).build();
        user.setProfile(Profile.builder()
                .user(user)
                .latitude(latitude)
                .longitude(longitude)
                .build());
        return user;
    }
}
