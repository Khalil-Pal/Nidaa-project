package com.humanitarian.platform.evaluation;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Profile;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The parameterised generator behind {@code DataSeeder} and the matching study
 * (EV-1). Everything is drawn from one {@link Random} seeded by the spec, in a
 * fixed order, so the same spec yields the same dataset on every run and on
 * every machine. No Spring, no database: the objects are plain entities used
 * as data holders.
 */
public final class SyntheticDataGenerator {

    private static final String[] TYPES = {"MEDICAL", "FOOD", "SHELTER", "WATER", "CLOTHING"};
    private static final String[] URGENCY = {"CRITICAL", "HIGH", "MEDIUM", "LOW"};

    /** Requests only, without ids, for the dev seeder to persist under one beneficiary. */
    public List<HelpRequest> requests(DatasetSpec spec, Long beneficiaryId) {
        Random random = new Random(spec.seed());
        List<HelpRequest> requests = new ArrayList<>(spec.requestCount());
        for (int i = 0; i < spec.requestCount(); i++) {
            requests.add(request(spec, random, i, beneficiaryId).request());
        }
        return requests;
    }

    /** A full dataset for the simulation: requests in arrival order with ids, providers, handling times. */
    public SyntheticDataset generate(DatasetSpec spec) {
        Random random = new Random(spec.seed());
        List<Drawn> drawn = new ArrayList<>(spec.requestCount());
        for (int i = 0; i < spec.requestCount(); i++) {
            drawn.add(request(spec, random, i, null));
        }
        // arrival order, then the draw index so equal minutes keep a fixed order
        drawn.sort(Comparator.comparing((Drawn d) -> d.request().getCreatedAt()).thenComparingInt(Drawn::index));

        List<HelpRequest> requests = new ArrayList<>(drawn.size());
        Map<Long, Double> handling = new LinkedHashMap<>();
        Map<Long, String> requestClusters = new LinkedHashMap<>();
        long id = 1;
        for (Drawn d : drawn) {
            d.request().setId(id);
            requests.add(d.request());
            handling.put(id, d.handlingHours());
            requestClusters.put(id, d.cluster());
            id++;
        }

        List<Volunteer> providers = new ArrayList<>();
        Map<Long, String> providerClusters = new LinkedHashMap<>();
        long providerId = 1;
        for (DatasetSpec.Cluster cluster : spec.clusters()) {
            for (int k = 0; k < spec.providersPerCluster(); k++) {
                double latitude = cluster.latitude() + (random.nextDouble() - 0.5) * cluster.spreadDegrees();
                double longitude = cluster.longitude() + (random.nextDouble() - 0.5) * cluster.spreadDegrees();
                providers.add(provider(providerId, latitude, longitude));
                providerClusters.put(providerId, cluster.name());
                providerId++;
            }
        }
        return new SyntheticDataset(spec, requests, providers, handling, requestClusters, providerClusters);
    }

    private record Drawn(int index, HelpRequest request, double handlingHours, String cluster) {
    }

    private Drawn request(DatasetSpec spec, Random random, int index, Long beneficiaryId) {
        DatasetSpec.Cluster cluster = pickCluster(spec.clusters(), random);
        String urgency = weighted(URGENCY, spec.urgency(), random);
        String type = TYPES[random.nextInt(TYPES.length)];
        int people = random.nextInt(spec.maxPeople()) + 1;
        boolean children = random.nextDouble() < spec.childrenRate();
        boolean elderly = random.nextDouble() < spec.elderlyRate();
        boolean disabled = random.nextDouble() < spec.disabledRate();
        double latitude = cluster.latitude() + (random.nextDouble() - 0.5) * cluster.spreadDegrees();
        double longitude = cluster.longitude() + (random.nextDouble() - 0.5) * cluster.spreadDegrees();
        long arrivalMinutes = (long) Math.floor(random.nextDouble() * spec.horizonHours() * 60);
        double handling = spec.handlingHoursMin()
                + random.nextDouble() * (spec.handlingHoursMax() - spec.handlingHoursMin());

        HelpRequest request = HelpRequest.builder()
                .beneficiaryId(beneficiaryId)
                .title("Seeded " + urgency.toLowerCase() + " request #" + index)
                .helpType(type)
                .urgencyLevel(urgency)
                .description("Seeded request #" + index)
                .peopleCount(people)
                .hasChildren(children)
                .hasElderly(elderly)
                .hasDisabled(disabled)
                .latitude(latitude)
                .longitude(longitude)
                .status("PENDING")
                .createdAt(spec.origin().plusMinutes(arrivalMinutes))
                .build();
        return new Drawn(index, request, handling, cluster.name());
    }

    private static DatasetSpec.Cluster pickCluster(List<DatasetSpec.Cluster> clusters, Random random) {
        double total = clusters.stream().mapToDouble(DatasetSpec.Cluster::requestShare).sum();
        double point = random.nextDouble() * total;
        double seen = 0;
        for (DatasetSpec.Cluster cluster : clusters) {
            seen += cluster.requestShare();
            if (point < seen) {
                return cluster;
            }
        }
        return clusters.get(clusters.size() - 1);
    }

    private static String weighted(String[] values, int[] weights, Random random) {
        int total = 0;
        for (int w : weights) {
            total += w;
        }
        int point = random.nextInt(total);
        int seen = 0;
        for (int i = 0; i < values.length; i++) {
            seen += weights[i];
            if (point < seen) {
                return values[i];
            }
        }
        return values[values.length - 1];
    }

    /** A volunteer as {@code GeoMatchingService} needs one: available, with a profile that has coordinates. */
    private static Volunteer provider(long id, double latitude, double longitude) {
        User user = User.builder().id(id).fullName("Provider " + id).role(UserRole.VOLUNTEER).build();
        user.setProfile(Profile.builder().user(user).latitude(latitude).longitude(longitude).build());
        return Volunteer.builder().id(id).user(user).isAvailable(true).availabilityPreference(true).build();
    }
}
