package com.humanitarian.platform.evaluation;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.service.GeoMatchingService;
import com.humanitarian.platform.service.PriorityWeights;
import com.humanitarian.platform.service.RequestRegionResolver;
import com.humanitarian.platform.service.matching.MatchingStrategy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

/**
 * A discrete-event simulation of dispatching over 72 hours (EV-1), modelling the
 * platform as it behaves after GAP-1 and GAP-2 — which is the only version worth
 * measuring, because those two changed what automatic matching does.
 *
 * <p><b>What happens, and why it matches production</b></p>
 * <ul>
 *   <li><b>On arrival</b> a request is offered to one provider immediately, chosen
 *       by the strategy among those free at that instant
 *       ({@code AutomaticAssignmentService.assignNearestProvider} at creation).
 *       No ordering is involved: there is one request.</li>
 *   <li><b>Every {@code sweepIntervalHours}</b> (30 minutes, the schedule of
 *       {@code StaleRequestScheduler}) the pending queue is ranked by the strategy
 *       and placed greedily against the free providers. <b>This is the only place
 *       the priority model orders anything</b>, in the simulation as in production.</li>
 *   <li><b>A provider becoming free changes nothing until the next sweep</b>, which
 *       is exactly the platform's behaviour — and one of the costs this study can
 *       now quantify.</li>
 *   <li><b>A provider may decline</b> ({@code declineProbability}, GAP-1): the
 *       request goes straight back on the queue, the provider is free again at
 *       once, and that pair is never offered again. After {@code maxDeclines} the
 *       request is escalated: it is counted as needing a human, and the sweep
 *       keeps offering it only to providers who have not refused it.</li>
 *   <li><b>Service</b> costs the request's handling time plus the round trip at
 *       {@code speedKmh}; the provider returns home.</li>
 *   <li><b>Drain.</b> After the last arrival the sweeps continue until the queue is
 *       empty, so no waiting time is censored, except for a request every eligible
 *       provider has refused: nothing later can place it, so the drain stops and it
 *       is reported as unassigned. Waiting times are over the requests that were
 *       assigned; throughput is the share completed inside the horizon, over all of
 *       them.</li>
 * </ul>
 *
 * <p>Whether a given provider declines a given request is a deterministic function
 * of the dataset seed and the two ids, not of the order in which a strategy
 * happens to try them. Two strategies therefore meet the same refusals on the
 * same dataset, which keeps the paired design honest, and a run is reproducible.</p>
 */
public final class MatchingSimulation {

