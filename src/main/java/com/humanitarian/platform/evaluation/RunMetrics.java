package com.humanitarian.platform.evaluation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The dependent variables of one simulation run (EV-1). Waiting time is hours
 * from arrival to assignment; every request is assigned eventually because the
 * simulation drains the queue after the last arrival, so no waiting time is
 * censored.
 *
 * @param criticalMeanWaitHours       mean waiting time of CRITICAL requests
 * @param criticalP95WaitHours        95th percentile of the same
 * @param urgentWithinWindowPercent   share of HIGH and CRITICAL requests assigned within the urgent window (6 h)
 * @param meanWaitHours               mean waiting time over all requests
 * @param p95WaitHours                95th percentile over all requests
 * @param utilisationGini             Gini coefficient of busy hours across providers (0 = equal load, 1 = one provider does everything)
 * @param meanDistanceKm              mean straight-line distance of an assignment
 * @param crossClusterPercent         share of assignments whose provider lives in another settlement than the request
 * @param completedWithinHorizonPercent share of requests whose delivery finished inside the 72-hour horizon
 * @param regionalFairnessJain        Jain's index over the per-region share of requests assigned within 24 h (1 = every region served alike)
 * @param escalatedPercent            share of requests that were refused by three providers and went to a human (GAP-1)
 * @param unassignedPercent           share that every eligible provider refused, so nobody ever took them
 * @param providerCount               providers in the run (context, not a dependent variable)
 * @param requestCount                requests in the run
 */
public record RunMetrics(double criticalMeanWaitHours,
                         double criticalP95WaitHours,
                         double urgentWithinWindowPercent,
                         double meanWaitHours,
                         double p95WaitHours,
                         double utilisationGini,
                         double meanDistanceKm,
                         double crossClusterPercent,
                         double completedWithinHorizonPercent,
                         double regionalFairnessJain,
                         double escalatedPercent,
                         double unassignedPercent,
                         int providerCount,
                         int requestCount) {

    /** The dependent variables in report order, by column name. */
    public Map<String, Double> values() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("critical_mean_wait_h", criticalMeanWaitHours);
        m.put("critical_p95_wait_h", criticalP95WaitHours);
        m.put("urgent_within_6h_pct", urgentWithinWindowPercent);
        m.put("mean_wait_h", meanWaitHours);
        m.put("p95_wait_h", p95WaitHours);
        m.put("utilisation_gini", utilisationGini);
        m.put("mean_distance_km", meanDistanceKm);
        m.put("cross_cluster_pct", crossClusterPercent);
        m.put("completed_within_horizon_pct", completedWithinHorizonPercent);
        m.put("regional_fairness_jain", regionalFairnessJain);
        m.put("escalated_pct", escalatedPercent);
        m.put("unassigned_pct", unassignedPercent);
        return m;
    }

    public static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("critical_mean_wait_h", "CRITICAL mean waiting time (h)"),
            Map.entry("critical_p95_wait_h", "CRITICAL p95 waiting time (h)"),
            Map.entry("urgent_within_6h_pct", "Urgent requests assigned within 6 h (%)"),
            Map.entry("mean_wait_h", "Mean waiting time, all requests (h)"),
            Map.entry("p95_wait_h", "p95 waiting time, all requests (h)"),
            Map.entry("utilisation_gini", "Provider utilisation Gini (0 = equal)"),
            Map.entry("mean_distance_km", "Mean travel distance (km)"),
            Map.entry("cross_cluster_pct", "Assignments across settlements (%)"),
            Map.entry("completed_within_horizon_pct", "Completed within the 72 h horizon (%)"),
            Map.entry("regional_fairness_jain", "Regional fairness, Jain index (1 = equal)"),
            Map.entry("escalated_pct", "Escalated to a human after three declines (%)"),
            Map.entry("unassigned_pct", "Never taken by anyone (%)"));
}
