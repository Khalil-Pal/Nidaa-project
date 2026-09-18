package com.humanitarian.platform.service;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.repository.HelpRequestRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retry and escalation for requests nobody took (GAP-2).
 *
 * A request is matched once, on arrival, against the providers free at that
 * moment. Before this sweep, one that found nobody stayed PENDING for ever: no
 * later arrival of a provider, no return of capacity and no change of
 * availability brought it back into consideration, and nothing told anyone.
 *
 * Every 30 minutes the sweep takes the PENDING requests with no open assignment
 * **in priority order, highest score first**, and offers each to the nearest
 * available provider again, never to one who has already declined it (GAP-1).
 *
 * <strong>This ordering is the only place where the priority score decides what
 * automatic matching looks at first.</strong> On arrival a request is matched
 * alone — there is nothing to order it against — and the ranked queue
 * (`/api/v1/admin/dashboard/ranked`) only orders what humans browse. The
 * evaluation chapter (EV-1) depends on knowing exactly where the model acts:
 * here, and in what the ranked queue shows.
 *
 * A request that has waited longer than the escalation age ({@code
 * app.matching.escalate-after-hours}, 24 by default) and still has no provider
 * is flagged for an administrator, once, through {@link AttentionService}.
 */
@Component
public class StaleRequestScheduler {

    private static final Logger log = LoggerFactory.getLogger(StaleRequestScheduler.class);

    static final int PAGE_SIZE = 100;

    private final HelpRequestRepository helpRequestRepository;
    private final AutomaticAssignmentService automaticAssignmentService;
    private final RequestDeclineService declines;
    private final AttentionService attention;
    private final long escalateAfterHours;

    public StaleRequestScheduler(HelpRequestRepository helpRequestRepository,
                                 AutomaticAssignmentService automaticAssignmentService,
                                 RequestDeclineService declines,
                                 AttentionService attention,
                                 @Value("${app.matching.escalate-after-hours:24}") long escalateAfterHours) {
        this.helpRequestRepository = helpRequestRepository;
        this.automaticAssignmentService = automaticAssignmentService;
        this.declines = declines;
        this.attention = attention;
        this.escalateAfterHours = escalateAfterHours;
    }

    /** What one sweep did, for the log and for the tests. */
    public record SweepResult(int considered, int assigned, int escalated, List<Long> orderConsidered) {
    }

    // Not @Transactional: each request's matching commits on its own, so one
    // failure isolates, the same rule PriorityScoreScheduler follows.
    @Scheduled(fixedRate = 1_800_000)
    public void retryPending() {
        SweepResult result = sweep();
        if (result.considered() > 0) {
            log.info("Stale request sweep: {} considered, {} assigned, {} escalated",
                    result.considered(), result.assigned(), result.escalated());
        }
    }

    public SweepResult sweep() {
        LocalDateTime now = LocalDateTime.now();
        int considered = 0;
        int assigned = 0;
        int escalated = 0;
        List<Long> order = new java.util.ArrayList<>();

        int pageNumber = 0;
        Page<HelpRequest> page;
        do {
            // The query orders by priority_score DESC; paging keeps that order because
            // the score does not change under the sweep (the priority scheduler runs
            // on its own clock and only raises scores).
            page = helpRequestRepository.findUnassignedPendingByPriority(PageRequest.of(pageNumber, PAGE_SIZE));
            for (HelpRequest request : page.getContent()) {
                considered++;
                order.add(request.getId());
                try {
                    if (automaticAssignmentService.assignNearestProvider(
                            request, declines.declinedProviders(request.getId()))) {
                        assigned++;
                        continue;
                    }
                    if (isStale(request, now) && attention.flag(request, escalationReason(request, now))) {
                        escalated++;
                    }
                } catch (RuntimeException ex) {
                    log.warn("Stale request sweep skipped request {}: {}", request.getId(), ex.getMessage());
                }
            }
            pageNumber++;
        } while (page.hasNext());

        return new SweepResult(considered, assigned, escalated, List.copyOf(order));
    }

    private boolean isStale(HelpRequest request, LocalDateTime now) {
        return request.getCreatedAt() != null
                && Duration.between(request.getCreatedAt(), now).toHours() >= escalateAfterHours;
    }

    private String escalationReason(HelpRequest request, LocalDateTime now) {
        long hours = Duration.between(request.getCreatedAt(), now).toHours();
        return "no provider was available for " + hours + " hours";
    }
}
