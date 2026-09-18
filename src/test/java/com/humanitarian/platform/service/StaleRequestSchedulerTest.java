package com.humanitarian.platform.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.repository.HelpRequestRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * GAP-2: the sweep retries what automatic matching left behind, in priority
 * order, without ever offering a request to a provider who declined it, and
 * escalates what has waited too long.
 */
@ExtendWith(MockitoExtension.class)
class StaleRequestSchedulerTest {

    @Mock private HelpRequestRepository helpRequestRepository;
    @Mock private AutomaticAssignmentService automaticAssignmentService;
    @Mock private RequestDeclineService declines;
    @Mock private AttentionService attention;

    private StaleRequestScheduler scheduler(long escalateAfterHours) {
        return new StaleRequestScheduler(helpRequestRepository, automaticAssignmentService,
                declines, attention, escalateAfterHours);
    }

    private static HelpRequest request(Long id, int score, LocalDateTime createdAt) {
        return HelpRequest.builder().id(id).title("Request " + id).status("PENDING")
                .priorityScore(score).createdAt(createdAt)
                .latitude(55.75).longitude(37.62).build();
    }

    private void queueIs(List<HelpRequest> requests) {
        when(helpRequestRepository.findUnassignedPendingByPriority(any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(0);
                    return pageable.getPageNumber() == 0
                            ? new PageImpl<>(requests, PageRequest.of(0, StaleRequestScheduler.PAGE_SIZE), requests.size())
                            : Page.<HelpRequest>empty(pageable);
                });
    }

    @Test
    void theUrgentStaleRequestIsRetriedBeforeTheLessUrgentOne() {
        LocalDateTime now = LocalDateTime.now();
        // the repository returns them in priority order; the sweep must preserve it
        HelpRequest urgent = request(1L, 88, now.minusHours(2));
        HelpRequest middling = request(2L, 55, now.minusHours(2));
        HelpRequest low = request(3L, 12, now.minusHours(2));
        queueIs(List.of(urgent, middling, low));
        when(declines.declinedProviders(any())).thenReturn(AutomaticAssignmentService.ProviderExclusions.NONE);
        when(automaticAssignmentService.assignNearestProvider(any(HelpRequest.class), any())).thenReturn(false);

        StaleRequestScheduler.SweepResult result = scheduler(24).sweep();

        assertEquals(List.of(1L, 2L, 3L), result.orderConsidered(), "highest priority score first");
        ArgumentCaptor<HelpRequest> offered = ArgumentCaptor.forClass(HelpRequest.class);
        verify(automaticAssignmentService, org.mockito.Mockito.times(3))
                .assignNearestProvider(offered.capture(), any());
        assertEquals(List.of(1L, 2L, 3L), offered.getAllValues().stream().map(HelpRequest::getId).toList());
        assertEquals(3, result.considered());
        assertEquals(0, result.assigned());
        assertEquals(0, result.escalated(), "two hours old is not stale");
        verify(attention, never()).flag(any(), anyString());
    }

    @Test
    void theRetryRespectsTheDeclineExclusions() {
        HelpRequest request = request(4L, 70, LocalDateTime.now().minusHours(1));
        queueIs(List.of(request));
        AutomaticAssignmentService.ProviderExclusions declined =
                new AutomaticAssignmentService.ProviderExclusions(List.of(20L, 21L), List.of(70L));
        when(declines.declinedProviders(4L)).thenReturn(declined);
        when(automaticAssignmentService.assignNearestProvider(any(HelpRequest.class), eq(declined))).thenReturn(true);

        StaleRequestScheduler.SweepResult result = scheduler(24).sweep();

        verify(automaticAssignmentService).assignNearestProvider(any(HelpRequest.class), eq(declined));
        assertEquals(1, result.assigned());
        assertEquals(0, result.escalated(), "an assignment is not an escalation");
    }

    @Test
    void aRequestOlderThanTheEscalationAgeWithNobodyToTakeItIsFlaggedOnce() {
        LocalDateTime now = LocalDateTime.now();
        HelpRequest stale = request(5L, 60, now.minusHours(30));
        HelpRequest fresh = request(6L, 59, now.minusHours(3));
        queueIs(List.of(stale, fresh));
        when(declines.declinedProviders(any())).thenReturn(AutomaticAssignmentService.ProviderExclusions.NONE);
        when(automaticAssignmentService.assignNearestProvider(any(HelpRequest.class), any())).thenReturn(false);
        when(attention.flag(any(HelpRequest.class), anyString())).thenReturn(true);

        StaleRequestScheduler.SweepResult result = scheduler(24).sweep();

        ArgumentCaptor<HelpRequest> flagged = ArgumentCaptor.forClass(HelpRequest.class);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(attention).flag(flagged.capture(), reason.capture());
        assertEquals(5L, flagged.getValue().getId(), "only the one past the escalation age");
        org.junit.jupiter.api.Assertions.assertTrue(reason.getValue().startsWith("no provider was available for 30 hour"),
                reason.getValue());
        assertEquals(1, result.escalated());
        assertEquals(2, result.considered());
    }

    @Test
    void oneFailingRequestDoesNotStopTheSweep() {
        LocalDateTime now = LocalDateTime.now();
        HelpRequest broken = request(7L, 90, now.minusHours(1));
        HelpRequest next = request(8L, 80, now.minusHours(1));
        queueIs(List.of(broken, next));
        when(declines.declinedProviders(any())).thenReturn(AutomaticAssignmentService.ProviderExclusions.NONE);
        when(automaticAssignmentService.assignNearestProvider(any(HelpRequest.class), any()))
                .thenThrow(new IllegalStateException("provider vanished"))
                .thenReturn(true);

        StaleRequestScheduler.SweepResult result = scheduler(24).sweep();

        assertEquals(2, result.considered());
        assertEquals(1, result.assigned(), "the second one was still matched");
    }
}
