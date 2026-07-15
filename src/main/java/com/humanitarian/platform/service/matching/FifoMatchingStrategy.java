package com.humanitarian.platform.service.matching;

import com.humanitarian.platform.model.HelpRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Component
public class FifoMatchingStrategy implements MatchingStrategy {

    @Override
    public List<HelpRequest> rank(List<HelpRequest> requests) {
        return requests.stream()
                .sorted(Comparator.comparing(
                        HelpRequest::getCreatedAt,
                        Comparator.nullsLast(LocalDateTime::compareTo)))
                .toList();
    }

    @Override
    public String getName() {
        return "FIFO";
    }
}
