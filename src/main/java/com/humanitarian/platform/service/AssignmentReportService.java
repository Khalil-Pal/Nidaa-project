package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.AssignmentFeedbackRequest;
import com.humanitarian.platform.dto.AssignmentReportRequest;
import com.humanitarian.platform.dto.AssignmentReportResponse;
import com.humanitarian.platform.exception.BusinessException;
import com.humanitarian.platform.exception.ConflictException;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.exception.UnauthorizedException;
import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Organization;
import com.humanitarian.platform.model.Report;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.OrganizationRepository;
import com.humanitarian.platform.repository.ReportRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Completion reports on help-request assignments (R-1): the assigned volunteer
 * records what was delivered once the assignment is COMPLETED, the beneficiary
 * rates it once, and beneficiary, assigned provider and administrators may read
 * it. Exactly one report per assignment; the UNIQUE constraint from V19 decides
 * a race the service did not see.
 *
 * The {@code reports} table carries {@code volunteer_id NOT NULL}: it records
 * volunteer deliveries. An organization's completed assignment has no report row
 * to write (FUTURE_WORK.md). {@code photos} stays unused: upload is its own
 * security surface and there is no endpoint for it.
 *
 * Existence is revealed only to the request's parties: anyone else gets 404 for
 * any assignment id, the same rule as the request itself.
 */
@Service
public class AssignmentReportService {

    private static final Logger log = LoggerFactory.getLogger(AssignmentReportService.class);

    private final ReportRepository reportRepository;
    private final AssignmentRepository assignmentRepository;
    private final HelpRequestRepository helpRequestRepository;
    private final VolunteerRepository volunteerRepository;
    private final OrganizationRepository organizationRepository;
    private final UserService userService;
    private final NotificationService notifications;

    public AssignmentReportService(ReportRepository reportRepository,
                                   AssignmentRepository assignmentRepository,
                                   HelpRequestRepository helpRequestRepository,
                                   VolunteerRepository volunteerRepository,
                                   OrganizationRepository organizationRepository,
                                   UserService userService,
                                   NotificationService notifications) {
        this.reportRepository = reportRepository;
        this.assignmentRepository = assignmentRepository;
        this.helpRequestRepository = helpRequestRepository;
        this.volunteerRepository = volunteerRepository;
        this.organizationRepository = organizationRepository;
        this.userService = userService;
        this.notifications = notifications;
    }

