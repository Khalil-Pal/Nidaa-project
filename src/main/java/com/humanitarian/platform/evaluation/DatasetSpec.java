package com.humanitarian.platform.evaluation;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Parameters of one synthetic dataset (EV-1): everything the generator needs to
 * produce the same requests and providers again from the same seed. The dev
 * seeder's sample data ({@link #demo}) and the matching study's cells
 * ({@link #study}) are two presets of the same generator.
 *
 * @param seed               the random seed; equal specs produce equal datasets
 * @param requestCount       how many requests arrive over the horizon
 * @param horizonHours       arrivals are spread uniformly over [0, horizon) hours after {@code origin}
 * @param origin             the instant of hour 0 (the dev seeder uses "168 hours ago", the study a fixed date)
 * @param clusters           where requests and providers are placed, and each cluster's share of requests
 * @param providersPerCluster how many volunteers each cluster gets (0 for the dev seeder, which creates none)
 * @param urgency            weights of CRITICAL, HIGH, MEDIUM, LOW in that order (need not sum to 100)
 * @param childrenRate       share of requests with children, elderly, disabled
 * @param maxPeople          people_count is uniform in 1..maxPeople
 * @param handlingHoursMin   on-site handling time per request, uniform in [min, max) hours (the simulation's service time before travel)
 */
public record DatasetSpec(long seed,
                          int requestCount,
                          double horizonHours,
                          LocalDateTime origin,
                          List<Cluster> clusters,
                          int providersPerCluster,
                          int[] urgency,
                          double childrenRate,
                          double elderlyRate,
                          double disabledRate,
                          int maxPeople,
                          double handlingHoursMin,
                          double handlingHoursMax) {

    /** A settlement: requests and providers fall uniformly in the square of side {@code spreadDegrees} around the centre. */
    public record Cluster(String name, double latitude, double longitude, double spreadDegrees, double requestShare) {
    }

    /** The plan's weighting: 10 % CRITICAL, 25 % HIGH, 40 % MEDIUM, 25 % LOW. */
    public static final int[] PLAN_URGENCY = {10, 25, 40, 25};

    /** The dev seeder's three cities, kept for the map demo. */
    public static final List<Cluster> DEMO_CITIES = List.of(
            new Cluster("Moscow", 55.75, 37.62, 0.5, 1.0),
            new Cluster("Saint Petersburg", 59.93, 30.32, 0.5, 1.0),
            new Cluster("Yekaterinburg", 56.85, 60.61, 0.5, 1.0));

    /**
     * The study's region: three settlements of one oblast, 60–85 km apart, each in
     * its own one-degree cell of {@code RequestRegionResolver} so the regional
     * metrics and the multi-objective strategy's regional term see three regions.
     * Demand is uneven on purpose (50/30/20 %) while providers are spread evenly,
     * so the central settlement is under-provisioned relative to its demand.
     */
    public static final List<Cluster> STUDY_REGION = List.of(
            new Cluster("Central", 55.60, 37.40, 0.3, 0.5),
            new Cluster("North", 56.30, 37.60, 0.3, 0.3),
            new Cluster("East", 55.70, 38.40, 0.3, 0.2));

    /** What {@code DataSeeder} inserts on a dev profile: 500 pending requests over the last week. */
    public static DatasetSpec demo(long seed, LocalDateTime now) {
        return new DatasetSpec(seed, 500, 168, now.minusHours(168), DEMO_CITIES, 0,
                PLAN_URGENCY, 0.30, 0.20, 0.15, 10, 1.0, 3.0);
    }

    /** One cell's dataset for the study: a 72-hour horizon at a given arrival rate and provider density. */
    public static DatasetSpec study(long seed, double arrivalsPerHour, int providersPerCluster) {
        double horizon = 72;
        return new DatasetSpec(seed, (int) Math.round(arrivalsPerHour * horizon), horizon,
                LocalDateTime.of(2026, 1, 1, 0, 0), STUDY_REGION, providersPerCluster,
                PLAN_URGENCY, 0.30, 0.20, 0.15, 10, 1.0, 3.0);
    }
}
