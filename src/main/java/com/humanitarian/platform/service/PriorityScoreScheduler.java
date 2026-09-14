package com.humanitarian.platform.service;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.repository.HelpRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Re-applies the waiting-time component of the priority model to every
 * PENDING request every 30 minutes (D-5).
 *
 * Rows are read in pages and written one at a time through a dedicated
 * UPDATE of priority_score, so one row that cannot be saved is logged and
 * skipped instead of aborting the whole batch. The previous version loaded
 * every pending request into memory and saved them in a single transaction,
 * which would have stopped recalculation for the entire system the first
 * time any row failed.
 */
@Component
public class PriorityScoreScheduler {

    private static final Logger log = LoggerFactory.getLogger(PriorityScoreScheduler.class);
    static final int PAGE_SIZE = 200;

    private final HelpRequestRepository helpRequestRepository;
    private final PriorityScoreService priorityScoreService;

    public PriorityScoreScheduler(HelpRequestRepository helpRequestRepository,
                                  PriorityScoreService priorityScoreService) {
        this.helpRequestRepository = helpRequestRepository;
        this.priorityScoreService = priorityScoreService;
    }

    // Deliberately not @Transactional: each row's UPDATE commits on its own
    // (the repository method is @Transactional), so one failure isolates.
    @Scheduled(fixedRate = 1_800_000)
    public void recalculateAllPending() {
        int updated = 0;
        int unchanged = 0;
        int skipped = 0;
        int pageNumber = 0;
        Page<HelpRequest> page;
        do {
            page = helpRequestRepository.findByStatus("PENDING",
                    PageRequest.of(pageNumber, PAGE_SIZE, Sort.by("id")));
            for (HelpRequest request : page.getContent()) {
                try {
                    int score = priorityScoreService.calculate(request);
                    if (Objects.equals(score, request.getPriorityScore())) {
                        unchanged++;
                        continue;
                    }
                    helpRequestRepository.updatePriorityScore(request.getId(), score);
                    updated++;
                } catch (RuntimeException ex) {
                    skipped++;
                    log.warn("Priority recalculation skipped for request {}: {}",
                            request.getId(), ex.getMessage());
                }
            }
            pageNumber++;
        } while (page.hasNext());

        if (updated > 0 || skipped > 0) {
            log.info("Priority recalculation: {} updated, {} unchanged, {} skipped", updated, unchanged, skipped);
        }
    }
}
