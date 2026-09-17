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
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consultation records on psychological cases (CS-1): the assigned psychologist
 * records format, duration, topics and recommendations once the case is
 * COMPLETED, the beneficiary rates it once, and beneficiary, assigned
 * psychologist and administrators may read it. Exactly one record per case; the
 * UNIQUE constraint from V20 decides a race the service did not see.
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

    private final ConsultationRepository consultationRepository;
    private final PsychologicalRequestRepository requestRepository;
    private final PsychologistRepository psychologistRepository;
    private final AssignmentRepository assignmentRepository;
    private final UserService userService;
    private final NotificationService notifications;

    public ConsultationService(ConsultationRepository consultationRepository,
                               PsychologicalRequestRepository requestRepository,
                               PsychologistRepository psychologistRepository,
                               AssignmentRepository assignmentRepository,
                               UserService userService,
                               NotificationService notifications) {
        this.consultationRepository = consultationRepository;
        this.requestRepository = requestRepository;
        this.psychologistRepository = psychologistRepository;
        this.assignmentRepository = assignmentRepository;
        this.userService = userService;
        this.notifications = notifications;
    }

    @Transactional
    public ConsultationResponse record(Long requestId, ConsultationRequest body) {
        User me = userService.getCurrentUser();
        Context ctx = partyContext(requestId, me);
        if (!ctx.assignedPsychologist) {
            throw new UnauthorizedException("Only the assigned psychologist may record this consultation.");
        }
        if (!"COMPLETED".equals(ctx.request.getStatus())) {
            throw new BusinessException("A consultation is recorded once the case is completed.");
        }
        if (consultationRepository.existsByPsychologicalRequestId(requestId)) {
            throw new ConflictException("A consultation has already been recorded for this case.");
        }
        LocalDateTime startedAt = body.getStartedAt() != null ? body.getStartedAt() : LocalDateTime.now();
        LocalDateTime endedAt = body.getDurationMinutes() == null ? null : startedAt.plusMinutes(body.getDurationMinutes());
        // the assignment that produced the session: the one closed with the case, else the latest
        Long assignmentId = assignmentRepository
                .findFirstByPsychologicalRequestIdAndStatusOrderByAssignedAtDesc(requestId, "COMPLETED")
                .or(() -> assignmentRepository.findFirstByPsychologicalRequestIdOrderByAssignedAtDesc(requestId))
                .map(Assignment::getId).orElse(null);
        Consultation saved;
        try {
            saved = consultationRepository.saveAndFlush(Consultation.builder()
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
        } catch (DataIntegrityViolationException raceLost) {
            // two submissions at once: uq_consultations_psych_request kept the first one
            throw new ConflictException("A consultation has already been recorded for this case.");
        }
        log.info("Consultation {} recorded for case {} (assignment {}) by psychologist {} (user {})",
                saved.getId(), requestId, assignmentId, saved.getPsychologistId(), me.getId());
        // the beneficiary's own notification: their case, their psychologist's name
        notifications.notify(ctx.request.getBeneficiaryId(), "Your consultation was recorded: please rate it",
                me.getFullName() + " recorded your consultation. A rating helps other people choose.",
                NotificationService.REF_PSYCHOLOGICAL_REQUEST, requestId);
        return toResponse(saved, me.getFullName(), true);
    }

    @Transactional
    public ConsultationResponse submitFeedback(Long requestId, ConsultationFeedbackRequest body) {
        User me = userService.getCurrentUser();
        Context ctx = partyContext(requestId, me);
        if (!Objects.equals(ctx.request.getBeneficiaryId(), me.getId())) {
            throw new UnauthorizedException("Only the person who asked for support may rate this consultation.");
        }
        Consultation consultation = consultationRepository.findByPsychologicalRequestId(requestId)
                .orElseThrow(() -> new BusinessException(
                        "The psychologist has not recorded the consultation yet; you can rate it once they have."));
        if (consultation.getRating() != null) {
            throw new ConflictException("You have already rated this consultation.");
        }
        consultation.setRating(body.getRating());
        consultation.setFeedbackFromBeneficiary(blankToNull(body.getFeedback()));
        Consultation saved = consultationRepository.save(consultation);
        // no user id in the line: the beneficiary may be anonymous to the psychologist
        log.info("Case {} consultation rated {}/5 by its beneficiary", requestId, body.getRating());
        // the psychologist learns the rating, never who gave it
        psychologistUserId(saved.getPsychologistId()).ifPresent(psychologistUser ->
                notifications.notify(psychologistUser, "You received a rating: " + body.getRating() + "/5",
                        "The person you supported in case #" + requestId + " rated the consultation.",
                        NotificationService.REF_PSYCHOLOGICAL_REQUEST, requestId));
        return toResponse(saved, psychologistName(saved.getPsychologistId()), false);
    }

    @Transactional(readOnly = true)
    public ConsultationResponse getConsultation(Long requestId) {
        Context ctx = partyContext(requestId, userService.getCurrentUser());
        Consultation consultation = consultationRepository.findByPsychologicalRequestId(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("No consultation has been recorded for this case yet."));
        return toResponse(consultation, psychologistName(consultation.getPsychologistId()), ctx.assignedPsychologist);
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
