package com.humanitarian.platform.service;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.service.matching.MatchingStrategy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MatchingEvaluationService {

    private final List<MatchingStrategy> strategies;

    public MatchingEvaluationService(List<MatchingStrategy> strategies) {
        this.strategies = strategies;
    }

    public Map<String, Object> evaluate(List<HelpRequest> requests) {
        Map<String, Object> results = new LinkedHashMap<>();
        for (MatchingStrategy strategy : strategies) {
            List<HelpRequest> ranked = strategy.rank(requests);
            Map<String, Object> metrics = new HashMap<>();

            List<Integer> criticalPositions = new ArrayList<>();
            for (int i = 0; i < ranked.size(); i++) {
                if ("CRITICAL".equals(ranked.get(i).getUrgencyLevel())) {
                    criticalPositions.add(i + 1);
                }
            }

            double avgCriticalPosition = criticalPositions.stream()
                    .mapToInt(Integer::intValue)
                    .average()
                    .orElse(0);

            metrics.put("avgCriticalPosition", avgCriticalPosition);
            metrics.put("totalRequests", ranked.size());
            results.put(strategy.getName(), metrics);
        }
        return results;
    }
}