    /**
     * @param speedKmh             travel speed for the round trip
     * @param urgentWindowHours    the service level HIGH and CRITICAL requests are measured against
     * @param regionalWindowHours  the window the regional fairness index is computed over
     * @param sweepIntervalHours   how often the retry sweep runs (GAP-2: every 30 minutes)
     * @param declineProbability   chance that a provider offered a request refuses it (GAP-1)
     * @param maxDeclines          declines after which the request is escalated to a human (GAP-1)
     */
    public record Settings(double speedKmh, double urgentWindowHours, double regionalWindowHours,
                           double sweepIntervalHours, double declineProbability, int maxDeclines) {
        public static final Settings DEFAULT = new Settings(40.0, 6.0, 24.0, 0.5, 0.10, 3);

        public Settings withDeclineProbability(double probability) {
            return new Settings(speedKmh, urgentWindowHours, regionalWindowHours,
                    sweepIntervalHours, probability, maxDeclines);
        }

        public Settings withSweepIntervalHours(double hours) {
            return new Settings(speedKmh, urgentWindowHours, regionalWindowHours,
                    hours, declineProbability, maxDeclines);
        }
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

    public Settings settings() {
        return settings;
    }

    /** A provider waiting at home, with the hour they became free (used to break ties fairly). */
    private record Free(Volunteer volunteer, double freeSince) {
    }

    private record Release(double time, Volunteer volunteer) {
    }

    public RunMetrics run(SyntheticDataset dataset, String strategyName) {
        return run(dataset, strategyName, PriorityWeights.DEFAULT);
    }

    public RunMetrics run(SyntheticDataset dataset, String strategyName, PriorityWeights weights) {
        LocalDateTime origin = dataset.spec().origin();
        SimulationClock clock = new SimulationClock(origin);
        MatchingStrategy strategy = StudyStrategies.create(strategyName, clock, weights);

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
        int[] declines = new int[n];
        Arrays.fill(assignedAt, Double.NaN);

        Map<Long, Double> busyHours = new TreeMap<>();
        dataset.providers().forEach(p -> busyHours.put(p.getId(), 0.0));
        Map<Long, Set<Long>> refusedBy = new HashMap<>();    // request id -> provider ids who declined

        List<HelpRequest> pending = new ArrayList<>();
        List<Free> free = new ArrayList<>();
        dataset.providers().forEach(p -> free.add(new Free(p, 0.0)));
        PriorityQueue<Release> releases = new PriorityQueue<>(
                Comparator.comparingDouble(Release::time).thenComparing(r -> r.volunteer().getId()));

        int next = 0;
        double sweepAt = settings.sweepIntervalHours();
        while (next < n || !pending.isEmpty() || !releases.isEmpty()) {
            double nextArrival = next < n ? arrival[next] : Double.POSITIVE_INFINITY;
            double nextRelease = releases.isEmpty() ? Double.POSITIVE_INFINITY : releases.peek().time();
            double nextSweep = pending.isEmpty() && next >= n ? Double.POSITIVE_INFINITY : sweepAt;
            double t = Math.min(nextArrival, Math.min(nextRelease, nextSweep));
            if (Double.isInfinite(t)) {
                break;
            }
            clock.set(origin.plusMinutes(Math.round(t * 60)));

            // the epsilon matters: a provider freed a hair before a sweep (floating-point
            // service times against accumulated tick times) must be seen by that sweep
            while (!releases.isEmpty() && releases.peek().time() <= t + 1e-9) {
                free.add(new Free(releases.poll().volunteer(), t));
            }

            boolean arrivedNow = false;
            while (next < n && arrival[next] <= t) {
                pending.add(arrivals.get(next));
                next++;
                arrivedNow = true;
            }

            // On arrival: each new request is offered to one provider, alone (no ordering).
            if (arrivedNow) {
                for (HelpRequest candidate : List.copyOf(pending)) {
                    int i = indexById.get(candidate.getId());
                    if (!Double.isNaN(assignedAt[i]) || arrival[i] < t) {
                        continue;      // only the ones that arrived at this instant
                    }
                    place(candidate, t, dataset, strategy, free, releases, busyHours, refusedBy,
                            indexById, assignedAt, serviceHours, distanceKm, crossCluster, declines, pending);
                }
            }

            // The sweep: the queue is ranked and placed greedily (GAP-2).
            if (t >= sweepAt - 1e-9) {
                sweepAt += settings.sweepIntervalHours();
                boolean placedAny = false;
                boolean placedSomething = true;
                while (placedSomething && !pending.isEmpty() && !free.isEmpty()) {
                    placedSomething = false;
                    free.sort(Comparator.comparingDouble(Free::freeSince).thenComparing(f -> f.volunteer().getId()));
                    List<Volunteer> freeVolunteers = free.stream().map(Free::volunteer).toList();
                    for (HelpRequest candidate : strategy.rank(List.copyOf(pending), freeVolunteers)) {
                        if (place(candidate, t, dataset, strategy, free, releases, busyHours, refusedBy,
                                indexById, assignedAt, serviceHours, distanceKm, crossCluster, declines, pending)) {
                            placedSomething = true;
                            placedAny = true;
                            break;     // re-rank after every assignment, as the platform re-reads the queue
                        }
                    }
                }
                // Nothing left to change the answer: no arrivals, nobody out on a job, and this
                // sweep placed nothing. Whatever is still queued has been refused by every
                // provider who could take it, and no later sweep can do better. It is reported
                // as unassigned rather than looped over for ever.
                if (!placedAny && next >= n && releases.isEmpty() && !pending.isEmpty()) {
                    break;
                }
            }
        }

        return metrics(dataset, arrivals, arrival, assignedAt, serviceHours, distanceKm, crossCluster,
                declines, busyHours);
    }

    /**
     * Offers one request to the provider the strategy picks among those free and not
     * already refused by. A decline costs nothing but the offer: the provider stays
     * free and the pair is remembered. Returns true when the request was taken.
     */
    private boolean place(HelpRequest request, double t, SyntheticDataset dataset, MatchingStrategy strategy,
                          List<Free> free, PriorityQueue<Release> releases, Map<Long, Double> busyHours,
                          Map<Long, Set<Long>> refusedBy, Map<Long, Integer> indexById,
                          double[] assignedAt, double[] serviceHours, double[] distanceKm,
                          boolean[] crossCluster, int[] declines, List<HelpRequest> pending) {
        Set<Long> refused = refusedBy.getOrDefault(request.getId(), Set.of());
        List<Volunteer> candidates = free.stream()
                .map(Free::volunteer)
                .filter(v -> !refused.contains(v.getId()))
                .toList();
        if (candidates.isEmpty()) {
            return false;
        }
        Optional<Volunteer> selected = strategy.selectVolunteer(request, candidates);
        if (selected.isEmpty()) {
            return false;
        }
        Volunteer provider = selected.get();
        int i = indexById.get(request.getId());

        if (declinesOffer(dataset.spec().seed(), request.getId(), provider.getId())) {
            declines[i]++;
            refusedBy.computeIfAbsent(request.getId(), id -> new HashSet<>()).add(provider.getId());
            return false;      // back on the queue at once; the provider is still free
        }

        double distance = distance(request, provider);
        double service = dataset.handlingHoursByRequestId().get(request.getId()) + 2 * distance / settings.speedKmh();
        assignedAt[i] = t;
        serviceHours[i] = service;
        distanceKm[i] = distance;
        crossCluster[i] = !Objects.equals(dataset.clusterByRequestId().get(request.getId()),
                dataset.clusterByProviderId().get(provider.getId()));
        busyHours.merge(provider.getId(), service, Double::sum);
        releases.add(new Release(t + service, provider));
        final Long providerId = provider.getId();
        final Long requestId = request.getId();
        free.removeIf(f -> Objects.equals(f.volunteer().getId(), providerId));
        pending.removeIf(r -> Objects.equals(r.getId(), requestId));
        return true;
    }

    /**
     * Whether this provider refuses this request — a property of the pair and the
     * dataset, not of the order a strategy tried them in, so every strategy meets
     * the same refusals on the same dataset.
     */
    private boolean declinesOffer(long seed, long requestId, long providerId) {
        if (settings.declineProbability() <= 0) {
            return false;
        }
        long h = seed * 1_000_003L + requestId * 31L + providerId;
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        double uniform = (h >>> 11) / (double) (1L << 53);
        return uniform < settings.declineProbability();
    }

    private RunMetrics metrics(SyntheticDataset dataset, List<HelpRequest> requests, double[] arrival,
                               double[] assignedAt, double[] serviceHours, double[] distanceKm,
                               boolean[] crossCluster, int[] declines, Map<Long, Double> busyHours) {
        int n = requests.size();
        double horizon = dataset.spec().horizonHours();
        List<Double> criticalWaits = new ArrayList<>();
        List<Double> allWaits = new ArrayList<>();
        int urgent = 0;
        int urgentInWindow = 0;
        int completedInHorizon = 0;
        int cross = 0;
        int escalated = 0;
        int unassigned = 0;
        int assignedCount = 0;
        double distanceSum = 0;
        Map<String, int[]> perRegion = new TreeMap<>();    // [served within the regional window, total]
        for (int i = 0; i < n; i++) {
            HelpRequest r = requests.get(i);
            boolean assigned = !Double.isNaN(assignedAt[i]);
            double wait = assigned ? assignedAt[i] - arrival[i] : Double.NaN;
            String urgency = r.getUrgencyLevel();
            if (assigned) {
                assignedCount++;
                allWaits.add(wait);
                if ("CRITICAL".equals(urgency)) {
                    criticalWaits.add(wait);
                }
                if (assignedAt[i] + serviceHours[i] <= horizon) {
                    completedInHorizon++;
                }
                if (crossCluster[i]) {
                    cross++;
                }
                distanceSum += distanceKm[i];
            } else {
                unassigned++;
            }
            // Service levels count every request: one nobody took has missed its window.
            if ("CRITICAL".equals(urgency) || "HIGH".equals(urgency)) {
                urgent++;
                if (assigned && wait <= settings.urgentWindowHours()) {
                    urgentInWindow++;
                }
            }
            if (declines[i] >= settings.maxDeclines()) {
                escalated++;
            }
            int[] counts = perRegion.computeIfAbsent(regions.resolve(r), k -> new int[2]);
            counts[1]++;
            if (assigned && wait <= settings.regionalWindowHours()) {
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
                assignedCount == 0 ? 0 : distanceSum / assignedCount,
                assignedCount == 0 ? 0 : 100.0 * cross / assignedCount,
                n == 0 ? 0 : 100.0 * completedInHorizon / n,
                jain(coverage),
                n == 0 ? 0 : 100.0 * escalated / n,
                n == 0 ? 0 : 100.0 * unassigned / n,
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
