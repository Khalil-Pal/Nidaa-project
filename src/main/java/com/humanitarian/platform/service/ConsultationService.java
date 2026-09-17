package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.ConsultationFeedbackRequest;
import com.humanitarian.platform.dto.ConsultationRequest;
import com.humanitarian.platform.dto.ConsultationResponse;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.ConflictException;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.exception.UnauthorizedException;
import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.model.Consultation;
import com.humanitarian.platform.model.PsychologicalRequest;
import com.humanitarian.platform.model.Psychologist;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.ConsultationRepository;
import com.humanitarian.platform.repository.PsychologicalRequestRepository;
import com.humanitarian.platform.repository.PsychologistRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consultation records on psychological cases (CS-1): one record per session.
 * A case is a course of support that stays ASSIGNED until the psychologist
 * completes it; each session the assigned psychologist records (format,
 * duration, topics, recommendations) is a row of its own, the beneficiary rates
 * each session once, and beneficiary, assigned psychologist and administrators
 * may list them. Sessions may be recorded while the case is ASSIGNED or after it
 * is COMPLETED (the last session is often written up after closing); never on a
 * PENDING or CANCELLED case.
 *
 * Two things never leave this service for the wrong reader. The psychologist's
 * private note ({@code notes_for_psychologist}) is put on the response only when
 * the caller is the assigned psychologist; every other response, including the
 * administrator's, is built with it null and the key is omitted. The response and
 * the notifications carry no beneficiary identity at all (no id, no name), so an
 * anonymous request stays anonymous through feedback as well, the same care
 * {@link ContactInfoService} takes with contact details.
 *
 * Existence is revealed only to the case's parties: anyone else gets 404 for any
 * request id, the same rule as the request itself.
 */
@Service
public class ConsultationService {

    private static final Logger log = LoggerFactory.getLogger(ConsultationService.class);

    /** A session is recorded on a case that has, or had, a psychologist. */
    private static final Set<String> RECORDABLE = Set.of("ASSIGNED", "COMPLETED");

    private final ConsultationRepository consultationRepository;
    private final PsychologicalRequestRepository requestRepository;
    private final PsychologistRepository psychologistRepository;
    private final AssignmentRepository assignmentRepository;
    private final UserService userService;
    private final NotificationService notifications;
    private final ProviderStatsService providerStats;

    public ConsultationService(ConsultationRepository consultationRepository,
                               PsychologicalRequestRepository requestRepository,
                               PsychologistRepository psychologistRepository,
                               AssignmentRepository assignmentRepository,
                               UserService userService,
                               NotificationService notifications,
                               ProviderStatsService providerStats) {
        this.consultationRepository = consultationRepository;
        this.requestRepository = requestRepository;
        this.psychologistRepository = psychologistRepository;
        this.assignmentRepository = assignmentRepository;
        this.userService = userService;
        this.notifications = notifications;
        this.providerStats = providerStats;
    }

    @Transactional
    public ConsultationResponse record(Long requestId, ConsultationRequest body) {
        User me = userService.getCurrentUser();
        Context ctx = partyContext(requestId, me);
        if (!ctx.assignedPsychologist) {
            throw new UnauthorizedException("Only the assigned psychologist may record a session on this case.");
        }
        if (!RECORDABLE.contains(ctx.request.getStatus())) {
            throw new BusinessException("Sessions are recorded on an open or completed case, not a "
                    + ctx.request.getStatus().toLowerCase() + " one.");
        }
        LocalDateTime startedAt = body.getStartedAt() != null ? body.getStartedAt() : LocalDateTime.now();
        LocalDateTime endedAt = body.getDurationMinutes() == null ? null : startedAt.plusMinutes(body.getDurationMinutes());
        // the assignment the session belongs to: the case's latest
        Long assignmentId = assignmentRepository.findFirstByPsychologicalRequestIdOrderByAssignedAtDesc(requestId)
                .map(Assignment::getId).orElse(null);
        // flushed before the counters are recomputed, so the new row is in the count
        Consultation saved = consultationRepository.saveAndFlush(Consultation.builder()
                .psychologicalRequestId(requestId)
                .assignmentId(assignmentId)
                .psychologistId(ctx.request.getAssignedPsychologistId())
                .beneficiaryId(ctx.request.getBeneficiaryId())
                .format(PsychologicalRequestService.toFormat(body.getFormat()))
                .startedAt(startedAt)
                .endedAt(endedAt)
                .durationMinutes(body.getDurationMinutes())
                .topicsDiscussed(cleanTopics(body.getTopicsDiscussed()))
                .recommendations(blankToNull(body.getRecommendations()))
                .notesForPsychologist(blankToNull(body.getNotesForPsychologist()))
                .isCrisis(Boolean.TRUE.equals(ctx.request.getIsCrisis()))
                .build());
        log.info("Consultation {} recorded on case {} (assignment {}) by psychologist {} (user {})",
                saved.getId(), requestId, assignmentId, saved.getPsychologistId(), me.getId());
        providerStats.refreshPsychologist(saved.getPsychologistId());   // AGG-1: the count moves here
        // the beneficiary's own notification: their case, their psychologist's name
        notifications.notify(ctx.request.getBeneficiaryId(), "A session was recorded: please rate it",
                me.getFullName() + " recorded a session on your case. A rating helps other people choose.",
                NotificationService.REF_PSYCHOLOGICAL_REQUEST, requestId);
        return toResponse(saved, me.getFullName(), true);
    }

