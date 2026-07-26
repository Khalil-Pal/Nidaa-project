package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.AssignmentHistoryDTO;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.exception.UnauthorizedException;
import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Organization;
import com.humanitarian.platform.model.PsychologicalRequest;
import com.humanitarian.platform.model.Psychologist;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.OrganizationRepository;
import com.humanitarian.platform.repository.PsychologicalRequestRepository;
import com.humanitarian.platform.repository.PsychologistRepository;
import com.humanitarian.platform.repository.UserRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class AssignmentHistoryService {

    private final AssignmentRepository assignmentRepository;
    private final HelpRequestRepository helpRequestRepository;
    private final PsychologicalRequestRepository psychologicalRequestRepository;
    private final VolunteerRepository volunteerRepository;
    private final OrganizationRepository organizationRepository;
    private final PsychologistRepository psychologistRepository;
    private final UserRepository userRepository;
    private final UserService userService;

    public AssignmentHistoryService(AssignmentRepository assignmentRepository,
                                    HelpRequestRepository helpRequestRepository,
                                    PsychologicalRequestRepository psychologicalRequestRepository,
                                    VolunteerRepository volunteerRepository,
                                    OrganizationRepository organizationRepository,
                                    PsychologistRepository psychologistRepository,
                                    UserRepository userRepository,
                                    UserService userService) {
        this.assignmentRepository = assignmentRepository;
        this.helpRequestRepository = helpRequestRepository;
        this.psychologicalRequestRepository = psychologicalRequestRepository;
        this.volunteerRepository = volunteerRepository;
        this.organizationRepository = organizationRepository;
        this.psychologistRepository = psychologistRepository;
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @Transactional(readOnly = true)
    public List<AssignmentHistoryDTO> getHelpRequestHistory(Long requestId) {
        HelpRequest request = helpRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Help request not found: " + requestId));
        List<Assignment> assignments = assignmentRepository.findByRequestIdOrderByAssignedAtAsc(requestId);
        authorizeHelpHistory(request, assignments, userService.getCurrentUser());
        return assignments.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<AssignmentHistoryDTO> getPsychologicalRequestHistory(Long requestId) {
        PsychologicalRequest request = psychologicalRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Psychological request not found: " + requestId));
        List<Assignment> assignments = assignmentRepository
                .findByPsychologicalRequestIdOrderByAssignedAtAsc(requestId);
        authorizePsychologicalHistory(request, assignments, userService.getCurrentUser());
        return assignments.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<AssignmentHistoryDTO> getMyHistory() {
        User currentUser = userService.getCurrentUser();
        List<Assignment> assignments = switch (currentUser.getRole()) {
            case ADMIN -> assignmentRepository.findAllByOrderByAssignedAtDesc();
            case VOLUNTEER -> volunteerRepository.findByUserId(currentUser.getId())
                    .map(Volunteer::getId)
                    .map(assignmentRepository::findByVolunteerIdOrderByAssignedAtDesc)
                    .orElseGet(List::of);
            case ORGANIZATION -> organizationRepository.findByUserId(currentUser.getId())
                    .map(Organization::getId)
                    .map(assignmentRepository::findByOrganizationIdOrderByAssignedAtDesc)
                    .orElseGet(List::of);
            case PSYCHOLOGIST -> psychologistRepository.findByUserId(currentUser.getId())
                    .map(Psychologist::getId)
                    .map(assignmentRepository::findByPsychologistIdOrderByAssignedAtDesc)
                    .orElseGet(List::of);
            case BENEFICIARY -> beneficiaryAssignments(currentUser.getId());
        };

        return assignments.stream()
                .sorted(Comparator.comparing(
                        Assignment::getAssignedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AssignmentHistoryDTO> getAllHistory() {
        return assignmentRepository.findAllByOrderByAssignedAtDesc()
                .stream()
                .map(this::toDto)
                .toList();
    }

    private List<Assignment> beneficiaryAssignments(Long beneficiaryId) {
        List<Assignment> assignments = new ArrayList<>();
        helpRequestRepository.findByBeneficiaryId(beneficiaryId)
                .forEach(request -> assignments.addAll(
                        assignmentRepository.findByRequestIdOrderByAssignedAtAsc(request.getId())));
        psychologicalRequestRepository.findByBeneficiaryId(beneficiaryId)
                .forEach(request -> assignments.addAll(
                        assignmentRepository.findByPsychologicalRequestIdOrderByAssignedAtAsc(request.getId())));
        return assignments;
    }

    private void authorizeHelpHistory(HelpRequest request,
                                      List<Assignment> assignments,
                                      User currentUser) {
        if (currentUser.getRole() == UserRole.ADMIN
                || request.getBeneficiaryId().equals(currentUser.getId())) {
            return;
        }

        boolean assignedToCurrentUser = switch (currentUser.getRole()) {
            case VOLUNTEER -> volunteerRepository.findByUserId(currentUser.getId())
                    .map(Volunteer::getId)
                    .map(id -> assignments.stream().anyMatch(a -> Objects.equals(a.getVolunteerId(), id)))
                    .orElse(false);
            case ORGANIZATION -> organizationRepository.findByUserId(currentUser.getId())
                    .map(Organization::getId)
                    .map(id -> assignments.stream().anyMatch(a -> Objects.equals(a.getOrganizationId(), id)))
                    .orElse(false);
            default -> false;
        };

        if (!assignedToCurrentUser) {
            throw new UnauthorizedException("You do not have permission to view this assignment history.");
        }
    }

    private void authorizePsychologicalHistory(PsychologicalRequest request,
                                               List<Assignment> assignments,
                                               User currentUser) {
        if (currentUser.getRole() == UserRole.ADMIN
                || request.getBeneficiaryId().equals(currentUser.getId())) {
            return;
        }

        boolean assignedToCurrentUser = currentUser.getRole() == UserRole.PSYCHOLOGIST
                && psychologistRepository.findByUserId(currentUser.getId())
                .map(Psychologist::getId)
                .map(id -> assignments.stream().anyMatch(a -> Objects.equals(a.getPsychologistId(), id)))
                .orElse(false);

        if (!assignedToCurrentUser) {
            throw new UnauthorizedException("You do not have permission to view this assignment history.");
        }
    }

    private AssignmentHistoryDTO toDto(Assignment assignment) {
        boolean psychological = "PSYCHOLOGICAL_REQUEST".equals(assignment.getRequestType());
        Long requestId = psychological
                ? assignment.getPsychologicalRequestId()
                : assignment.getRequestId();

        LocalDateTime requestCreatedAt = psychological
                ? psychologicalRequestRepository.findById(requestId)
                    .map(PsychologicalRequest::getCreatedAt).orElse(null)
                : helpRequestRepository.findById(requestId)
                    .map(HelpRequest::getCreatedAt).orElse(null);

        String requestSummary = psychological
                ? psychologicalRequestRepository.findById(requestId)
                    .map(PsychologicalRequest::getCategory).orElse("Psychological request")
                : helpRequestRepository.findById(requestId)
                    .map(HelpRequest::getTitle).orElse("Help request");

        Assignee assignee = resolveAssignee(assignment);
        String assignedByName = Optional.ofNullable(assignment.getAssignedBy())
                .flatMap(userRepository::findById)
                .map(User::getFullName)
                .orElse(assignment.getAssignedBy() == null ? "System" : "Unknown user");

        return AssignmentHistoryDTO.builder()
                .assignmentId(assignment.getId())
                .requestType(assignment.getRequestType())
                .requestId(requestId)
                .requestSummary(requestSummary)
                .assigneeType(assignee.type())
                .assigneeId(assignee.id())
                .assigneeName(assignee.name())
                .assignedById(assignment.getAssignedBy())
                .assignedByName(assignedByName)
                .assignmentSource(assignment.getAssignmentSource())
                .automated(!"MANUAL".equals(assignment.getAssignmentSource()))
                .status(assignment.getStatus())
                .assignedAt(assignment.getAssignedAt())
                .completedAt(assignment.getCompletedAt())
                .waitingTimeMinutes(minutesBetween(requestCreatedAt, assignment.getAssignedAt()))
                .assignmentDurationMinutes(minutesBetween(
                        assignment.getAssignedAt(), assignment.getCompletedAt()))
                .notes(assignment.getNotes())
                .build();
    }

    private Assignee resolveAssignee(Assignment assignment) {
        if (assignment.getVolunteerId() != null) {
            return volunteerRepository.findById(assignment.getVolunteerId())
                    .map(volunteer -> new Assignee(
                            "VOLUNTEER",
                            volunteer.getId(),
                            volunteer.getUser() != null
                                    ? volunteer.getUser().getFullName()
                                    : "Volunteer #" + volunteer.getId()))
                    .orElse(new Assignee(
                            "VOLUNTEER", assignment.getVolunteerId(),
                            "Volunteer #" + assignment.getVolunteerId()));
        }
        if (assignment.getOrganizationId() != null) {
            return organizationRepository.findById(assignment.getOrganizationId())
                    .map(organization -> new Assignee(
                            "ORGANIZATION", organization.getId(), organization.getOfficialName()))
                    .orElse(new Assignee(
                            "ORGANIZATION", assignment.getOrganizationId(),
                            "Organization #" + assignment.getOrganizationId()));
        }
        if (assignment.getPsychologistId() != null) {
            return psychologistRepository.findById(assignment.getPsychologistId())
                    .map(psychologist -> new Assignee(
                            "PSYCHOLOGIST",
                            psychologist.getId(),
                            psychologist.getUser() != null
                                    ? psychologist.getUser().getFullName()
                                    : "Psychologist #" + psychologist.getId()))
                    .orElse(new Assignee(
                            "PSYCHOLOGIST", assignment.getPsychologistId(),
                            "Psychologist #" + assignment.getPsychologistId()));
        }
        return new Assignee("UNASSIGNED", null, "Unassigned");
    }

    private Long minutesBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return null;
        }
        return Math.max(0, Duration.between(start, end).toMinutes());
    }

    private record Assignee(String type, Long id, String name) {
    }
}
