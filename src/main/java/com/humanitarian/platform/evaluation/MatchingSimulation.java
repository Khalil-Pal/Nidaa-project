package com.humanitarian.platform.evaluation;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.service.GeoMatchingService;
import com.humanitarian.platform.service.RequestRegionResolver;
import com.humanitarian.platform.service.matching.MatchingStrategy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.TreeMap;

/**
 * A discrete-event simulation of dispatching (EV-1). Requests arrive at their
 * {@code createdAt}; providers start free at home. At every decision point — an
 * arrival or a provider becoming free — the strategy ranks the pending queue
 * against the free providers and the first request it can staff is assigned to
 * the provider it selects; this repeats until the queue or the free list is
 * empty. Service takes the request's handling time plus travel there and back
 * at {@code speedKmh}, after which the provider is free again at home. After the
 * last arrival the queue is drained, so every request is assigned and no waiting
 * time is censored; "completed within the horizon" is what measures throughput.
 *
 * FIFO and WEIGHTED_SCORING keep the interface's default provider selection —
 * the first free provider in the list — and the list is ordered by how long a
 * provider has been idle, so they are pure ordering policies over requests.
 * GEO_NEAREST and MULTI_OBJECTIVE_OPTIMIZATION select the nearest free provider.
 *
 * Deterministic: a dataset and a strategy name give the same metrics on every
 * run. Ties are broken by id or creation time everywhere.
 */
public final class MatchingSimulation {

    /** Travel speed and the two service-level windows the metrics use. */
    public record Settings(double speedKmh, double urgentWindowHours, double regionalWindowHours) {
        public static final Settings DEFAULT = new Settings(40.0, 6.0, 24.0);
    }

    private final GeoMatchingService geo = new GeoMatchingService();
    private final RequestRegionResolver regions = new RequestRegionResolver();
    private final Settings settings;

    public MatchingSimulation(Settings settings) {
        this.settings = settings;
    }

    public MatchingSimulation() {
        this(Settings.DEFAULT);
    }

    /** A provider waiting at home since {@code freeSince} hours. */
    private record Free(Volunteer volunteer, double freeSince) {
    }

    private record Release(double time, Volunteer volunteer) {
    }

    public RunMetrics run(SyntheticDataset dataset, String strategyName) {
        LocalDateTime origin = dataset.spec().origin();
        SimulationClock clock = new SimulationClock(origin);
        MatchingStrategy strategy = StudyStrategies.create(strategyName, clock);

        List<HelpRequest> arrivals = dataset.requests();     // already in arrival order
        int n = arrivals.size();
        double[] arrival = new double[n];
        for (int i = 0; i < n; i++) {
            arrival[i] = hours(origin, arrivals.get(i).getCreatedAt());
        }
        Map<Long, Integer> indexById = new HashMap<>();
        for (int i = 0; i < n; i++) {
            indexById.put(arrivals.get(i).getId(), i);
        }
        double[] assignedAt = new double[n];
        double[] serviceHours = new double[n];
        double[] distanceKm = new double[n];
        boolean[] crossCluster = new boolean[n];
        Arrays.fill(assignedAt, Double.NaN);

        Map<Long, Double> busyHours = new TreeMap<>();
        dataset.providers().forEach(p -> busyHours.put(p.getId(), 0.0));

        List<HelpRequest> pending = new ArrayList<>();
        List<Free> free = new ArrayList<>();
        dataset.providers().forEach(p -> free.add(new Free(p, 0.0)));
        PriorityQueue<Release> releases = new PriorityQueue<>(
                Comparator.comparingDouble(Release::time).thenComparing(r -> r.volunteer().getId()));

        int next = 0;
        while (next < n || !releases.isEmpty()) {
            double nextArrival = next < n ? arrival[next] : Double.POSITIVE_INFINITY;
            double nextRelease = releases.isEmpty() ? Double.POSITIVE_INFINITY : releases.peek().time();
            double t = Math.min(nextArrival, nextRelease);
            while (next < n && arrival[next] <= t) {
                pending.add(arrivals.get(next));
                next++;
            }
            while (!releases.isEmpty() && releases.peek().time() <= t) {
                free.add(new Free(releases.poll().volunteer(), t));
            }

            // dispatch: one assignment per ranking until nothing more can be staffed
            clock.set(origin.plusMinutes(Math.round(t * 60)));
            while (!pending.isEmpty() && !free.isEmpty()) {
                free.sort(Comparator.comparingDouble(Free::freeSince).thenComparing(f -> f.volunteer().getId()));
                List<Volunteer> freeVolunteers = free.stream().map(Free::volunteer).toList();
                List<HelpRequest> ranked = strategy.rank(List.copyOf(pending), freeVolunteers);
                HelpRequest chosen = null;
                Volunteer provider = null;
                for (HelpRequest candidate : ranked) {
                    Optional<Volunteer> selected = strategy.selectVolunteer(candidate, freeVolunteers);
                    if (selected.isPresent()) {
                        chosen = candidate;
                        provider = selected.get();
                        break;
                    }
                }
                if (chosen == null) {
                    break;
                }
                int i = indexById.get(chosen.getId());
                double distance = distance(chosen, provider);
                double service = dataset.handlingHoursByRequestId().get(chosen.getId())
                        + 2 * distance / settings.speedKmh();
                assignedAt[i] = t;
                serviceHours[i] = service;
                distanceKm[i] = distance;
                crossCluster[i] = !Objects.equals(dataset.clusterByRequestId().get(chosen.getId()),
                        dataset.clusterByProviderId().get(provider.getId()));
                busyHours.merge(provider.getId(), service, Double::sum);
                releases.add(new Release(t + service, provider));
                final Long chosenId = chosen.getId();
                final Long providerId = provider.getId();
                pending.removeIf(r -> Objects.equals(r.getId(), chosenId));
                free.removeIf(f -> Objects.equals(f.volunteer().getId(), providerId));
            }
        }

        return metrics(dataset, arrivals, arrival, assignedAt, serviceHours, distanceKm, crossCluster, busyHours);
    }

