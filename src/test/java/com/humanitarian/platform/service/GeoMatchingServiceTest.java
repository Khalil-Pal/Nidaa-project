package com.humanitarian.platform.service;

import com.humanitarian.platform.model.HelpRequest;
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
                .latitude(55.75)
                .longitude(37.62)
                .build();
        Volunteer availableFarther = Volunteer.builder()
                .id(2L)
                .isAvailable(true)
                .latitude(55.80)
                .longitude(37.70)
                .build();

        assertEquals(2L, service.findNearestVolunteer(
                request,
                List.of(unavailableNearby, availableFarther)).orElseThrow().getId());
    }
}
