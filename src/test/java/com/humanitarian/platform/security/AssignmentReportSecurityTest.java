package com.humanitarian.platform.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.humanitarian.platform.controller.AssignmentReportController;
import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.Organization;
import com.humanitarian.platform.model.Report;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.model.Volunteer;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.OrganizationRepository;
import com.humanitarian.platform.repository.ReportRepository;
import com.humanitarian.platform.repository.VolunteerRepository;
import com.humanitarian.platform.service.AssignmentReportService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * R-1 through the real security config and the real {@link AssignmentReportService}
 * with repositories mocked: who may record a delivery, who may rate it, who may
 * read it, and that everyone else sees 404 rather than learning the assignment
 * exists. Beneficiary is user 1, the assigned volunteer user 2 (profile 20), an
 * unrelated volunteer user 3 (profile 30), an organization user 4 (profile 40).
 */
@WebMvcTest(AssignmentReportController.class)
@Import(AssignmentReportService.class)
class AssignmentReportSecurityTest extends SecuritySliceTest {

    @MockBean private ReportRepository reportRepository;
    @MockBean private AssignmentRepository assignmentRepository;
    @MockBean private HelpRequestRepository helpRequestRepository;
    @MockBean private VolunteerRepository volunteerRepository;
    @MockBean private OrganizationRepository organizationRepository;

    private static final String REPORT_BODY = "{\"description\":\"Food parcels for three people\"}";
    private static final String FEEDBACK_BODY = "{\"rating\":4,\"feedback\":\"Kind and on time\"}";

    private Assignment assignment;

