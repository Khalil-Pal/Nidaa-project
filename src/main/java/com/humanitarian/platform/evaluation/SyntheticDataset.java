package com.humanitarian.platform.evaluation;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Volunteer;
import java.util.List;
import java.util.Map;

/**
 * One generated dataset (EV-1): pending requests with ids 1..n in arrival order,
 * available volunteers with ids 1..p, and the per-request handling time the
 * simulation charges before travel. Requests and providers remember the cluster
 * they were placed in, which is how the cross-cluster share is measured.
 * Nothing here has touched the database: every priority score the strategies
 * see is computed by {@code PriorityScoreService}, the post-V15 model.
 */
public record SyntheticDataset(DatasetSpec spec,
                               List<HelpRequest> requests,
                               List<Volunteer> providers,
                               Map<Long, Double> handlingHoursByRequestId,
                               Map<Long, String> clusterByRequestId,
                               Map<Long, String> clusterByProviderId) {
}
