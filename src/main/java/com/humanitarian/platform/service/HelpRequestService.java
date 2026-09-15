package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.HelpRequestDto;
import com.humanitarian.platform.dto.ProviderCapacityAssessment;
import com.humanitarian.platform.dto.ProviderCapacityReservation;
import com.humanitarian.platform.dto.RankedRequestDTO;
import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.OrganizationRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.ConflictException;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.exception.UnauthorizedException;
import com.humanitarian.platform.util.RequestTransitions;
import com.humanitarian.platform.util.HelpTypeNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import com.humanitarian.platform.model.UserRole;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class HelpRequestService {

    private static final Logger log = LoggerFactory.getLogger(HelpRequestService.class);

    @Autowired
    private HelpRequestRepository helpRequestRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PriorityScoreService priorityScoreService;

    @Autowired
    private GeoMatchingService geoMatchingService;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private VolunteerRepository volunteerRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private AutomaticAssignmentService automaticAssignmentService;

    @Autowired
    private ProviderResourceService providerResourceService;

    @Autowired
    private AdminAuditService adminAudit;

    @Autowired
    private NotificationService notifications;

    @Transactional
    public HelpRequest createRequest(HelpRequestDto dto) {
        User currentUser = userService.getCurrentUser();
        User beneficiary = resolveBeneficiary(dto, currentUser);
        Long filedByUserId = Objects.equals(beneficiary.getId(), currentUser.getId())
                ? null : currentUser.getId();

        String helpType = HelpTypeNormalizer.normalize(dto.getHelpType());
        String urgency  = mapUrgency(dto.getUrgencyLevel());

        int count = 1;
        if (dto.getPeopleCount() != null && dto.getPeopleCount() > 0) {
            count = dto.getPeopleCount();
        } else if (dto.getNumberOfPeople() != null && !dto.getNumberOfPeople().isBlank()) {
            try { count = Integer.parseInt(dto.getNumberOfPeople().trim().split("[^0-9]")[0]); }
            catch (Exception ignored) {}
        }

        String desc = (dto.getDescription() != null && !dto.getDescription().isBlank())
                ? dto.getDescription() : dto.getTitle();

        HelpRequest request = HelpRequest.builder()
                .beneficiaryId(beneficiary.getId())
                .filedByUserId(filedByUserId)
                .title(dto.getTitle().trim())
                .description(desc)
                .helpType(helpType)
                .urgencyLevel(urgency)
                .peopleCount(count)
                .hasChildren(dto.getHasChildren() != null ? dto.getHasChildren() : false)
                .hasElderly(dto.getHasElderly() != null ? dto.getHasElderly() : false)
                .hasDisabled(dto.getHasDisabled() != null ? dto.getHasDisabled() : false)
                .address(dto.getAddress())
                .latitude(dto.getLatitude())
                .longitude(dto.getLongitude())
                .status("PENDING")
                .build();

        request.setPriorityScore(priorityScoreService.calculate(request));
        HelpRequest saved = helpRequestRepository.save(request);
        automaticAssignmentService.assignNearestProvider(saved);
        return helpRequestRepository.findById(saved.getId()).orElse(saved);
    }

    /**
     * Decides who the request is for (ON-1). A beneficiary always files for
     * themselves. A volunteer or organization may name another person by email,
     * in which case that person's account is reused or created and the caller
     * becomes the filer.
     */
    private User resolveBeneficiary(HelpRequestDto dto, User currentUser) {
        if (!dto.targetsAnotherPerson()) {
            return currentUser;
        }
        if (currentUser.getRole() != UserRole.VOLUNTEER
                && currentUser.getRole() != UserRole.ORGANIZATION) {
            throw new BusinessException(
                    "Only volunteers and organizations can file a request on someone else's behalf.");
        }
        if (dto.getBeneficiaryEmail() == null || dto.getBeneficiaryEmail().isBlank()) {
            throw new BusinessException(
                    "beneficiaryEmail is required when filing on someone else's behalf.");
        }
        String email = dto.getBeneficiaryEmail().toLowerCase().trim();
        if (email.equalsIgnoreCase(currentUser.getEmail())) {
            throw new BusinessException("Leave the beneficiary fields empty to file for yourself.");
        }

        User existing = userRepository.findByEmail(email).orElse(null);
        if (existing != null) {
            if (existing.getRole() != UserRole.BENEFICIARY) {
                throw new BusinessException(
                        "That email belongs to a " + existing.getRole().name().toLowerCase()
                                + " account, not a beneficiary.");
            }
            return existing;
        }
        if (dto.getBeneficiaryName() == null || dto.getBeneficiaryName().isBlank()) {
            throw new BusinessException(
                    "beneficiaryName is required when the person does not have an account yet.");
        }
        return userService.createUnverifiedBeneficiary(
                dto.getBeneficiaryName(), email, dto.getBeneficiaryPhone());
    }

    /**
     * Assigns the current user (volunteer or organization) to a help request.
     * All FK lookups and repository calls happen here — the controller just calls this.
     */
    @Transactional
    public Map<String, Object> assignToMe(Long requestId) {
        User currentUser = userService.getCurrentUser();
        String role = currentUser.getRole().name().toLowerCase();
        Long assignedVolunteerId = null;
        Long assignedOrganizationId = null;

        if (!role.equals("volunteer") && !role.equals("organization")) {
            throw new UnauthorizedException("Only volunteers and organizations can accept help requests.");
        }

        HelpRequest request = findOrThrow(requestId);
        // A provider who filed a request must not also deliver it, or they could
        // manufacture requests and self-assign to inflate their completion count.
        if (Objects.equals(request.getFiledByUserId(), currentUser.getId())) {
            throw new BusinessException("You cannot accept a request you filed on someone's behalf.");
        }
        providerResourceService.requireUsableResource(
                currentUser.getId(), request.getHelpType(), request.getPeopleCount());

        if (role.equals("volunteer")) {
            Long volunteerId;
            try {
                volunteerId = jdbc.queryForObject(
                        "SELECT volunteer_id FROM volunteers WHERE user_id = ?",
                        Long.class, currentUser.getId());
            } catch (EmptyResultDataAccessException e) {
                throw new ResourceNotFoundException("Volunteer profile not found. Contact admin.");
            } catch (Exception e) {
                throw new BusinessException("Error retrieving volunteer profile: " + e.getMessage());
            }
            if (volunteerRepository.claimIfAvailable(volunteerId) == 0) {
                throw new BusinessException("Your volunteer profile is not currently available.");
            }
            assignedVolunteerId = volunteerId;

        } else if (role.equals("organization")) {
            Long organizationId;
            try {
                organizationId = jdbc.queryForObject(
                        "SELECT organization_id FROM organizations WHERE user_id = ?",
                        Long.class, currentUser.getId());
            } catch (EmptyResultDataAccessException e) {
                throw new ResourceNotFoundException("Organization profile not found. Contact admin.");
            } catch (Exception e) {
                throw new BusinessException("Error retrieving organization profile: " + e.getMessage());
            }
            if (organizationRepository.claimIfAvailable(organizationId) == 0) {
                throw new BusinessException("Your organization profile is not currently available.");
            }
            assignedOrganizationId = organizationId;

        }

        ProviderCapacityReservation reservation = providerResourceService
                .reserveForAssignment(
                        currentUser.getId(), request.getHelpType(), request.getPeopleCount())
                .orElse(null);
        if (reservation == null) {
            releaseClaim(assignedVolunteerId, assignedOrganizationId);
            throw new BusinessException(
                    "Your matching provider resource is no longer available.");
        }

        int updated = assignedVolunteerId != null
                ? helpRequestRepository.assignVolunteer(
                        requestId, assignedVolunteerId, "ASSIGNED", "PENDING")
                : helpRequestRepository.assignOrganization(
                        requestId, assignedOrganizationId, "ASSIGNED", "PENDING");

        if (updated == 0) {
            providerResourceService.restoreReservation(reservation);
            releaseClaim(assignedVolunteerId, assignedOrganizationId);
            throw new ConflictException("Request is no longer available or already assigned.");
        }

        if (assignedVolunteerId != null || assignedOrganizationId != null) {
            Assignment assignment = Assignment.builder()
                    .requestId(requestId)
                    .volunteerId(assignedVolunteerId)
                    .organizationId(assignedOrganizationId)
                    .assignedBy(currentUser.getId())
                    .requestType("HELP_REQUEST")
                    .assignmentSource("MANUAL")
                    .status("ASSIGNED")
                    .assignedAt(LocalDateTime.now())
                    .resourceUserId(reservation.hasNumericReservation()
                            ? reservation.userId() : null)
                    .resourceHelpType(reservation.hasNumericReservation()
                            ? reservation.helpType() : null)
                    .reservedCapacityAmount(reservation.reservedAmount())
                    .build();
            assignmentRepository.save(assignment);
        }
        log.info("Request {} assigned manually to {} {} by user {}", requestId,
                assignedVolunteerId != null ? "volunteer" : "organization",
                assignedVolunteerId != null ? assignedVolunteerId : assignedOrganizationId, currentUser.getId());

        HelpRequest saved = findOrThrow(requestId);
        // N-1: the people waiting learn of the acceptance without reloading
        for (Long recipient : requestParties(saved, currentUser.getId())) {
            notifications.notify(recipient, "Your request was accepted",
                    currentUser.getFullName() + " accepted \"" + saved.getTitle() + "\" and will be in touch.",
                    NotificationService.REF_HELP_REQUEST, requestId);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("requestId",  requestId);
        result.put("status",     saved.getStatus());
        result.put("workerName", currentUser.getFullName());

        userRepository.findById(saved.getBeneficiaryId()).ifPresent(b -> {
            result.put("requesterName",  b.getFullName());
            result.put("requesterEmail", b.getEmail());
            result.put("requesterPhone", b.getPhone() != null ? b.getPhone() : "Not provided");
        });

        return result;
    }

    // Unknown urgency is an error, not MEDIUM: silently downgrading a
    // mistyped CRITICAL in a triage system would be the worst possible default.
    private String mapUrgency(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Urgency level is required. Accepted values: LOW, MEDIUM, HIGH, CRITICAL");
        }
        return switch (raw.toUpperCase().trim()) {
            case "CRITICAL" -> "CRITICAL";
            case "HIGH"     -> "HIGH";
            case "MEDIUM"   -> "MEDIUM";
            case "LOW"      -> "LOW";
            default -> throw new IllegalArgumentException(
                    "Unknown urgency level '" + raw + "'. Accepted values: LOW, MEDIUM, HIGH, CRITICAL");
        };
    }

    public List<HelpRequest> getAllRequests() {
        return helpRequestRepository.findAll(
                PageRequest.of(0, 100, Sort.by("createdAt").descending())).getContent();
    }

    public Page<HelpRequest> getAllRequests(int page, int size) {
        return helpRequestRepository.findAll(
                PageRequest.of(page, size, Sort.by("createdAt").descending()));
    }

    public List<HelpRequest> getRequestsByStatus(String status) {
        return helpRequestRepository.findByStatus(status.toUpperCase());
    }

    public List<HelpRequest> getMyRequests() {
        User currentUser = userService.getCurrentUser();
        return helpRequestRepository.findMine(
                currentUser.getId(),
                PageRequest.of(0, 50, Sort.by("createdAt").descending())).getContent();
    }

    /**
     * Returns the request only if the caller may see it: an admin, the
     * beneficiary, whoever filed it for them, or the assigned provider.
     * Anyone else gets 404 rather than 403, because confirming that the
     * record exists would itself leak information.
     */
    @Transactional(readOnly = true)
    public HelpRequest getRequestById(Long id) {
        HelpRequest request = findOrThrow(id);
        User me = userService.getCurrentUser();
        if (!canView(me, request)) {
            throw new ResourceNotFoundException("Help request not found: " + id);
        }
        return request;
    }

    /** Unguarded lookup for flows that apply their own rule (accept, status change, delete). */
    private HelpRequest findOrThrow(Long id) {
        return helpRequestRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Help request not found: " + id));
    }

    private boolean canView(User me, HelpRequest request) {
        return me.getRole() == UserRole.ADMIN
                || Objects.equals(request.getBeneficiaryId(), me.getId())
                || Objects.equals(request.getFiledByUserId(), me.getId())
                || isAssignedVolunteer(me, request)
                || isAssignedOrganization(me, request);
    }

    // Compares the caller's profile id to the assignee column; user ids and
    // profile ids come from different sequences and must never be compared directly.
    private boolean isAssignedVolunteer(User me, HelpRequest request) {
        if (me.getRole() != UserRole.VOLUNTEER || request.getAssignedVolunteerId() == null) {
            return false;
        }
        return volunteerRepository.findByUserId(me.getId())
                .map(profile -> Objects.equals(profile.getId(), request.getAssignedVolunteerId()))
                .orElse(false);
    }

    private boolean isAssignedOrganization(User me, HelpRequest request) {
        if (me.getRole() != UserRole.ORGANIZATION || request.getAssignedOrganizationId() == null) {
            return false;
        }
        return organizationRepository.findByUserId(me.getId())
                .map(profile -> Objects.equals(profile.getId(), request.getAssignedOrganizationId()))
                .orElse(false);
    }

    // Targets each party may move a request to. Completion is reserved for the
    // assigned provider (or an admin) because it is the delivery record; the
    // beneficiary and the filer may only withdraw.
    private static final Set<String> OWNER_TARGETS    = Set.of("CANCELLED");
    private static final Set<String> PROVIDER_TARGETS = Set.of("COMPLETED", "CANCELLED");
    private static final Set<String> ADMIN_TARGETS    = Set.of("ASSIGNED", "COMPLETED", "CANCELLED");

    @Transactional
    public HelpRequest updateStatus(Long id, String status) {
        HelpRequest request = findOrThrow(id);
        User currentUser = userService.getCurrentUser();
        String current = request.getStatus();
        String next = status.toUpperCase();

        // Authorization before transition validation, so an unrelated caller
        // learns nothing about the request's current state.
        if (!permittedTargets(currentUser, request).contains(next)) {
            throw new UnauthorizedException("You do not have permission to update this request.");
        }

        // Someone else already applied this transition: a conflict of state, not
        // a bad request, so a client racing another actor sees 409 either way (B-4).
        if (next.equals(current)) {
            throw new ConflictException("This request is already " + current + ".");
        }

        // Transition validation — no going backwards or into invalid states
        if (!RequestTransitions.allows(current, next)) {
            throw new BusinessException("Invalid status transition: cannot move from " + current + " to " + next);
        }

        // The UPDATE is guarded by the status validated above, so two callers
        // racing from the same state cannot both win (B-4).
        LocalDateTime statusChangedAt = LocalDateTime.now();
        int updated;
        if ("COMPLETED".equals(next)) {
            updated = helpRequestRepository.updateStatusCompleted(id, next, statusChangedAt, current);
        } else if ("CANCELLED".equals(next)) {
            updated = helpRequestRepository.updateStatusCancelled(id, next, statusChangedAt, current);
        } else {
            updated = helpRequestRepository.updateStatusNative(id, next, current);
        }
        if (updated == 0) {
            throw new ConflictException("This request was updated by someone else. Reload and try again.");
        }
        if ("COMPLETED".equals(next) || "CANCELLED".equals(next)) {
            updateAssignmentStatus(id, next, statusChangedAt);
        }
        log.info("Request {} {} -> {} by user {} ({})", id, current, next, currentUser.getId(), currentUser.getRole());
        if (currentUser.getRole() == UserRole.ADMIN) {
            adminAudit.record("REQUEST_STATUS_CHANGED", "HELP_REQUEST", id, Map.of("from", current, "to", next));
        }

        // N-1: every other party to the request hears about the change
        for (Long recipient : requestParties(request, currentUser.getId())) {
            notifications.notify(recipient, "Request " + statusWord(next),
                    "\"" + request.getTitle() + "\" is now " + statusWord(next) + ".",
                    NotificationService.REF_HELP_REQUEST, id);
        }

        return findOrThrow(id);
    }

    /**
     * The people with a stake in a request other than the actor: the beneficiary,
     * the filer if someone filed it for them, and the assigned provider.
     */
    private Set<Long> requestParties(HelpRequest request, Long actorId) {
        Set<Long> parties = new java.util.LinkedHashSet<>();
        parties.add(request.getBeneficiaryId());
        parties.add(request.getFiledByUserId());
        if (request.getAssignedVolunteerId() != null) {
            volunteerRepository.findById(request.getAssignedVolunteerId())
                    .ifPresent(v -> parties.add(v.getUser().getId()));
        }
        if (request.getAssignedOrganizationId() != null) {
            organizationRepository.findById(request.getAssignedOrganizationId())
                    .ifPresent(o -> parties.add(o.getUser().getId()));
        }
        parties.remove(null);
        parties.remove(actorId);
        return parties;
    }

    private static String statusWord(String status) {
        return switch (status) {
            case "IN_PROGRESS" -> "in progress";
            default -> status.toLowerCase(java.util.Locale.ROOT);
        };
    }

    private Set<String> permittedTargets(User me, HelpRequest request) {
        if (me.getRole() == UserRole.ADMIN) {
            return ADMIN_TARGETS;
        }
        if (Objects.equals(request.getBeneficiaryId(), me.getId())
                || Objects.equals(request.getFiledByUserId(), me.getId())) {
            return OWNER_TARGETS;
        }
        if (isAssignedVolunteer(me, request) || isAssignedOrganization(me, request)) {
            return PROVIDER_TARGETS;
        }
        return Set.of();
    }

    @Transactional
    public void deleteRequest(Long id) {
        HelpRequest request = findOrThrow(id);
        User currentUser = userService.getCurrentUser();
        if (!request.getBeneficiaryId().equals(currentUser.getId())) {
            throw new UnauthorizedException("You can only delete your own requests");
        }
        helpRequestRepository.delete(request);
    }

    /**
     * Highest priority first; ties broken oldest-first, then by id, so the
     * order is total and pages neither repeat nor skip a request (Q-3).
     * idx_help_requests_status_priority covers the leading sort.
     */
    static final Sort PENDING_ORDER = Sort.by(Sort.Order.desc("priorityScore"),
            Sort.Order.asc("createdAt"), Sort.Order.asc("id"));

    public Page<HelpRequest> getPendingByPriority(int page, int size) {
        return helpRequestRepository.findByStatus("PENDING", PageRequest.of(page, size, PENDING_ORDER));
    }

    @Transactional(readOnly = true)
    public Page<RankedRequestDTO> getRankedWithSuggestions(List<Volunteer> availableVolunteers,
                                                           int page, int size) {
        Map<CapacityLookupKey, Map<Long, ProviderCapacityAssessment>> capacityByRequest =
                new HashMap<>();
        return getPendingByPriority(page, size)
                .map(request -> {
                    int score = request.getPriorityScore() != null
                            ? request.getPriorityScore()
                            : priorityScoreService.calculate(request);
                    String helpType = HelpTypeNormalizer.normalize(request.getHelpType());
                    CapacityLookupKey capacityKey = new CapacityLookupKey(
                            helpType, request.getPeopleCount());
                    Map<Long, ProviderCapacityAssessment> capacityByUserId =
                            capacityByRequest.computeIfAbsent(
                                    capacityKey,
                                    key -> providerResourceService
                                            .findEligibleProviderCapacityAssessments(
                                                    key.helpType(), key.peopleCount()));
                    List<Volunteer> resourceMatchedVolunteers = filterByProviderResource(
                            availableVolunteers, capacityByUserId.keySet());
                    var nearest = geoMatchingService.findNearestProvider(
                            request, resourceMatchedVolunteers, List.of());
                    ProviderCapacityAssessment capacity = nearest
                            .map(match -> capacityByUserId.get(match.userId()))
                            .orElse(null);
                    return RankedRequestDTO.builder()
                            .request(request)
                            .priorityScore(score)
                            .suggestedVolunteerName(nearest
                                    .map(GeoMatchingService.ProviderMatch::providerName)
                                    .orElse("N/A"))
                            .distanceKm(nearest
                                    .map(GeoMatchingService.ProviderMatch::distanceKm)
                                    .orElse(null))
                            .capacityMode(capacity == null ? null : capacity.getCapacityMode())
                            .capacityAmount(capacity == null ? null : capacity.getCapacityAmount())
                            .capacitySufficient(
                                    capacity == null ? null : capacity.getCapacitySufficient())
                            .build();
                });
    }

    private List<Volunteer> filterByProviderResource(List<Volunteer> volunteers,
                                                     Set<Long> eligibleUserIds) {
        if (volunteers == null || volunteers.isEmpty() || eligibleUserIds.isEmpty()) {
            return List.of();
        }
        return volunteers.stream()
                .filter(volunteer -> volunteer.getUser() != null)
                .filter(volunteer -> eligibleUserIds.contains(volunteer.getUser().getId()))
                .toList();
    }

    public List<HelpRequest> getRequestsByType(String helpType) {
        return helpRequestRepository.findByHelpType(HelpTypeNormalizer.normalize(helpType));
    }

    private void updateAssignmentStatus(Long requestId, String newStatus, LocalDateTime completedAt) {
        assignmentRepository
                .findFirstByRequestIdAndStatusOrderByAssignedAtDesc(requestId, "ASSIGNED")
                .or(() -> assignmentRepository.findFirstByRequestIdOrderByAssignedAtDesc(requestId))
                .ifPresent(assignment -> {
                    if ("CANCELLED".equals(newStatus)
                            && !restoreAssignmentCapacity(assignment, completedAt)) {
                        return;
                    }
                    assignment.setStatus(newStatus);
                    assignment.setCompletedAt(completedAt);
                    assignmentRepository.save(assignment);
                    releaseVolunteerIfIdle(assignment.getVolunteerId());
                    releaseOrganizationIfIdle(assignment.getOrganizationId());
                });
    }

    private boolean restoreAssignmentCapacity(Assignment assignment,
                                              LocalDateTime restoredAt) {
        if (assignment.getReservedCapacityAmount() == null
                || assignment.getCapacityRestoredAt() != null) {
            return true;
        }
        if (assignment.getId() == null
                || assignmentRepository.markCapacityRestored(
                        assignment.getId(), restoredAt) == 0) {
            return false;
        }

        providerResourceService.restoreReservation(new ProviderCapacityReservation(
                assignment.getResourceUserId(),
                assignment.getResourceHelpType(),
                assignment.getReservedCapacityAmount()));
        assignment.setCapacityRestoredAt(restoredAt);
        return true;
    }

    private void releaseClaim(Long volunteerId, Long organizationId) {
        if (volunteerId != null) {
            volunteerRepository.release(volunteerId);
        }
        if (organizationId != null) {
            organizationRepository.release(organizationId);
        }
    }

    private void releaseVolunteerIfIdle(Long volunteerId) {
        if (volunteerId != null
                && assignmentRepository.countByVolunteerIdAndStatus(volunteerId, "ASSIGNED") == 0) {
            volunteerRepository.release(volunteerId);
        }
    }

    private void releaseOrganizationIfIdle(Long organizationId) {
        if (organizationId != null
                && assignmentRepository.countByOrganizationIdAndStatus(
                        organizationId, "ASSIGNED") == 0) {
            organizationRepository.release(organizationId);
        }
    }

    private record CapacityLookupKey(String helpType, Integer peopleCount) {
    }
}