    @Transactional
    public ConsultationResponse submitFeedback(Long requestId, Long consultationId, ConsultationFeedbackRequest body) {
        User me = userService.getCurrentUser();
        Context ctx = partyContext(requestId, me);
        if (!Objects.equals(ctx.request.getBeneficiaryId(), me.getId())) {
            throw new UnauthorizedException("Only the person who asked for support may rate a session.");
        }
        Consultation consultation = consultationRepository.findByIdAndPsychologicalRequestId(consultationId, requestId)
                .orElseThrow(() -> new ResourceNotFoundException("No such session on this case: " + consultationId));
        if (consultation.getRating() != null) {
            throw new ConflictException("You have already rated this session.");
        }
        consultation.setRating(body.getRating());
        consultation.setFeedbackFromBeneficiary(blankToNull(body.getFeedback()));
        Consultation saved = consultationRepository.save(consultation);
        // no user id in the line: the beneficiary may be anonymous to the psychologist
        log.info("Case {} session {} rated {}/5 by its beneficiary", requestId, consultationId, body.getRating());
        providerStats.refreshPsychologist(saved.getPsychologistId());   // AGG-1: the mean moves here
        // the psychologist learns the rating, never who gave it
        psychologistUserId(saved.getPsychologistId()).ifPresent(psychologistUser ->
                notifications.notify(psychologistUser, "You received a rating: " + body.getRating() + "/5",
                        "The person you supported in case #" + requestId + " rated a session.",
                        NotificationService.REF_PSYCHOLOGICAL_REQUEST, requestId));
        return toResponse(saved, psychologistName(saved.getPsychologistId()), false);
    }

    /** The sessions of a case, oldest first; an empty list for a case without any. */
    @Transactional(readOnly = true)
    public List<ConsultationResponse> listConsultations(Long requestId) {
        Context ctx = partyContext(requestId, userService.getCurrentUser());
        List<Consultation> sessions = consultationRepository.findByPsychologicalRequestIdOrderByStartedAtAscIdAsc(requestId);
        if (sessions.isEmpty()) {
            return List.of();
        }
        // a case has one psychologist unless it was reassigned; one name lookup per distinct id
        Map<Long, String> names = sessions.stream().map(Consultation::getPsychologistId).distinct()
                .collect(Collectors.toMap(id -> id, id -> Optional.ofNullable(psychologistName(id)).orElse("")));
        return sessions.stream()
                .map(c -> toResponse(c, blankToNull(names.get(c.getPsychologistId())), ctx.assignedPsychologist))
                .toList();
    }

    // ---- helpers ----------------------------------------------------------------

    private record Context(PsychologicalRequest request, boolean assignedPsychologist) {
    }

    /**
     * Loads a psychological request for one of its parties. Anyone else sees 404,
     * never 403: on a mental-health record, confirming existence is itself a
     * disclosure (the rule {@code PsychologicalRequestService.getRequestById} applies).
     */
    private Context partyContext(Long requestId, User me) {
        PsychologicalRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Request not found: " + requestId));
        // the assignee column holds the psychologist profile id, not the user id
        boolean assignedPsychologist = me.getRole() == UserRole.PSYCHOLOGIST && request.getAssignedPsychologistId() != null
                && psychologistRepository.findByUserId(me.getId()).map(Psychologist::getId)
                        .map(id -> id.equals(request.getAssignedPsychologistId())).orElse(false);
        boolean party = me.getRole() == UserRole.ADMIN
                || Objects.equals(request.getBeneficiaryId(), me.getId())
                || assignedPsychologist;
        if (!party) {
            throw new ResourceNotFoundException("Request not found: " + requestId);
        }
        return new Context(request, assignedPsychologist);
    }

    private Optional<Long> psychologistUserId(Long psychologistId) {
        return psychologistRepository.findById(psychologistId).map(p -> p.getUser().getId());
    }

    private String psychologistName(Long psychologistId) {
        return psychologistRepository.findById(psychologistId).map(p -> p.getUser().getFullName()).orElse(null);
    }

    private static List<String> cleanTopics(List<String> topics) {
        if (topics == null) {
            return null;
        }
        List<String> cleaned = topics.stream().map(String::trim).filter(t -> !t.isEmpty()).distinct().toList();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The private note is passed through only for the assigned psychologist; null means the key is omitted. */
    private static ConsultationResponse toResponse(Consultation c, String psychologistName, boolean forAssignedPsychologist) {
        return new ConsultationResponse(c.getId(), c.getPsychologicalRequestId(), c.getAssignmentId(),
                c.getPsychologistId(), psychologistName, c.getFormat(), c.getStartedAt(), c.getEndedAt(),
                c.getDurationMinutes(), c.getTopicsDiscussed(), c.getRecommendations(), c.getIsCrisis(),
                c.getFeedbackFromBeneficiary(), c.getRating(),
                forAssignedPsychologist ? c.getNotesForPsychologist() : null);
    }
}
