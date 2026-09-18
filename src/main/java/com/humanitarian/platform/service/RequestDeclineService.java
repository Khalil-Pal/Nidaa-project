package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.ProviderCapacityReservation;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.ConflictException;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.exception.UnauthorizedException;
import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.OrganizationRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Declining an assignment (GAP-1). An automatically matched provider who cannot
 * take a request had only one way out before: cancelling it, which destroys the
 * beneficiary's request. Declining ends the provider's involvement and leaves
 * the request alive.
 *
 * What a decline does, in order: the assignment row becomes DECLINED (not
 * CANCELLED — it records that this provider said no, and it is what the rematch
 * excludes on); any reserved capacity is restored exactly once, by the same
 * guarded UPDATE the cancellation path uses; the provider is released if they
 * hold no other open assignment; the request returns to PENDING with its
 * provider columns cleared. Then the request is offered to the next nearest
 * provider, never to anyone who has already declined it.
 *
 * After {@link #MAX_DECLINES} declines the request is flagged for an
 * administrator instead of being offered again: three refusals mean something a
 * matching rule cannot fix.
 */
@Service
public class RequestDeclineService {

    private static final Logger log = LoggerFactory.getLogger(RequestDeclineService.class);

    /** Declines after which the request goes to an administrator rather than round again. */
    public static final int MAX_DECLINES = 3;

    static final String DECLINED = "DECLINED";

    private final HelpRequestRepository helpRequestRepository;
    private final AssignmentRepository assignmentRepository;
    private final VolunteerRepository volunteerRepository;
    private final OrganizationRepository organizationRepository;
    private final ProviderResourceService providerResourceService;
    private final AutomaticAssignmentService automaticAssignmentService;
    private final AttentionService attention;
    private final UserService userService;
    private final NotificationService notifications;

    public RequestDeclineService(HelpRequestRepository helpRequestRepository,
                                 AssignmentRepository assignmentRepository,
                                 VolunteerRepository volunteerRepository,
                                 OrganizationRepository organizationRepository,
                                 ProviderResourceService providerResourceService,
                                 AutomaticAssignmentService automaticAssignmentService,
                                 AttentionService attention,
                                 UserService userService,
                                 NotificationService notifications) {
        this.helpRequestRepository = helpRequestRepository;
        this.assignmentRepository = assignmentRepository;
        this.volunteerRepository = volunteerRepository;
        this.organizationRepository = organizationRepository;
        this.providerResourceService = providerResourceService;
        this.automaticAssignmentService = automaticAssignmentService;
        this.attention = attention;
        this.userService = userService;
        this.notifications = notifications;
    }

    /** What happened, for the endpoint's answer and for the log. */
    public record DeclineOutcome(Long requestId, String status, int declineCount,
                                 boolean reassigned, boolean needsAttention) {
    }

    @Transactional
    public DeclineOutcome decline(Long requestId, String reason) {
        User me = userService.getCurrentUser();
        HelpRequest request = helpRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Request not found: " + requestId));

        Assignment assignment = openAssignmentOf(request, me)
                // 404 rather than 403: a provider who is not on this request learns
                // nothing about it, the rule getRequestById follows.
                .orElseThrow(() -> new ResourceNotFoundException("Request not found: " + requestId));
        if (!"ASSIGNED".equals(request.getStatus())) {
            throw new BusinessException("Only a request that is still assigned can be declined; this one is "
                    + request.getStatus().toLowerCase() + ".");
        }

        LocalDateTime declinedAt = LocalDateTime.now();
        if (!restoreCapacity(assignment, declinedAt)) {
            // the guarded UPDATE lost: another closing path is restoring the same reservation
            throw new ConflictException("This request was updated by someone else. Reload and try again.");
        }
        assignment.setStatus(DECLINED);
        assignment.setCompletedAt(declinedAt);
        assignment.setNotes(appendReason(assignment.getNotes(), reason));
        assignmentRepository.save(assignment);
        releaseProvider(assignment);

        if (helpRequestRepository.releaseToPending(requestId) == 0) {
            throw new ConflictException("This request was updated by someone else. Reload and try again.");
        }
        long declines = assignmentRepository.countByRequestIdAndStatus(requestId, DECLINED);
        log.info("Request {} declined by {} (decline {} of {}), back to PENDING", requestId,
                assignment.getVolunteerId() != null
                        ? "volunteer " + assignment.getVolunteerId()
                        : "organization " + assignment.getOrganizationId(),
                declines, MAX_DECLINES);

        // the people waiting are told before the rematch, so the order of their
        // notifications matches what happened
        for (Long recipient : java.util.stream.Stream.of(request.getBeneficiaryId(), request.getFiledByUserId())
                .filter(Objects::nonNull).distinct().toList()) {
            notifications.notify(recipient, "Your request is being matched again",
                    "\"" + request.getTitle() + "\" is back on the queue: the provider who had it could not deliver it.",
                    NotificationService.REF_HELP_REQUEST, requestId);
        }

        if (declines >= MAX_DECLINES) {
            attention.flag(request, declines + " providers declined this request");
            return new DeclineOutcome(requestId, "PENDING", (int) declines, false, true);
        }

        HelpRequest pending = helpRequestRepository.findById(requestId).orElse(request);
        boolean reassigned = automaticAssignmentService.assignNearestProvider(
                pending, declinedProviders(requestId));
        if (!reassigned) {
            // Not lost: it stays PENDING and the retry sweep picks it up (GAP-2).
            log.info("Request {} found no other provider after the decline; it stays PENDING for the retry sweep",
                    requestId);
        }
        return new DeclineOutcome(requestId, reassigned ? "ASSIGNED" : "PENDING", (int) declines, reassigned, false);
    }

    /** The providers who have already declined this request, as the matcher excludes them (GAP-1). */
    public AutomaticAssignmentService.ProviderExclusions declinedProviders(Long requestId) {
        List<Assignment> declined = assignmentRepository.findAllByRequestIdAndStatus(requestId, DECLINED);
        return new AutomaticAssignmentService.ProviderExclusions(
                declined.stream().map(Assignment::getVolunteerId).filter(Objects::nonNull).distinct().toList(),
                declined.stream().map(Assignment::getOrganizationId).filter(Objects::nonNull).distinct().toList());
    }

    // ---- helpers ----------------------------------------------------------------

    /** The caller's own open assignment on this request, if they are its provider. */
    private Optional<Assignment> openAssignmentOf(HelpRequest request, User me) {
        Optional<Assignment> open = assignmentRepository
                .findFirstByRequestIdAndStatusOrderByAssignedAtDesc(request.getId(), "ASSIGNED");
        if (open.isEmpty()) {
            return Optional.empty();
        }
        Assignment assignment = open.get();
        boolean mine = switch (me.getRole()) {
            case VOLUNTEER -> volunteerRepository.findByUserId(me.getId())
                    .map(profile -> Objects.equals(profile.getId(), assignment.getVolunteerId()))
                    .orElse(false);
            case ORGANIZATION -> organizationRepository.findByUserId(me.getId())
                    .map(profile -> Objects.equals(profile.getId(), assignment.getOrganizationId()))
                    .orElse(false);
            default -> false;
        };
        return mine ? open : Optional.empty();
    }

    /**
     * Restores the reservation exactly once, guarded on capacity_restored_at being
     * unset, which is the rule ADR 005 states for a closing that gives capacity
     * back. A row without a numeric reservation has nothing to restore.
     */
    private boolean restoreCapacity(Assignment assignment, LocalDateTime restoredAt) {
        if (assignment.getReservedCapacityAmount() == null || assignment.getCapacityRestoredAt() != null) {
            return true;
        }
        if (assignment.getId() == null
                || assignmentRepository.markCapacityRestored(assignment.getId(), restoredAt) == 0) {
            return false;
        }
        providerResourceService.restoreReservation(new ProviderCapacityReservation(
                assignment.getResourceUserId(), assignment.getResourceHelpType(),
                assignment.getReservedCapacityAmount()));
        assignment.setCapacityRestoredAt(restoredAt);
        return true;
    }

    /** A provider with no other open assignment becomes available again. */
    private void releaseProvider(Assignment assignment) {
        if (assignment.getVolunteerId() != null
                && assignmentRepository.countByVolunteerIdAndStatus(assignment.getVolunteerId(), "ASSIGNED") == 0) {
            volunteerRepository.release(assignment.getVolunteerId());
        }
        if (assignment.getOrganizationId() != null
                && assignmentRepository.countByOrganizationIdAndStatus(assignment.getOrganizationId(), "ASSIGNED") == 0) {
            organizationRepository.release(assignment.getOrganizationId());
        }
    }

    private static String appendReason(String notes, String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        String line = "Declined" + (trimmed.isEmpty() ? "" : ": " + trimmed);
        return notes == null || notes.isBlank() ? line : notes + "\n" + line;
    }

    /** Only providers decline; the role gate on the endpoint says the same thing. */
    static boolean isProvider(UserRole role) {
        return role == UserRole.VOLUNTEER || role == UserRole.ORGANIZATION;
    }

    /** Guard used by the controller's slice tests to describe the rule in one place. */
    void requireProvider(User me) {
        if (!isProvider(me.getRole())) {
            throw new UnauthorizedException("Only the assigned provider may decline a request.");
        }
    }
}
