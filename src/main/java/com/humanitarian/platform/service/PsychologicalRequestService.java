package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.PsychologicalRequestDto;
import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.model.Psychologist;
import com.humanitarian.platform.model.PsychologicalRequest;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.PsychologistRepository;
import com.humanitarian.platform.repository.PsychologicalRequestRepository;
import com.humanitarian.platform.util.RequestTransitions;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.ConflictException;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.exception.UnauthorizedException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class PsychologicalRequestService {

    private static final Logger logger = LoggerFactory.getLogger(PsychologicalRequestService.class);

    @Autowired private PsychologicalRequestRepository repo;
    @Autowired private UserService                    userService;
    @Autowired private CrisisDetectorService          crisisDetectorService;
    @Autowired private AssignmentRepository           assignmentRepository;
    @Autowired private PsychologistRepository         psychologistRepository;
    @Autowired private AutomaticAssignmentService     automaticAssignmentService;
    @Autowired private AdminAuditService              adminAudit;
    @Autowired private NotificationService            notifications;

    @Transactional
    public PsychologicalRequest createRequest(PsychologicalRequestDto dto) {
        User user = userService.getCurrentUser();
        String rawCategory = dto.getCategory();
        String category = toCategory(rawCategory);
        String description = dto.getDescription() != null ? dto.getDescription() : "";
        // An explicit crisis category or support type forces a crisis; otherwise
        // the weighted detector decides between crisis, review and normal.
        CrisisDetectorService.Assessment assessment =
                crisisDetectorService.assess(rawCategory, description);
        boolean crisis = assessment.isCrisis()
                || crisisDetectorService.detect(dto.getSupportType(), description);
        boolean needsReview = !crisis && assessment.needsReview();
        // Score and match count only: the matched terms are fragments of what the
        // person wrote, and the log is not the place for them.
        if (crisis) {
            logger.warn("CRISIS detected for user {}: score {} on {} term(s); routing to an on-duty psychologist",
                    user.getId(), assessment.score(), assessment.matchedTerms().size());
        } else if (needsReview) {
            logger.info("Psychological request from user {} flagged for review: score {} on {} term(s)",
                    user.getId(), assessment.score(), assessment.matchedTerms().size());
        }

        PsychologicalRequest r = PsychologicalRequest.builder()
                .beneficiaryId(user.getId())
                .supportType(toSupportType(dto.getSupportType()))
                .category(category)
                .urgencyLevel(toUrgency(dto.getUrgencyLevel()))
                .preferredFormat(toFormat(dto.getPreferredFormat()))
                .description(description)
                .isAnonymous(dto.getIsAnonymous() != null ? dto.getIsAnonymous() : false)
                .status("PENDING")
                .isCrisis(crisis)
                .needsReview(needsReview)
                .crisisDetectedAt(crisis ? LocalDateTime.now() : null)
                .build();
        PsychologicalRequest saved = repo.save(r);
        if (crisis) {
            automaticAssignmentService.routeCrisisRequest(saved);
        }
        return repo.findById(saved.getId()).orElse(saved);
    }

    @Transactional
    public PsychologicalRequest acceptRequest(Long requestId) {
        User currentUser = userService.getCurrentUser();
        PsychologicalRequest request = findOrThrow(requestId);

        // assigned_psychologist_id is the psychologists PK, not the user id
        Psychologist psychologist = psychologistRepository.findByUserId(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Psychologist profile not found. Contact admin."));
        Long psychologistId = psychologist.getId();
        if (Boolean.TRUE.equals(request.getIsCrisis()) && !Boolean.TRUE.equals(psychologist.getIsOnDuty())) {
            throw new BusinessException("Crisis requests require an on-duty psychologist");
        }

        int updated = repo.assignPsychologist(requestId, psychologistId, "ASSIGNED", "PENDING");
        if (updated == 0) {
            throw new ConflictException("Request is no longer available or already assigned.");
        }
        logger.info("Case {} accepted by psychologist {} (user {})", requestId, psychologistId, currentUser.getId());

        Assignment assignment = Assignment.builder()
                .psychologicalRequestId(requestId)
                .psychologistId(psychologistId)
                .assignedBy(currentUser.getId())
                .requestType("PSYCHOLOGICAL_REQUEST")
                .assignmentSource("MANUAL")
                .status("ASSIGNED")
                .assignedAt(LocalDateTime.now())
                .notes("Accepted manually by psychologist")
                .build();
        assignmentRepository.save(assignment);

        // N-1: the beneficiary learns a psychologist took the case. Their own request,
        // so nothing about them is revealed; anonymity protects the beneficiary, not the psychologist.
        notifications.notify(request.getBeneficiaryId(), "A psychologist accepted your request",
                currentUser.getFullName() + " accepted your psychological support request and will contact you.",
                NotificationService.REF_PSYCHOLOGICAL_REQUEST, requestId);

        return findOrThrow(requestId);
    }

    // Only the assigned psychologist (or an admin) may close a case; the
    // beneficiary may withdraw it. Volunteers and organizations have no role
    // in psychological cases at all.
    private static final Set<String> OWNER_TARGETS        = Set.of("CANCELLED");
    private static final Set<String> PSYCHOLOGIST_TARGETS = Set.of("COMPLETED", "CANCELLED");
    private static final Set<String> ADMIN_TARGETS        = Set.of("ASSIGNED", "COMPLETED", "CANCELLED");

    @Transactional
    public PsychologicalRequest updateStatus(Long id, String status) {
        PsychologicalRequest request = findOrThrow(id);
        User currentUser = userService.getCurrentUser();
        String current = request.getStatus();
        String next = status.toUpperCase();

        // Authorization before transition validation, so an unrelated caller
        // learns nothing about the case's current state.
        if (!permittedTargets(currentUser, request).contains(next)) {
            throw new UnauthorizedException("You do not have permission to update this request.");
        }

        // Someone else already applied this transition: a conflict of state, not
        // a bad request, so a client racing another actor sees 409 either way (B-4).
        if (next.equals(current)) {
            throw new ConflictException("This request is already " + current + ".");
        }

        // Transition validation
        if (!RequestTransitions.allows(current, next)) {
            throw new BusinessException("Invalid status transition: cannot move from " + current + " to " + next);
        }

        // Guarded by the status validated above; a lost race is 409, not a silent overwrite (B-4)
        if (repo.updateStatusNative(id, next, current) == 0) {
            throw new ConflictException("This request was updated by someone else. Reload and try again.");
        }
        if ("COMPLETED".equals(next) || "CANCELLED".equals(next)) {
            updateAssignmentStatus(id, next, LocalDateTime.now());
        }
        logger.info("Case {} {} -> {} by user {} ({})", id, current, next, currentUser.getId(), currentUser.getRole());
        if (currentUser.getRole() == UserRole.ADMIN) {
            adminAudit.record("REQUEST_STATUS_CHANGED", "PSYCHOLOGICAL_REQUEST", id, Map.of("from", current, "to", next));
        }
        // N-1: the other party hears about the change
        String word = next.toLowerCase(java.util.Locale.ROOT);
        for (Long recipient : caseParties(request, currentUser.getId())) {
            notifications.notify(recipient, "Support request " + word,
                    "Your psychological support request is now " + word + ".",
                    NotificationService.REF_PSYCHOLOGICAL_REQUEST, id);
        }
        return findOrThrow(id);
    }

    /** The beneficiary and the assigned psychologist, minus whoever acted. */
    private Set<Long> caseParties(PsychologicalRequest request, Long actorId) {
        Set<Long> parties = new java.util.LinkedHashSet<>();
        parties.add(request.getBeneficiaryId());
        if (request.getAssignedPsychologistId() != null) {
            psychologistRepository.findById(request.getAssignedPsychologistId())
                    .ifPresent(p -> parties.add(p.getUser().getId()));
        }
        parties.remove(null);
        parties.remove(actorId);
        return parties;
    }

    private Set<String> permittedTargets(User me, PsychologicalRequest request) {
        if (me.getRole() == UserRole.ADMIN) {
            return ADMIN_TARGETS;
        }
        if (Objects.equals(request.getBeneficiaryId(), me.getId())) {
            return OWNER_TARGETS;
        }
        if (isAssignedPsychologist(me, request)) {
            return PSYCHOLOGIST_TARGETS;
        }
        return Set.of();
    }

    public List<PsychologicalRequest> getAllRequests() { return repo.findAll(); }

    /** Unassigned pending requests, oldest first, paged (Q-3). */
    public Page<PsychologicalRequest> getPendingRequests(int page, int size) {
        return repo.findByStatusAndAssignedPsychologistIdIsNull("PENDING",
                PageRequest.of(page, size, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"))));
    }

    public List<PsychologicalRequest> getMyRequests() {
        return repo.findByBeneficiaryId(userService.getCurrentUser().getId());
    }

    public List<PsychologicalRequest> getMyAssignedRequests() {
        User user = userService.getCurrentUser();
        return psychologistRepository.findByUserId(user.getId())
                .map(p -> repo.findByAssignedPsychologistId(p.getId()))
                .orElseGet(List::of);
    }

    /**
     * Returns the request only to an admin, the beneficiary or the assigned
     * psychologist. Everyone else gets 404, never 403: on a mental-health
     * record, confirming existence is itself a disclosure and would also
     * undermine the isAnonymous protection in ContactInfoService.
     */
    @Transactional(readOnly = true)
    public PsychologicalRequest getRequestById(Long id) {
        PsychologicalRequest request = findOrThrow(id);
        User me = userService.getCurrentUser();
        boolean allowed = me.getRole() == UserRole.ADMIN
                || Objects.equals(request.getBeneficiaryId(), me.getId())
                || isAssignedPsychologist(me, request);
        if (!allowed) {
            throw new ResourceNotFoundException("Request not found: " + id);
        }
        return request;
    }

    /** Unguarded lookup for flows that apply their own rule (accept, status change). */
    private PsychologicalRequest findOrThrow(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Request not found: " + id));
    }

    // Compares the caller's psychologist profile id, not the user id, to the assignee column.
    private boolean isAssignedPsychologist(User me, PsychologicalRequest request) {
        if (me.getRole() != UserRole.PSYCHOLOGIST || request.getAssignedPsychologistId() == null) {
            return false;
        }
        return psychologistRepository.findByUserId(me.getId())
                .map(profile -> Objects.equals(profile.getId(), request.getAssignedPsychologistId()))
                .orElse(false);
    }

    // The converters below canonicalise form input to the database enum
    // labels. Unrecognised input is an error: a request must never be filed
    // under a category, urgency or format the person did not choose.
    // Optional fields (urgency, format) fall back to the column defaults
    // only when absent, never when wrong.

    private String toCategory(String v) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Category is required. Accepted values: " + PsychologicalRequestDto.CATEGORIES);
        }
        String u = v.toUpperCase().trim().replace(" & ","_AND_").replace(" ","_").replace("-","_");
        return switch (u) {
            case "ANXIETY"                             -> "ANXIETY";
            case "DEPRESSION"                          -> "DEPRESSION";
            case "PTSD"                                -> "PTSD";
            // Labels must match the psychological_category enum in V1 exactly
            case "GRIEF_AND_LOSS","GRIEF_LOSS","GRIEF" -> "GRIEF";
            case "DOMESTIC_VIOLENCE","VIOLENCE"        -> "VIOLENCE";
            case "CRISIS_SUPPORT","CRISIS"             -> "CRISIS";
            case "CHILD"                               -> "CHILD";
            case "OTHER"                               -> "OTHER";
            default -> throw new IllegalArgumentException(
                    "Unknown category '" + v + "'. Accepted values: " + PsychologicalRequestDto.CATEGORIES);
        };
    }
    private String toSupportType(String v) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Support type is required. Accepted values: INDIVIDUAL, GROUP, CRISIS");
        }
        return switch (v.toUpperCase().trim()) {
            case "INDIVIDUAL" -> "INDIVIDUAL";
            case "GROUP"      -> "GROUP";
            case "CRISIS"     -> "CRISIS";
            default -> throw new IllegalArgumentException(
                    "Unknown support type '" + v + "'. Accepted values: INDIVIDUAL, GROUP, CRISIS");
        };
    }
    private String toUrgency(String v) {
        if (v == null || v.isBlank()) return "MEDIUM";   // column default
        return switch (v.toUpperCase().trim()) {
            case "CRITICAL" -> "CRITICAL";
            case "HIGH"     -> "HIGH";
            case "MEDIUM"   -> "MEDIUM";
            case "LOW"      -> "LOW";
            default -> throw new IllegalArgumentException(
                    "Unknown urgency level '" + v + "'. Accepted values: LOW, MEDIUM, HIGH, CRITICAL");
        };
    }
    private String toFormat(String v) {
        if (v == null || v.isBlank()) return "CHAT";     // column default
        return switch (v.toUpperCase().trim()) {
            case "CHAT"                  -> "CHAT";
            case "AUDIO","AUDIO_CALL"    -> "AUDIO";
            case "VIDEO","VIDEO_SESSION" -> "VIDEO";
            default -> throw new IllegalArgumentException(
                    "Unknown format '" + v + "'. Accepted values: CHAT, AUDIO, VIDEO");
        };
    }

    private void updateAssignmentStatus(Long requestId, String newStatus, LocalDateTime completedAt) {
        assignmentRepository
                .findFirstByPsychologicalRequestIdAndStatusOrderByAssignedAtDesc(requestId, "ASSIGNED")
                .or(() -> assignmentRepository.findFirstByPsychologicalRequestIdOrderByAssignedAtDesc(requestId))
                .ifPresent(assignment -> {
                    assignment.setStatus(newStatus);
                    assignment.setCompletedAt(completedAt);
                    assignmentRepository.save(assignment);
                });
    }
}
