package com.humanitarian.platform.service;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.repository.HelpRequestRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class PriorityScoreScheduler {

    private final HelpRequestRepository helpRequestRepository;
    private final PriorityScoreService priorityScoreService;

    public PriorityScoreScheduler(HelpRequestRepository helpRequestRepository,
                                  PriorityScoreService priorityScoreService) {
        this.helpRequestRepository = helpRequestRepository;
        this.priorityScoreService = priorityScoreService;
    }

    @Scheduled(fixedRate = 1_800_000)
    @Transactional
    public void recalculateAllPending() {
        List<HelpRequest> pending = helpRequestRepository.findByStatus("PENDING");
        pending.forEach(request -> request.setPriorityScore(priorityScoreService.calculate(request)));
        helpRequestRepository.saveAll(pending);
    }
}
