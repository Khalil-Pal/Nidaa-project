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
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.exception.UnauthorizedException;
import com.humanitarian.platform.util.HelpTypeNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class HelpRequestService {

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

    @Transactional
    public HelpRequest createRequest(HelpRequestDto dto) {
        User currentUser = userService.getCurrentUser();

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
                .beneficiaryId(currentUser.getId())
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

        HelpRequest request = getRequestById(requestId);
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
            throw new BusinessException("Request is no longer available or already assigned.");
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

        HelpRequest saved = getRequestById(requestId);
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

    private String mapUrgency(String raw) {
        if (raw == null) return "MEDIUM";
        return switch (raw.toUpperCase().trim()) {
            case "CRITICAL" -> "CRITICAL";
            case "HIGH"     -> "HIGH";
            case "LOW"      -> "LOW";
            default         -> "MEDIUM";
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
        return helpRequestRepository.findByBeneficiaryId(
                currentUser.getId(),
                PageRequest.of(0, 50, Sort.by("createdAt").descending())).getContent();
    }

    public HelpRequest getRequestById(Long id) {
        return helpRequestRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Help request not found: " + id));
    }

    private static final Map<String, Set<String>> VALID_TRANSITIONS = Map.of(
            "PENDING",   Set.of("ASSIGNED", "CANCELLED"),
            "ASSIGNED",  Set.of("COMPLETED", "CANCELLED"),
            "COMPLETED", Set.of(),
            "CANCELLED", Set.of()
    );

    @Transactional
    public HelpRequest updateStatus(Long id, String status) {
        HelpRequest request = getRequestById(id);
        User currentUser = userService.getCurrentUser();
        String role = currentUser.getRole().name().toLowerCase();

        // Ownership check — only the right people can touch this request
        boolean canUpdate = role.equals("admin")
                || (role.equals("beneficiary") && request.getBeneficiaryId().equals(currentUser.getId()))
                || role.equals("volunteer")
                || role.equals("organization")
                || role.equals("psychologist");

        if (!canUpdate) {
            throw new UnauthorizedException("You do not have permission to update this request.");
        }

        // Transition validation — no going backwards or into invalid states
        String current = request.getStatus();
        String next = status.toUpperCase();

        if (!VALID_TRANSITIONS.getOrDefault(current, Set.of()).contains(next)) {
            throw new BusinessException("Invalid status transition: cannot move from " + current + " to " + next);
        }

        // Use native SQL to update the PostgreSQL ENUM status column — JPA save() cannot cast VARCHAR to ENUM
        LocalDateTime statusChangedAt = LocalDateTime.now();
        if ("COMPLETED".equals(next)) {
            helpRequestRepository.updateStatusCompleted(id, next, statusChangedAt);
            updateAssignmentStatus(id, next, statusChangedAt);
        } else if ("CANCELLED".equals(next)) {
            helpRequestRepository.updateStatusCancelled(id, next, statusChangedAt);
            updateAssignmentStatus(id, next, statusChangedAt);
        } else {
            helpRequestRepository.updateStatusNative(id, next);
        }

        return getRequestById(id);
    }

    @Transactional
    public void deleteRequest(Long id) {
        HelpRequest request = getRequestById(id);
        User currentUser = userService.getCurrentUser();
        if (!request.getBeneficiaryId().equals(currentUser.getId())) {
            throw new UnauthorizedException("You can only delete your own requests");
        }
        helpRequestRepository.delete(request);
    }

    public List<HelpRequest> getPendingByPriority() {
        return helpRequestRepository.findByStatusOrderByPriorityScoreDesc("PENDING");
    }

    @Transactional(readOnly = true)
    public List<RankedRequestDTO> getRankedWithSuggestions(List<Volunteer> availableVolunteers) {
        Map<CapacityLookupKey, Map<Long, ProviderCapacityAssessment>> capacityByRequest =
                new HashMap<>();
        return helpRequestRepository.findByStatusOrderByPriorityScoreDesc("PENDING").stream()
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
                })
                .toList();
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
