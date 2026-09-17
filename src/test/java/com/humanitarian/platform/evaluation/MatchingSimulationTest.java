package com.humanitarian.platform.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Profile;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.Volunteer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The event loop on datasets small enough to work out by hand: who is served
 * first, what the wait is, that the service time is handling plus the round
 * trip, that the metrics are what their definitions say, and that a run is
 * deterministic.
 */
class MatchingSimulationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final DatasetSpec.Cluster HERE = new DatasetSpec.Cluster("Here", 55.6, 37.4, 0.1, 1.0);

    /** One provider at the cluster centre; two requests, the second nearer, both arriving at hour 0 with 1 h of handling. */
    private static SyntheticDataset twoRequestsOneProvider(String firstUrgency, String secondUrgency) {
        DatasetSpec spec = new DatasetSpec(1L, 2, 72, T0, List.of(HERE), 1, DatasetSpec.PLAN_URGENCY, 0, 0, 0, 1, 1, 1);
        HelpRequest far = request(1L, firstUrgency, T0, 55.6, 37.4 + 0.16);        // ~10 km east
        HelpRequest near = request(2L, secondUrgency, T0, 55.6, 37.4 + 0.016);     // ~1 km east
        return new SyntheticDataset(spec, List.of(far, near), List.of(volunteer(1L, 55.6, 37.4)),
                Map.of(1L, 1.0, 2L, 1.0), Map.of(1L, "Here", 2L, "Here"), Map.of(1L, "Here"));
    }

    @Test
    void fifoServesTheEarlierRequestAndTheOtherWaitsForTheRoundTrip() {
        SyntheticDataset ds = twoRequestsOneProvider("LOW", "CRITICAL");
        RunMetrics m = new MatchingSimulation().run(ds, "FIFO");

        // request 1: 10 km away, service = 1 h + 2*10/40 h = 1.5 h; request 2 waits exactly that long
        assertEquals(1.5, m.criticalMeanWaitHours(), 0.01, "the CRITICAL one is second under FIFO");
        assertEquals(1.5, m.criticalP95WaitHours(), 0.01);
        assertEquals(0.75, m.meanWaitHours(), 0.01, "(0 + 1.5) / 2");
        assertEquals(100.0, m.urgentWithinWindowPercent(), 0.01, "1.5 h is inside the 6 h window");
        assertEquals(5.5, m.meanDistanceKm(), 0.2, "(10 + 1) / 2 km");
        assertEquals(0.0, m.utilisationGini(), 0.0, "one provider: nothing to be unequal about");
        assertEquals(0.0, m.crossClusterPercent(), 0.0);
        assertEquals(100.0, m.completedWithinHorizonPercent(), 0.0);
        assertEquals(1.0, m.regionalFairnessJain(), 0.0);
        assertEquals(1, m.providerCount());
        assertEquals(2, m.requestCount());
    }

    @Test
    void geoNearestAndWeightedScoringPickDifferentFirstRequests() {
        SyntheticDataset ds = twoRequestsOneProvider("LOW", "CRITICAL");
        MatchingSimulation sim = new MatchingSimulation();

        RunMetrics geo = sim.run(ds, "GEO_NEAREST");
        assertEquals(0.0, geo.criticalMeanWaitHours(), 0.01, "the near CRITICAL request happens to be nearest: served first");
        assertEquals(0.525, geo.meanWaitHours(), 0.01, "the far LOW one waits 1 h + 2*1/40 h; mean of (0, 1.05)");

        RunMetrics weighted = sim.run(ds, "WEIGHTED_SCORING");
        assertEquals(0.0, weighted.criticalMeanWaitHours(), 0.01, "CRITICAL outranks LOW");

        SyntheticDataset reversed = twoRequestsOneProvider("CRITICAL", "LOW");
        RunMetrics geoReversed = sim.run(reversed, "GEO_NEAREST");
        assertEquals(1.05, geoReversed.criticalMeanWaitHours(), 0.01, "geo-nearest ignores urgency: the far CRITICAL one waits");
        RunMetrics weightedReversed = sim.run(reversed, "WEIGHTED_SCORING");
        assertEquals(0.0, weightedReversed.criticalMeanWaitHours(), 0.01);
    }

    @Test
    void everyRequestIsAssignedAndARunIsDeterministic() {
        SyntheticDataset ds = new SyntheticDataGenerator().generate(DatasetSpec.study(3L, 2.0, 2));
        MatchingSimulation sim = new MatchingSimulation();
        for (String strategy : StudyStrategies.NAMES) {
            RunMetrics first = sim.run(ds, strategy);
            RunMetrics second = sim.run(ds, strategy);
            assertEquals(first, second, strategy + " twice on the same dataset");
            assertEquals(ds.requests().size(), first.requestCount());
            assertTrue(first.meanWaitHours() >= 0 && first.p95WaitHours() >= first.meanWaitHours(), strategy);
            assertTrue(first.utilisationGini() >= 0 && first.utilisationGini() <= 1, strategy);
            assertTrue(first.regionalFairnessJain() > 0 && first.regionalFairnessJain() <= 1.0000001, strategy);
            assertTrue(first.completedWithinHorizonPercent() > 0 && first.completedWithinHorizonPercent() <= 100, strategy);
            assertTrue(first.meanDistanceKm() > 0, strategy);
        }
    }

    @Test
    void theArithmeticMatchesTheDefinitions() {
        assertEquals(0.0, MatchingSimulation.gini(new double[]{3, 3, 3}), 1e-9);
        assertEquals(0.75, MatchingSimulation.gini(new double[]{0, 0, 0, 12}), 1e-9, "one of four does everything");
        assertEquals(1.0, MatchingSimulation.jain(new double[]{0.4, 0.4, 0.4}), 1e-9);
        assertEquals(1.0 / 3, MatchingSimulation.jain(new double[]{1, 0, 0}), 1e-9);
        assertEquals(5.0, MatchingSimulation.percentile(List.of(1.0, 2.0, 3.0, 4.0, 5.0), 0.95), 1e-9);
        assertEquals(3.0, MatchingSimulation.percentile(List.of(1.0, 2.0, 3.0, 4.0, 5.0), 0.5), 1e-9);
        assertEquals(0.0, MatchingSimulation.mean(List.of()), 1e-9);
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