    private RunMetrics metrics(SyntheticDataset dataset, List<HelpRequest> requests, double[] arrival,
                               double[] assignedAt, double[] serviceHours, double[] distanceKm,
                               boolean[] crossCluster, Map<Long, Double> busyHours) {
        int n = requests.size();
        double horizon = dataset.spec().horizonHours();
        List<Double> criticalWaits = new ArrayList<>();
        List<Double> allWaits = new ArrayList<>();
        int urgent = 0;
        int urgentInWindow = 0;
        int completedInHorizon = 0;
        int cross = 0;
        double distanceSum = 0;
        Map<String, int[]> perRegion = new TreeMap<>();    // [served within the regional window, total]
        for (int i = 0; i < n; i++) {
            HelpRequest r = requests.get(i);
            double wait = assignedAt[i] - arrival[i];
            allWaits.add(wait);
            String urgency = r.getUrgencyLevel();
            if ("CRITICAL".equals(urgency)) {
                criticalWaits.add(wait);
            }
            if ("CRITICAL".equals(urgency) || "HIGH".equals(urgency)) {
                urgent++;
                if (wait <= settings.urgentWindowHours()) {
                    urgentInWindow++;
                }
            }
            if (assignedAt[i] + serviceHours[i] <= horizon) {
                completedInHorizon++;
            }
            if (crossCluster[i]) {
                cross++;
            }
            distanceSum += distanceKm[i];
            int[] counts = perRegion.computeIfAbsent(regions.resolve(r), k -> new int[2]);
            counts[1]++;
            if (wait <= settings.regionalWindowHours()) {
                counts[0]++;
            }
        }
        double[] coverage = perRegion.values().stream().mapToDouble(c -> c[0] / (double) c[1]).toArray();
        return new RunMetrics(
                mean(criticalWaits),
                percentile(criticalWaits, 0.95),
                urgent == 0 ? 0 : 100.0 * urgentInWindow / urgent,
                mean(allWaits),
                percentile(allWaits, 0.95),
                gini(busyHours.values().stream().mapToDouble(Double::doubleValue).toArray()),
                n == 0 ? 0 : distanceSum / n,
                n == 0 ? 0 : 100.0 * cross / n,
                n == 0 ? 0 : 100.0 * completedInHorizon / n,
                jain(coverage),
                dataset.providers().size(),
                n);
    }

    // ---- arithmetic -------------------------------------------------------------

    static double hours(LocalDateTime origin, LocalDateTime at) {
        return Duration.between(origin, at).toMinutes() / 60.0;
    }

    private double distance(HelpRequest request, Volunteer volunteer) {
        return geo.haversine(request.getLatitude(), request.getLongitude(),
                volunteer.getUser().getProfile().getLatitude(), volunteer.getUser().getProfile().getLongitude());
    }

    static double mean(List<Double> values) {
        return values.isEmpty() ? 0 : values.stream().mapToDouble(Double::doubleValue).sum() / values.size();
    }

    /** Nearest-rank percentile: the value at position ceil(p·n) of the sorted sample. */
    static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) {
            return 0;
        }
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int rank = (int) Math.ceil(p * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    /** Gini coefficient of a non-negative sample; 0 when everything is equal (or empty). */
    static double gini(double[] values) {
        if (values.length == 0) {
            return 0;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double sum = 0;
        double weighted = 0;
        for (int i = 0; i < sorted.length; i++) {
            sum += sorted[i];
            weighted += (i + 1) * sorted[i];
        }
        if (sum == 0) {
            return 0;
        }
        int m = sorted.length;
        return (2 * weighted) / (m * sum) - (m + 1.0) / m;
    }

    /** Jain's fairness index over rates: (Σx)² / (k·Σx²); 1 when all rates are equal. */
    static double jain(double[] rates) {
        if (rates.length == 0) {
            return 1;
        }
        double sum = 0;
        double squares = 0;
        for (double x : rates) {
            sum += x;
            squares += x * x;
        }
        return squares == 0 ? 0 : (sum * sum) / (rates.length * squares);
    }
}
