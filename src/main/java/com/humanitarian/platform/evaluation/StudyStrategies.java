package com.humanitarian.platform.evaluation;

import com.humanitarian.platform.service.GeoMatchingService;
import com.humanitarian.platform.service.PriorityScoreService;
import com.humanitarian.platform.service.PriorityWeights;
import com.humanitarian.platform.service.RequestRegionResolver;
import com.humanitarian.platform.service.matching.FifoMatchingStrategy;
import com.humanitarian.platform.service.matching.GeoNearestStrategy;
import com.humanitarian.platform.service.matching.MatchingStrategy;
import com.humanitarian.platform.service.matching.OptimizedMatchingStrategy;
import com.humanitarian.platform.service.matching.WeightedScoringStrategy;
import java.time.Clock;
import java.util.List;

/**
 * The four strategy levels of the study, built on a simulated clock. They are
 * the production classes, not copies: the only difference from the Spring
 * singletons is the clock behind {@code PriorityScoreService}, which is what
 * lets the priority model age a request in simulated time.
 */
public final class StudyStrategies {

    public static final List<String> NAMES = List.of(
            "FIFO", "WEIGHTED_SCORING", "GEO_NEAREST", "MULTI_OBJECTIVE_OPTIMIZATION");

    private StudyStrategies() {
    }

    public static MatchingStrategy create(String name, Clock clock) {
        return create(name, clock, PriorityWeights.DEFAULT);
    }

    public static MatchingStrategy create(String name, Clock clock, PriorityWeights weights) {
        PriorityScoreService priority = new PriorityScoreService(clock, weights);
        GeoMatchingService geo = new GeoMatchingService();
        RequestRegionResolver regions = new RequestRegionResolver();
        return switch (name) {
            case "FIFO" -> new FifoMatchingStrategy();
            case "WEIGHTED_SCORING" -> new WeightedScoringStrategy(priority);
            case "GEO_NEAREST" -> new GeoNearestStrategy(geo);
            case "MULTI_OBJECTIVE_OPTIMIZATION" -> new OptimizedMatchingStrategy(priority, geo, regions);
            default -> throw new IllegalArgumentException("Unknown strategy: " + name);
        };
    }
}
