package com.humanitarian.platform.service.matching;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.service.PriorityScoreService;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class WeightedScoringStrategy implements MatchingStrategy {

    private final PriorityScoreService priorityScoreService;

    public WeightedScoringStrategy(PriorityScoreService priorityScoreService) {
        this.priorityScoreService = priorityScoreService;
    }

    @Override
    public List<HelpRequest> rank(List<HelpRequest> requests) {
        return requests.stream()
                .sorted(Comparator.comparingInt(
                        (HelpRequest request) -> priorityScoreService.calculate(request)).reversed())
                .toList();
    }

    @Override
    public String getName() {
        return "WEIGHTED_SCORING";
    }
}
