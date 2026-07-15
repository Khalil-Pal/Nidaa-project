package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.HelpRequestDto;
import com.humanitarian.platform.dto.RankedRequestDTO;
import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.exception.UnauthorizedException;
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

    @Transactional
    public HelpRequest createRequest(HelpRequestDto dto) {
        User currentUser = userService.getCurrentUser();

        String helpType = mapHelpType(dto.getHelpType());
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
        return helpRequestRepository.save(request);
    }

    /**
     * Assigns the current user (volunteer or organization) to a help request.
     * All FK lookups and repository calls happen here — the controller just calls this.
     */
    @Transactional
    public Map<String, Object> assignToMe(Long requestId) {
        User currentUser = userService.getCurrentUser();
        String role = currentUser.getRole().name().toLowerCase();
        int updated = 0;
        Long assignedVolunteerId = null;
        Long assignedOrganizationId = null;

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
            updated = helpRequestRepository.assignVolunteer(requestId, volunteerId, "ASSIGNED", "PENDING");
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
            updated = helpRequestRepository.assignOrganization(requestId, organizationId, "ASSIGNED", "PENDING");
            assignedOrganizationId = organizationId;

        } else {
            helpRequestRepository.updateStatusNative(requestId, "ASSIGNED");
            updated = 1;
        }

        if (updated == 0) {
            throw new BusinessException("Request is no longer available or already assigned.");
        }

        if (assignedVolunteerId != null || assignedOrganizationId != null) {
            Assignment assignment = Assignment.builder()
                    .requestId(requestId)
                    .volunteerId(assignedVolunteerId)
                    .organizationId(assignedOrganizationId)
                    .assignedBy(currentUser.getId())
                    .status("ASSIGNED")
                    .assignedAt(LocalDateTime.now())
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

    private String mapHelpType(String raw) {
        if (raw == null) return "OTHER";
        return switch (raw.toUpperCase().trim()) {
            case "MEDICAL"       -> "MEDICAL";
            case "FOOD"          -> "FOOD";
            case "SHELTER"       -> "SHELTER";
            case "WATER"         -> "WATER";
            case "CLOTHING"      -> "CLOTHING";
            case "PSYCHOLOGICAL" -> "PSYCHOLOGICAL";
            default              -> "OTHER";
        };
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
        return helpRequestRepository.findByStatusOrderByPriorityScoreDesc("PENDING").stream()
                .map(request -> {
                    int score = request.getPriorityScore() != null
                            ? request.getPriorityScore()
                            : priorityScoreService.calculate(request);
                    var nearest = geoMatchingService.findNearestVolunteer(request, availableVolunteers);
                    return RankedRequestDTO.builder()
                            .request(request)
                            .priorityScore(score)
                            .suggestedVolunteerName(nearest.map(this::volunteerName).orElse("N/A"))
                            .distanceKm(nearest
                                    .filter(volunteer -> hasCoordinates(request)
                                            && volunteer.getLatitude() != null
                                            && volunteer.getLongitude() != null)
                                    .map(volunteer -> geoMatchingService.haversine(
                                            request.getLatitude(),
                                            request.getLongitude(),
                                            volunteer.getLatitude(),
                                            volunteer.getLongitude()))
                                    .orElse(null))
                            .build();
                })
                .toList();
    }

    public List<HelpRequest> getRequestsByType(String helpType) {
        return helpRequestRepository.findByHelpType(mapHelpType(helpType));
    }

    public Map<String, Object> getContactInfo(Long requestId) {
        HelpRequest req = getRequestById(requestId);
        Map<String, Object> contact = new HashMap<>();
        userRepository.findById(req.getBeneficiaryId()).ifPresent(b -> {
            contact.put("name",  b.getFullName());
            contact.put("email", b.getEmail());
            contact.put("phone", b.getPhone() != null ? b.getPhone() : "Not provided");
        });
        return contact;
    }

    private void updateAssignmentStatus(Long requestId, String newStatus, LocalDateTime completedAt) {
        assignmentRepository
                .findFirstByRequestIdAndStatusOrderByAssignedAtDesc(requestId, "ASSIGNED")
                .or(() -> assignmentRepository.findFirstByRequestIdOrderByAssignedAtDesc(requestId))
                .ifPresent(assignment -> {
                    assignment.setStatus(newStatus);
                    assignment.setCompletedAt(completedAt);
                    assignmentRepository.save(assignment);
                });
    }

    private boolean hasCoordinates(HelpRequest request) {
        return request.getLatitude() != null && request.getLongitude() != null;
    }

    private String volunteerName(Volunteer volunteer) {
        if (volunteer.getUser() != null && volunteer.getUser().getFullName() != null) {
            return volunteer.getUser().getFullName();
        }
        return volunteer.getId() != null ? "Volunteer #" + volunteer.getId() : "Volunteer";
    }
}