    @Transactional
    public AssignmentReportResponse submitReport(Long assignmentId, AssignmentReportRequest body) {
        User me = userService.getCurrentUser();
        Context ctx = partyContext(assignmentId, me);
        if (ctx.assignedOrganization) {
            throw new BusinessException("Delivery reports are recorded for volunteer assignments; "
                    + "organization reports are not part of the data model yet.");
        }
        if (!ctx.assignedVolunteer) {
            throw new UnauthorizedException("Only the assigned volunteer may record what was delivered.");
        }
        if (!"COMPLETED".equals(ctx.assignment.getStatus())) {
            throw new BusinessException("A delivery report can be recorded once the assignment is completed.");
        }
        if (reportRepository.existsByAssignmentId(assignmentId)) {
            throw new ConflictException("A report has already been recorded for this assignment.");
        }
        Report saved;
        try {
            saved = reportRepository.saveAndFlush(Report.builder()
                    .assignmentId(assignmentId)
                    .volunteerId(ctx.assignment.getVolunteerId())
                    .description(body.getDescription().trim())
                    .build());
        } catch (DataIntegrityViolationException raceLost) {
            // two submissions at once: uq_reports_assignment kept the first one
            throw new ConflictException("A report has already been recorded for this assignment.");
        }
        log.info("Report {} recorded for assignment {} by volunteer {} (user {})",
                saved.getId(), assignmentId, saved.getVolunteerId(), me.getId());
        for (Long recipient : new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                ctx.request.getBeneficiaryId(), ctx.request.getFiledByUserId()))) {
            notifications.notify(recipient, "Delivery recorded: please rate this help",
                    me.getFullName() + " recorded what was delivered for \"" + ctx.request.getTitle()
                            + "\". A rating helps other people choose.",
                    NotificationService.REF_HELP_REQUEST, ctx.request.getId());
        }
        return toResponse(saved, ctx.request.getId(), me.getFullName());
    }

    @Transactional
    public AssignmentReportResponse submitFeedback(Long assignmentId, AssignmentFeedbackRequest body) {
        User me = userService.getCurrentUser();
        Context ctx = partyContext(assignmentId, me);
        if (!Objects.equals(ctx.request.getBeneficiaryId(), me.getId())) {
            throw new UnauthorizedException("Only the beneficiary may rate this help.");
        }
        Report report = reportRepository.findByAssignmentId(assignmentId)
                .orElseThrow(() -> new BusinessException(
                        "The volunteer has not recorded the delivery yet; you can rate it once they have."));
        if (report.getBeneficiaryRating() != null) {
            throw new ConflictException("You have already rated this help.");
        }
        report.setBeneficiaryRating(body.getRating());
        String feedback = body.getFeedback() == null ? null : body.getFeedback().trim();
        report.setFeedbackFromBeneficiary(feedback == null || feedback.isEmpty() ? null : feedback);
        Report saved = reportRepository.save(report);
        log.info("Assignment {} rated {}/5 by beneficiary {}", assignmentId, body.getRating(), me.getId());
        volunteerUserId(saved.getVolunteerId()).ifPresent(volunteerUser ->
                notifications.notify(volunteerUser, "You received a rating: " + body.getRating() + "/5",
                        "The beneficiary rated your help with \"" + ctx.request.getTitle() + "\".",
                        NotificationService.REF_HELP_REQUEST, ctx.request.getId()));
        return toResponse(saved, ctx.request.getId(), volunteerName(saved.getVolunteerId()));
    }

    @Transactional(readOnly = true)
    public AssignmentReportResponse getReport(Long assignmentId) {
        Context ctx = partyContext(assignmentId, userService.getCurrentUser());
        Report report = reportRepository.findByAssignmentId(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("No report has been recorded for this assignment yet."));
        return toResponse(report, ctx.request.getId(), volunteerName(report.getVolunteerId()));
    }

    // ---- helpers ----------------------------------------------------------------

    private record Context(Assignment assignment, HelpRequest request,
                           boolean assignedVolunteer, boolean assignedOrganization) {
    }

    /**
     * Loads a help-request assignment for one of its parties. Anyone else, and any
     * psychological assignment (those have consultations, CS-1), sees 404.
     */
    private Context partyContext(Long assignmentId, User me) {
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .filter(a -> a.getRequestId() != null)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found"));
        HelpRequest request = helpRequestRepository.findById(assignment.getRequestId())
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found"));
        boolean assignedVolunteer = me.getRole() == UserRole.VOLUNTEER && assignment.getVolunteerId() != null
                && volunteerRepository.findByUserId(me.getId()).map(Volunteer::getId)
                        .map(id -> id.equals(assignment.getVolunteerId())).orElse(false);
        boolean assignedOrganization = me.getRole() == UserRole.ORGANIZATION && assignment.getOrganizationId() != null
                && organizationRepository.findByUserId(me.getId()).map(Organization::getId)
                        .map(id -> id.equals(assignment.getOrganizationId())).orElse(false);
        boolean party = me.getRole() == UserRole.ADMIN
                || Objects.equals(request.getBeneficiaryId(), me.getId())
                || assignedVolunteer || assignedOrganization;
        if (!party) {
            throw new ResourceNotFoundException("Assignment not found");
        }
        return new Context(assignment, request, assignedVolunteer, assignedOrganization);
    }

    private java.util.Optional<Long> volunteerUserId(Long volunteerId) {
        return volunteerRepository.findById(volunteerId).map(v -> v.getUser().getId());
    }

    private String volunteerName(Long volunteerId) {
        return volunteerRepository.findById(volunteerId).map(v -> v.getUser().getFullName()).orElse(null);
    }

    private static AssignmentReportResponse toResponse(Report r, Long requestId, String volunteerName) {
        return new AssignmentReportResponse(r.getId(), r.getAssignmentId(), requestId, r.getVolunteerId(),
                volunteerName, r.getDescription(), r.getFeedbackFromBeneficiary(), r.getBeneficiaryRating(),
                r.getCreatedAt());
    }
}
