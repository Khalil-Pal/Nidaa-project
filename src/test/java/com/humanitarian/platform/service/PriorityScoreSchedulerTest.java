package com.humanitarian.platform.service;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.repository.HelpRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** D-5: the recalculation is paged, writes only changed rows, and survives a failing row. */
@ExtendWith(MockitoExtension.class)
class PriorityScoreSchedulerTest {

    @Mock private HelpRequestRepository helpRequestRepository;

    private final PriorityScoreService priorityScoreService = new PriorityScoreService();

    private static HelpRequest pending(long id, String urgency, int hoursOld, Integer storedScore) {
        return HelpRequest.builder()
                .id(id)
                .urgencyLevel(urgency)
                .peopleCount(1)
                .status("PENDING")
                .priorityScore(storedScore)
                .createdAt(LocalDateTime.now().minusHours(hoursOld))
                .build();
    }

    @Test
    void oneFailingRowDoesNotStopTheOthers() {
        HelpRequest a = pending(1L, "LOW", 10, 0);
        HelpRequest poison = pending(2L, "HIGH", 10, 0);
        HelpRequest c = pending(3L, "CRITICAL", 10, 0);
        when(helpRequestRepository.findByStatus(eq("PENDING"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a, poison, c), PageRequest.of(0, PriorityScoreScheduler.PAGE_SIZE), 3));
        when(helpRequestRepository.updatePriorityScore(eq(2L), anyInt()))
                .thenThrow(new DataIntegrityViolationException("priority_score_range"));

        new PriorityScoreScheduler(helpRequestRepository, priorityScoreService).recalculateAllPending();

        verify(helpRequestRepository).updatePriorityScore(eq(1L), anyInt());
        verify(helpRequestRepository).updatePriorityScore(eq(2L), anyInt());
        verify(helpRequestRepository).updatePriorityScore(eq(3L), anyInt());
        verify(helpRequestRepository, never()).saveAll(any());
    }

    @Test
    void requestOldEnoughToBreachTheOldCapStillSavesWithinRange() {
        // 400 hours old: uncapped aging would have added 200 points
        HelpRequest ancient = HelpRequest.builder()
                .id(7L).urgencyLevel("CRITICAL").peopleCount(12)
                .hasChildren(true).hasElderly(true).hasDisabled(true)
                .status("PENDING").priorityScore(95)
                .createdAt(LocalDateTime.now().minusHours(400))
                .build();
        when(helpRequestRepository.findByStatus(eq("PENDING"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ancient), PageRequest.of(0, PriorityScoreScheduler.PAGE_SIZE), 1));

        new PriorityScoreScheduler(helpRequestRepository, priorityScoreService).recalculateAllPending();

        verify(helpRequestRepository).updatePriorityScore(7L, PriorityScoreService.MAX_SCORE);
    }

    @Test
    void unchangedScoresAreNotRewritten() {
        HelpRequest fresh = pending(9L, "MEDIUM", 0, 22);   // 20 + 2 people points + 0 aging = 22
        when(helpRequestRepository.findByStatus(eq("PENDING"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(fresh), PageRequest.of(0, PriorityScoreScheduler.PAGE_SIZE), 1));

        new PriorityScoreScheduler(helpRequestRepository, priorityScoreService).recalculateAllPending();

        verify(helpRequestRepository, never()).updatePriorityScore(anyLong(), anyInt());
    }

    @Test
    void allPagesAreVisited() {
        Pageable first = PageRequest.of(0, PriorityScoreScheduler.PAGE_SIZE);
        Pageable second = PageRequest.of(1, PriorityScoreScheduler.PAGE_SIZE);
        when(helpRequestRepository.findByStatus(eq("PENDING"), any(Pageable.class)))
                .thenAnswer(inv -> {
                    Pageable p = inv.getArgument(1);
                    return p.getPageNumber() == 0
                            ? new PageImpl<>(List.of(pending(1L, "LOW", 5, 0)), first, PriorityScoreScheduler.PAGE_SIZE + 1)
                            : new PageImpl<>(List.of(pending(2L, "LOW", 5, 0)), second, PriorityScoreScheduler.PAGE_SIZE + 1);
                });

        new PriorityScoreScheduler(helpRequestRepository, priorityScoreService).recalculateAllPending();

        verify(helpRequestRepository, times(2)).findByStatus(eq("PENDING"), any(Pageable.class));
        verify(helpRequestRepository).updatePriorityScore(eq(1L), anyInt());
        verify(helpRequestRepository).updatePriorityScore(eq(2L), anyInt());
    }
}