    @BeforeEach
    void fixtures() {
        HelpRequest request = HelpRequest.builder().id(10L).beneficiaryId(1L).title("Need food").status("COMPLETED").build();
        assignment = Assignment.builder().id(100L).requestId(10L).volunteerId(20L).requestType("HELP_REQUEST").status("COMPLETED").build();
        when(assignmentRepository.findById(100L)).thenReturn(Optional.of(assignment));
        when(helpRequestRepository.findById(10L)).thenReturn(Optional.of(request));
        Volunteer assigned = Volunteer.builder().id(20L).user(user(2L, UserRole.VOLUNTEER)).build();
        when(volunteerRepository.findByUserId(2L)).thenReturn(Optional.of(assigned));
        when(volunteerRepository.findById(20L)).thenReturn(Optional.of(assigned));
        when(volunteerRepository.findByUserId(3L)).thenReturn(Optional.of(Volunteer.builder().id(30L).user(user(3L, UserRole.VOLUNTEER)).build()));
        when(organizationRepository.findByUserId(4L)).thenReturn(Optional.of(Organization.builder().id(40L).user(user(4L, UserRole.ORGANIZATION)).build()));
        when(reportRepository.saveAndFlush(any(Report.class))).thenAnswer(inv -> { Report r = inv.getArgument(0); r.setId(500L); return r; });
        when(reportRepository.save(any(Report.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Report storedReport(Integer rating) {
        return Report.builder().id(500L).assignmentId(100L).volunteerId(20L).description("Food parcels for three people")
                .beneficiaryRating(rating).build();
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/assignments/100/report")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/assignments/100/report").contentType(MediaType.APPLICATION_JSON).content(REPORT_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void beneficiaryCannotFileTheVolunteersReport() throws Exception {
        actingAs(1L, UserRole.BENEFICIARY);
        mockMvc.perform(post("/api/assignments/100/report").contentType(MediaType.APPLICATION_JSON).content(REPORT_BODY))
                .andExpect(status().isForbidden());
        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void unrelatedVolunteerSeesNoAssignment() throws Exception {
        actingAs(3L, UserRole.VOLUNTEER);
        mockMvc.perform(post("/api/assignments/100/report").contentType(MediaType.APPLICATION_JSON).content(REPORT_BODY))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/assignments/100/report")).andExpect(status().isNotFound());
        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void assignedVolunteerRecordsOnceTheAssignmentIsCompletedAndTheBeneficiaryIsTold() throws Exception {
        actingAs(2L, UserRole.VOLUNTEER);
        when(reportRepository.existsByAssignmentId(100L)).thenReturn(false);

        mockMvc.perform(post("/api/assignments/100/report").contentType(MediaType.APPLICATION_JSON).content(REPORT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.assignmentId").value(100))
                .andExpect(jsonPath("$.data.requestId").value(10))
                .andExpect(jsonPath("$.data.volunteerId").value(20))
                .andExpect(jsonPath("$.data.description").value("Food parcels for three people"))
                .andExpect(jsonPath("$.data.beneficiaryRating").doesNotExist());
        verify(notifications).notify(eq(1L), eq("Delivery recorded: please rate this help"), anyString(), eq("HELP_REQUEST"), eq(10L));
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void reportBeforeCompletionIsABadRequest() throws Exception {
        actingAs(2L, UserRole.VOLUNTEER);
        assignment.setStatus("ASSIGNED");
        mockMvc.perform(post("/api/assignments/100/report").contentType(MediaType.APPLICATION_JSON).content(REPORT_BODY))
                .andExpect(status().isBadRequest());
        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void secondReportIsAConflict() throws Exception {
        actingAs(2L, UserRole.VOLUNTEER);
        when(reportRepository.existsByAssignmentId(100L)).thenReturn(true);
        mockMvc.perform(post("/api/assignments/100/report").contentType(MediaType.APPLICATION_JSON).content(REPORT_BODY))
                .andExpect(status().isConflict());
        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void emptyDescriptionIsRejectedBeforeTheService() throws Exception {
        actingAs(2L, UserRole.VOLUNTEER);
        mockMvc.perform(post("/api/assignments/100/report").contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        verify(assignmentRepository, never()).findById(anyLong());
    }

    @Test
    @WithMockUser(roles = "ORGANIZATION")
    void organizationAssignmentHasNoReportRowToWrite() throws Exception {
        actingAs(4L, UserRole.ORGANIZATION);
        assignment.setVolunteerId(null);
        assignment.setOrganizationId(40L);
        mockMvc.perform(post("/api/assignments/100/report").contentType(MediaType.APPLICATION_JSON).content(REPORT_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("volunteer assignments")));
    }

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void beneficiaryRatesOnceAndTheVolunteerIsTold() throws Exception {
        actingAs(1L, UserRole.BENEFICIARY);
        when(reportRepository.findByAssignmentId(100L)).thenReturn(Optional.of(storedReport(null)));

        mockMvc.perform(post("/api/assignments/100/feedback").contentType(MediaType.APPLICATION_JSON).content(FEEDBACK_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.beneficiaryRating").value(4))
                .andExpect(jsonPath("$.data.feedbackFromBeneficiary").value("Kind and on time"))
                .andExpect(jsonPath("$.data.volunteerName").value("VOLUNTEER 2"));
        verify(notifications).notify(eq(2L), eq("You received a rating: 4/5"), anyString(), eq("HELP_REQUEST"), eq(10L));

        when(reportRepository.findByAssignmentId(100L)).thenReturn(Optional.of(storedReport(4)));
        mockMvc.perform(post("/api/assignments/100/feedback").contentType(MediaType.APPLICATION_JSON).content(FEEDBACK_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void ratingBeforeTheReportExistsIsABadRequestAndOutOfRangeIsRejected() throws Exception {
        actingAs(1L, UserRole.BENEFICIARY);
        when(reportRepository.findByAssignmentId(100L)).thenReturn(Optional.empty());
        mockMvc.perform(post("/api/assignments/100/feedback").contentType(MediaType.APPLICATION_JSON).content(FEEDBACK_BODY))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/assignments/100/feedback").contentType(MediaType.APPLICATION_JSON).content("{\"rating\":6}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.rating").exists());
    }

    @Test
    @WithMockUser(roles = "VOLUNTEER")
    void volunteerCannotRateTheirOwnDelivery() throws Exception {
        actingAs(2L, UserRole.VOLUNTEER);
        mockMvc.perform(post("/api/assignments/100/feedback").contentType(MediaType.APPLICATION_JSON).content(FEEDBACK_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminAndBothPartiesReadTheReportOthersDoNot() throws Exception {
        when(reportRepository.findByAssignmentId(100L)).thenReturn(Optional.of(storedReport(5)));
        actingAs(99L, UserRole.ADMIN);
        mockMvc.perform(get("/api/assignments/100/report")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.beneficiaryRating").value(5));
        actingAs(1L, UserRole.BENEFICIARY);
        mockMvc.perform(get("/api/assignments/100/report")).andExpect(status().isOk());
        actingAs(2L, UserRole.VOLUNTEER);
        mockMvc.perform(get("/api/assignments/100/report")).andExpect(status().isOk());
        actingAs(3L, UserRole.VOLUNTEER);
        mockMvc.perform(get("/api/assignments/100/report")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void noReportYetIsNotFoundForAParty() throws Exception {
        actingAs(99L, UserRole.ADMIN);
        when(reportRepository.findByAssignmentId(100L)).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/assignments/100/report")).andExpect(status().isNotFound());
    }
}
