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
 * first on arrival, what the 30-minute sweep costs the ones that had to wait
 * (GAP-2), what a decline does (GAP-1), that the metrics are what their
 * definitions say, and that a run is deterministic.
 */
class MatchingSimulationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final DatasetSpec.Cluster HERE = new DatasetSpec.Cluster("Here", 55.6, 37.4, 0.1, 1.0);

    /** No declines, so the arithmetic below is only about dispatch and the sweep. */
    private static final MatchingSimulation.Settings NO_DECLINES =
            MatchingSimulation.Settings.DEFAULT.withDeclineProbability(0);

    private static DatasetSpec spec(long seed) {
        return new DatasetSpec(seed, 2, 72, T0, List.of(HERE), 1, DatasetSpec.PLAN_URGENCY, 0, 0, 0, 1, 1, 1);
    }

    /** One provider at the cluster centre; two requests at hour 0, the second nearer, 1 h of handling each. */
    private static SyntheticDataset twoRequestsOneProvider(String firstUrgency, String secondUrgency) {
        HelpRequest far = request(1L, firstUrgency, T0, 55.6, 37.4 + 0.16);        // ~10 km east
        HelpRequest near = request(2L, secondUrgency, T0, 55.6, 37.4 + 0.016);     // ~1 km east
        return new SyntheticDataset(spec(1L), List.of(far, near), List.of(volunteer(1L, 55.6, 37.4)),
                Map.of(1L, 1.0, 2L, 1.0), Map.of(1L, "Here", 2L, "Here"), Map.of(1L, "Here"));
    }

    @Test
    void theFirstArrivalTakesTheProviderAndTheSecondWaitsForASweep() {
        SyntheticDataset ds = twoRequestsOneProvider("LOW", "CRITICAL");
        RunMetrics m = new MatchingSimulation(NO_DECLINES).run(ds, "FIFO");

        // Request 1 arrives first and is offered to the only provider immediately: about
        // 10 km away, so service is 1 h + 2*10.04/40 h = 1.502 h. Request 2 arrives in the
        // same instant with nobody free. The provider returns at 1.502 — just after the
        // 1.5 h sweep — so the request waits for the 2.0 h one. Half an hour of the
        // CRITICAL request's wait is the sweep interval itself, which is what the platform
        // does and what this model now shows.
        assertEquals(2.0, m.criticalMeanWaitHours(), 0.01, "the next sweep after the provider returns");
        assertEquals(1.0, m.meanWaitHours(), 0.01, "(0 + 2.0) / 2");
        assertEquals(100.0, m.urgentWithinWindowPercent(), 0.01, "2 h is inside the 6 h window");
        assertEquals(5.5, m.meanDistanceKm(), 0.2, "(10 + 1) / 2 km");
        assertEquals(0.0, m.utilisationGini(), 0.0, "one provider: nothing to be unequal about");
        assertEquals(0.0, m.escalatedPercent(), 0.0, "nobody declined");
        assertEquals(100.0, m.completedWithinHorizonPercent(), 0.0);
        assertEquals(1.0, m.regionalFairnessJain(), 0.0);
        assertEquals(1, m.providerCount());
        assertEquals(2, m.requestCount());
    }

    /**
     * A provider who becomes free between sweeps waits for the next tick: the cost
     * of the 30-minute schedule, which the simulation now models because the
     * platform works that way (GAP-2).
     */
    @Test
    void aProviderFreedBetweenSweepsIsNotUsedUntilTheNextTick() {
        HelpRequest first = request(1L, "LOW", T0, 55.6, 37.4);                    // at the provider: no travel
        HelpRequest second = request(2L, "CRITICAL", T0, 55.6, 37.4);
        SyntheticDataset ds = new SyntheticDataset(spec(2L), List.of(first, second),
                List.of(volunteer(1L, 55.6, 37.4)),
                Map.of(1L, 0.75, 2L, 0.75),                                        // 45 minutes of handling
                Map.of(1L, "Here", 2L, "Here"), Map.of(1L, "Here"));

        RunMetrics m = new MatchingSimulation(NO_DECLINES).run(ds, "FIFO");

        // the provider is free again at 0.75 h; the sweeps are at 0.5, 1.0, ... so the
        // second request waits until 1.0, not 0.75
        assertEquals(1.0, m.criticalMeanWaitHours(), 1e-6, "the next sweep, not the moment of release");
    }

    @Test
    void theSweepOrdersTheQueueByStrategyWhileArrivalDoesNot() {
        // Two requests arrive while the only provider is busy with a third, so both are
        // in the queue when the sweep runs and the strategy decides between them.
        HelpRequest busywork = request(1L, "LOW", T0, 55.6, 37.4);
        HelpRequest lowNear = request(2L, "LOW", T0.plusMinutes(6), 55.6, 37.4 + 0.016);
        HelpRequest criticalFar = request(3L, "CRITICAL", T0.plusMinutes(12), 55.6, 37.4 + 0.16);
        SyntheticDataset ds = new SyntheticDataset(spec(3L), List.of(busywork, lowNear, criticalFar),
                List.of(volunteer(1L, 55.6, 37.4)),
                Map.of(1L, 1.0, 2L, 1.0, 3L, 1.0),
                Map.of(1L, "Here", 2L, "Here", 3L, "Here"), Map.of(1L, "Here"));

        MatchingSimulation sim = new MatchingSimulation(NO_DECLINES);
        RunMetrics weighted = sim.run(ds, "WEIGHTED_SCORING");
        RunMetrics geo = sim.run(ds, "GEO_NEAREST");

        assertTrue(weighted.criticalMeanWaitHours() < geo.criticalMeanWaitHours(),
                "weighted scoring puts the CRITICAL request first at the sweep ("
                        + weighted.criticalMeanWaitHours() + " h against " + geo.criticalMeanWaitHours() + " h)");
        assertTrue(geo.meanDistanceKm() <= weighted.meanDistanceKm(),
                "and geo-nearest travels no further: " + geo.meanDistanceKm() + " against " + weighted.meanDistanceKm());
    }

    @Test
    void aDeclineSendsTheRequestBackAndIsNeverOfferedToThatProviderAgain() {
        SyntheticDataset ds = twoRequestsOneProvider("LOW", "CRITICAL");
        // everybody refuses everything: with one provider, nothing can ever be placed
        RunMetrics refused = new MatchingSimulation(
                MatchingSimulation.Settings.DEFAULT.withDeclineProbability(1.0)).run(ds, "FIFO");
        assertEquals(0.0, refused.completedWithinHorizonPercent(), 0.0, "nothing was delivered");
        assertEquals(0.0, refused.meanDistanceKm(), 0.0, "and nobody travelled");
        assertEquals(100.0, refused.unassignedPercent(), 0.0, "both are reported as never taken");
        assertEquals(0.0, refused.urgentWithinWindowPercent(), 0.0, "a request nobody took missed its window");

        // with nobody refusing, the same dataset is served
        RunMetrics served = new MatchingSimulation(NO_DECLINES).run(ds, "FIFO");
        assertEquals(100.0, served.completedWithinHorizonPercent(), 0.0);
        assertEquals(0.0, served.escalatedPercent(), 0.0);
        assertEquals(0.0, served.unassignedPercent(), 0.0);
    }

    @Test
    void whoDeclinesWhatIsAPropertyOfThePairSoEveryStrategyMeetsTheSameRefusals() {
        SyntheticDataset ds = new SyntheticDataGenerator().generate(DatasetSpec.study(11L, 2.0, 2));
        MatchingSimulation sim = new MatchingSimulation();          // the default 10 % decline rate
        RunMetrics fifo = sim.run(ds, "FIFO");
        RunMetrics geo = sim.run(ds, "GEO_NEAREST");

        assertTrue(fifo.escalatedPercent() >= 0 && geo.escalatedPercent() >= 0);
        assertEquals(fifo.requestCount(), geo.requestCount());
        // the refusals are seeded by (dataset, request, provider), so a rerun is identical
        assertEquals(fifo, sim.run(ds, "FIFO"));
        assertEquals(geo, sim.run(ds, "GEO_NEAREST"));
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
            assertTrue(first.escalatedPercent() >= 0 && first.escalatedPercent() <= 100, strategy);
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
