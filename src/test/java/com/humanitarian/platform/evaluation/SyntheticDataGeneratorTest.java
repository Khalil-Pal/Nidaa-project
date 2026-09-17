package com.humanitarian.platform.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.service.RequestRegionResolver;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** EV-1's generator: reproducible from the seed, shaped by the spec, never touching a database. */
class SyntheticDataGeneratorTest {

    private final SyntheticDataGenerator generator = new SyntheticDataGenerator();

    @Test
    void theSameSeedGivesTheSameDatasetAndAnotherSeedAnotherOne() {
        DatasetSpec spec = DatasetSpec.study(7L, 2.0, 2);
        SyntheticDataset a = generator.generate(spec);
        SyntheticDataset b = generator.generate(spec);

        assertEquals(a.requests(), b.requests(), "request by request, field by field");
        assertEquals(a.handlingHoursByRequestId(), b.handlingHoursByRequestId());
        assertEquals(coordinates(a), coordinates(b), "providers too");

        SyntheticDataset c = generator.generate(DatasetSpec.study(8L, 2.0, 2));
        assertNotEquals(a.requests(), c.requests());
    }

    @Test
    void theSpecShapesTheDataset() {
        DatasetSpec spec = DatasetSpec.study(20260917L, 4.0, 6);
        SyntheticDataset ds = generator.generate(spec);

        assertEquals(288, ds.requests().size(), "4 per hour over 72 hours");
        assertEquals(18, ds.providers().size(), "6 per settlement, three settlements");
        for (int i = 1; i < ds.requests().size(); i++) {
            assertTrue(!ds.requests().get(i).getCreatedAt().isBefore(ds.requests().get(i - 1).getCreatedAt()), "arrival order");
            assertEquals(i + 1L, ds.requests().get(i).getId(), "ids follow the arrival order");
        }
        LocalDateTime origin = spec.origin();
        for (HelpRequest r : ds.requests()) {
            assertTrue(!r.getCreatedAt().isBefore(origin) && r.getCreatedAt().isBefore(origin.plusHours(72)), "inside the horizon");
            assertTrue(r.getLatitude() != null && r.getLongitude() != null, "every request has coordinates");
            assertTrue(r.getPeopleCount() >= 1 && r.getPeopleCount() <= 10);
            assertEquals("PENDING", r.getStatus());
            double handling = ds.handlingHoursByRequestId().get(r.getId());
            assertTrue(handling >= 1.0 && handling < 3.0, "handling time in [1, 3) h");
        }

        // the three settlements land in three distinct one-degree cells of the region resolver
        RequestRegionResolver resolver = new RequestRegionResolver();
        Map<String, Set<String>> regionsByCluster = ds.requests().stream().collect(Collectors.groupingBy(
                r -> ds.clusterByRequestId().get(r.getId()),
                Collectors.mapping(resolver::resolve, Collectors.toSet())));
        assertEquals(3, regionsByCluster.size());
        regionsByCluster.forEach((cluster, regions) -> assertEquals(1, regions.size(), cluster + " spans one region"));
        assertEquals(3, regionsByCluster.values().stream().flatMap(Set::stream).distinct().count());

        // demand follows the 50/30/20 shares, providers are spread 6/6/6
        Map<String, Long> demand = ds.requests().stream()
                .collect(Collectors.groupingBy(r -> ds.clusterByRequestId().get(r.getId()), Collectors.counting()));
        assertTrue(demand.get("Central") > demand.get("North") && demand.get("North") > demand.get("East"), demand.toString());
        Map<String, Long> supply = ds.providers().stream()
                .collect(Collectors.groupingBy(p -> ds.clusterByProviderId().get(p.getId()), Collectors.counting()));
        assertEquals(Map.of("Central", 6L, "North", 6L, "East", 6L), supply);

        // the urgency mix is the plan's 10/25/40/25 within a loose tolerance
        Map<String, Long> urgency = ds.requests().stream()
                .collect(Collectors.groupingBy(HelpRequest::getUrgencyLevel, Collectors.counting()));
        assertTrue(urgency.get("CRITICAL") >= 15 && urgency.get("CRITICAL") <= 45, urgency.toString());
        assertTrue(urgency.get("MEDIUM") > urgency.get("HIGH") && urgency.get("HIGH") > urgency.get("CRITICAL"), urgency.toString());
    }

    @Test
    void theDevSeederPresetProducesRequestsWithoutIdsUnderOneBeneficiary() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 17, 12, 0);
        List<HelpRequest> requests = generator.requests(DatasetSpec.demo(42, now), 5L);

        assertEquals(500, requests.size());
        for (HelpRequest r : requests) {
            assertNull(r.getId(), "the database assigns ids");
            assertEquals(5L, r.getBeneficiaryId());
            assertTrue(!r.getCreatedAt().isBefore(now.minusHours(168)) && r.getCreatedAt().isBefore(now), "within the last week");
            assertTrue(r.getTitle().startsWith("Seeded "));
        }
        assertEquals(requests, generator.requests(DatasetSpec.demo(42, now), 5L), "same seed, same sample data");
    }

    private static List<List<Double>> coordinates(SyntheticDataset ds) {
        return ds.providers().stream().map(p -> List.of(p.getUser().getProfile().getLatitude(),
                p.getUser().getProfile().getLongitude())).toList();
    }
}
