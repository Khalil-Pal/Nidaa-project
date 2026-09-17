package com.humanitarian.platform.service.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Profile;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.service.GeoMatchingService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** EV-1's fourth strategy: nearest request first, nearest volunteer for it, no regard for urgency or age. */
class GeoNearestStrategyTest {

    private final GeoNearestStrategy strategy = new GeoNearestStrategy(new GeoMatchingService());

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Test
    void ranksByDistanceToTheNearestVolunteerAndPicksThatVolunteer() {
        Volunteer central = volunteer(1L, 55.60, 37.40);
        Volunteer north = volunteer(2L, 56.30, 37.60);
        HelpRequest oldCriticalFar = request(1L, "CRITICAL", T0.minusHours(30), 55.70, 38.40);   // ~60 km from either
        HelpRequest freshLowNear = request(2L, "LOW", T0, 55.61, 37.41);                         // ~1 km from central
        HelpRequest nearNorth = request(3L, "MEDIUM", T0.minusHours(1), 56.28, 37.58);          // ~2.5 km from north

        List<HelpRequest> ranked = strategy.rank(List.of(oldCriticalFar, freshLowNear, nearNorth), List.of(central, north));

        assertEquals(List.of(2L, 3L, 1L), ranked.stream().map(HelpRequest::getId).toList(),
                "distance decides, not urgency or age");
        assertEquals(1L, strategy.selectVolunteer(freshLowNear, List.of(north, central)).orElseThrow().getId());
        assertEquals(2L, strategy.selectVolunteer(nearNorth, List.of(central, north)).orElseThrow().getId());
        assertEquals("GEO_NEAREST", strategy.getName());
    }

    @Test
    void withoutVolunteersItFallsBackToArrivalOrderAndTheFirstProvider() {
        HelpRequest later = request(1L, "LOW", T0.plusHours(2), 55.6, 37.4);
        HelpRequest earlier = request(2L, "LOW", T0, 55.7, 38.4);
        HelpRequest noCoordinates = HelpRequest.builder().id(3L).urgencyLevel("HIGH").createdAt(T0.minusHours(5)).build();

        List<HelpRequest> ranked = strategy.rank(List.of(later, earlier, noCoordinates));
        assertEquals(List.of(3L, 2L, 1L), ranked.stream().map(HelpRequest::getId).toList(),
                "no distances to compare: creation time decides");

        Volunteer noProfile = Volunteer.builder().id(9L).isAvailable(true)
                .user(User.builder().id(9L).fullName("No profile").build()).build();
        assertTrue(strategy.selectVolunteer(earlier, List.of()).isEmpty());
        assertEquals(9L, strategy.selectVolunteer(earlier, List.of(noProfile)).orElseThrow().getId(),
                "a volunteer without coordinates is still someone");
    }

    private static HelpRequest request(Long id, String urgency, LocalDateTime createdAt, double lat, double lon) {
        return HelpRequest.builder().id(id).urgencyLevel(urgency).peopleCount(1).status("PENDING")
                .createdAt(createdAt).latitude(lat).longitude(lon).build();
    }

    private static Volunteer volunteer(Long id, double lat, double lon) {
        User user = User.builder().id(id).fullName("Volunteer " + id).build();
        user.setProfile(Profile.builder().user(user).latitude(lat).longitude(lon).build());
        return Volunteer.builder().id(id).user(user).isAvailable(true).availabilityPreference(true).build();
    }
}
