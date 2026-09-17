package com.humanitarian.platform.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.humanitarian.platform.controller.ConsultationController;
import com.humanitarian.platform.model.Assignment;
import com.humanitarian.platform.model.Consultation;
import com.humanitarian.platform.model.PsychologicalRequest;
import com.humanitarian.platform.model.Psychologist;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.AssignmentRepository;
import com.humanitarian.platform.repository.ConsultationRepository;
import com.humanitarian.platform.repository.PsychologicalRequestRepository;
import com.humanitarian.platform.repository.PsychologistRepository;
import com.humanitarian.platform.service.ConsultationService;
import com.humanitarian.platform.service.ProviderStatsService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * CS-1 through the real security config and the real {@link ConsultationService}
 * with repositories mocked: who may record a session, on which case states, who
 * may rate one, who may list them, that everyone else sees 404 rather than
 * learning the case exists, that the psychologist's private note reaches nobody
 * but the assigned psychologist, and that an anonymous beneficiary's identity
 * appears in no response and no notification. The beneficiary is user 1
 * (anonymous request), the assigned psychologist user 2 (profile 20), another
 * psychologist user 3 (profile 30), another beneficiary user 4, an
 * administrator user 99. The case is ASSIGNED unless a test says otherwise.
 */
@WebMvcTest(ConsultationController.class)
@Import(ConsultationService.class)
class ConsultationSecurityTest extends SecuritySliceTest {

    @MockBean private ConsultationRepository consultationRepository;
    @MockBean private PsychologicalRequestRepository requestRepository;
    @MockBean private PsychologistRepository psychologistRepository;
    @MockBean private AssignmentRepository assignmentRepository;
    @MockBean private ProviderStatsService providerStats;

    private static final String BENEFICIARY_NAME = "BENEFICIARY 1";   // what user(1L, BENEFICIARY) is called
    private static final String SESSIONS = "/api/psychological-requests/10/consultations";
    private static final String FEEDBACK = SESSIONS + "/500/feedback";
    private static final String RECORD_BODY = "{\"format\":\"video\",\"durationMinutes\":45,"
            + "\"topicsDiscussed\":[\"sleep\",\"grief\"],\"recommendations\":\"Keep a sleep diary\","
            + "\"notesForPsychologist\":\"PRIVATE: consider referral\"}";
    private static final String FEEDBACK_BODY = "{\"rating\":4,\"feedback\":\"Felt heard\"}";

    private PsychologicalRequest request;

    @BeforeEach
    void fixtures() {
        request = PsychologicalRequest.builder().id(10L).beneficiaryId(1L).assignedPsychologistId(20L)
                .category("GRIEF").supportType("INDIVIDUAL").preferredFormat("VIDEO").status("ASSIGNED")
                .isAnonymous(true).isCrisis(false).build();
        when(requestRepository.findById(10L)).thenReturn(Optional.of(request));
        Psychologist assigned = Psychologist.builder().id(20L).user(user(2L, UserRole.PSYCHOLOGIST)).isVerified(true).build();
        when(psychologistRepository.findByUserId(2L)).thenReturn(Optional.of(assigned));
        when(psychologistRepository.findById(20L)).thenReturn(Optional.of(assigned));
        when(psychologistRepository.findByUserId(3L)).thenReturn(Optional.of(
                Psychologist.builder().id(30L).user(user(3L, UserRole.PSYCHOLOGIST)).build()));
        when(assignmentRepository.findFirstByPsychologicalRequestIdOrderByAssignedAtDesc(10L))
                .thenReturn(Optional.of(Assignment.builder().id(100L).psychologicalRequestId(10L).psychologistId(20L)
                        .requestType("PSYCHOLOGICAL_REQUEST").status("ASSIGNED").build()));
        when(consultationRepository.saveAndFlush(any(Consultation.class))).thenAnswer(inv -> {
            Consultation c = inv.getArgument(0); c.setId(500L); return c; });
        when(consultationRepository.save(any(Consultation.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Consultation stored(Long id, Integer rating) {
        return Consultation.builder().id(id).psychologicalRequestId(10L).assignmentId(100L).psychologistId(20L)
                .beneficiaryId(1L).format("VIDEO").startedAt(LocalDateTime.now().minusHours(1)).durationMinutes(45)
                .topicsDiscussed(List.of("sleep", "grief")).recommendations("Keep a sleep diary")
                .notesForPsychologist("PRIVATE: consider referral").rating(rating).build();
    }

    private void sessionsAre(Consultation... sessions) {
        when(consultationRepository.findByPsychologicalRequestIdOrderByStartedAtAscIdAsc(10L)).thenReturn(List.of(sessions));
        for (Consultation c : sessions) {
            when(consultationRepository.findByIdAndPsychologicalRequestId(c.getId(), 10L)).thenReturn(Optional.of(c));
        }
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get(SESSIONS)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(SESSIONS).contentType(MediaType.APPLICATION_JSON).content(RECORD_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void beneficiaryCannotRecordThePsychologistsSession() throws Exception {
        actingAs(1L, UserRole.BENEFICIARY);
        mockMvc.perform(post(SESSIONS).contentType(MediaType.APPLICATION_JSON).content(RECORD_BODY))
                .andExpect(status().isForbidden());
        verify(consultationRepository, never()).saveAndFlush(any());
    }

    @Test
    @WithMockUser(roles = "PSYCHOLOGIST")
    void unrelatedPsychologistSeesNoCase() throws Exception {
        actingAs(3L, UserRole.PSYCHOLOGIST);
        sessionsAre(stored(500L, null));
        mockMvc.perform(post(SESSIONS).contentType(MediaType.APPLICATION_JSON).content(RECORD_BODY))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(SESSIONS)).andExpect(status().isNotFound());
        verify(consultationRepository, never()).saveAndFlush(any());
    }

    @Test
    @WithMockUser(roles = "PSYCHOLOGIST")
    void assignedPsychologistRecordsASessionOnTheOpenCaseAndSeesTheirOwnNote() throws Exception {
        actingAs(2L, UserRole.PSYCHOLOGIST);

        mockMvc.perform(post(SESSIONS).contentType(MediaType.APPLICATION_JSON).content(RECORD_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.consultationId").value(500))
                .andExpect(jsonPath("$.data.requestId").value(10))
                .andExpect(jsonPath("$.data.assignmentId").value(100))
                .andExpect(jsonPath("$.data.psychologistId").value(20))
                .andExpect(jsonPath("$.data.format").value("VIDEO"))
                .andExpect(jsonPath("$.data.durationMinutes").value(45))
                .andExpect(jsonPath("$.data.endedAt").exists())
                .andExpect(jsonPath("$.data.topicsDiscussed[1]").value("grief"))
                .andExpect(jsonPath("$.data.recommendations").value("Keep a sleep diary"))
                .andExpect(jsonPath("$.data.notesForPsychologist").value("PRIVATE: consider referral"))
                .andExpect(jsonPath("$.data.rating").doesNotExist())
                .andExpect(jsonPath("$.data.beneficiaryId").doesNotExist())
                .andExpect(jsonPath("$.data.beneficiaryName").doesNotExist())
                .andExpect(content().string(Matchers.not(Matchers.containsString(BENEFICIARY_NAME))));
        verify(notifications).notify(eq(1L), eq("A session was recorded: please rate it"), anyString(), eq("PSYCHOLOGICAL_REQUEST"), eq(10L));
        verify(providerStats).refreshPsychologist(20L);   // AGG-1: the count is recomputed in the same transaction
    }

    /** Owner decision after Gate 5: a case has as many sessions as it needs; the case's state does not change. */
    @Test
    @WithMockUser(roles = "PSYCHOLOGIST")
    void aSecondSessionIsAnotherRecordAndACompletedCaseStillTakesOne() throws Exception {
        actingAs(2L, UserRole.PSYCHOLOGIST);
        sessionsAre(stored(500L, 5));
        mockMvc.perform(post(SESSIONS).contentType(MediaType.APPLICATION_JSON).content(RECORD_BODY))
                .andExpect(status().isCreated());

        request.setStatus("COMPLETED");
        mockMvc.perform(post(SESSIONS).contentType(MediaType.APPLICATION_JSON).content(RECORD_BODY))
                .andExpect(status().isCreated());
        verify(consultationRepository, times(2)).saveAndFlush(any());
        assertEquals("COMPLETED", request.getStatus(), "recording a session never moves the case");
    }

    @Test
    @WithMockUser(roles = "PSYCHOLOGIST")
    void aCancelledCaseTakesNoSession() throws Exception {
        actingAs(2L, UserRole.PSYCHOLOGIST);
        request.setStatus("CANCELLED");
        mockMvc.perform(post(SESSIONS).contentType(MediaType.APPLICATION_JSON).content(RECORD_BODY))
                .andExpect(status().isBadRequest());
        verify(consultationRepository, never()).saveAndFlush(any());
    }

    @Test
    @WithMockUser(roles = "PSYCHOLOGIST")
    void unknownFormatIsRejectedBeforeTheService() throws Exception {
        actingAs(2L, UserRole.PSYCHOLOGIST);
        mockMvc.perform(post(SESSIONS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"format\":\"PHONE\",\"durationMinutes\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.details.format").exists())
                .andExpect(jsonPath("$.details.durationMinutes").exists());
        verify(requestRepository, never()).findById(anyLong());
    }

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void anotherBeneficiarySeesNoCase() throws Exception {
        actingAs(4L, UserRole.BENEFICIARY);
        sessionsAre(stored(500L, null));
        mockMvc.perform(get(SESSIONS)).andExpect(status().isNotFound());
        mockMvc.perform(post(FEEDBACK).contentType(MediaType.APPLICATION_JSON).content(FEEDBACK_BODY))
                .andExpect(status().isNotFound());
        verify(consultationRepository, never()).save(any());
    }

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void beneficiaryRatesEachSessionOnceAndThePsychologistIsToldWithoutLearningWho() throws Exception {
        actingAs(1L, UserRole.BENEFICIARY);
        sessionsAre(stored(500L, null), stored(501L, null));

        mockMvc.perform(post(FEEDBACK).contentType(MediaType.APPLICATION_JSON).content(FEEDBACK_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consultationId").value(500))
                .andExpect(jsonPath("$.data.rating").value(4))
                .andExpect(jsonPath("$.data.feedbackFromBeneficiary").value("Felt heard"))
                .andExpect(jsonPath("$.data.psychologistName").value("PSYCHOLOGIST 2"))
                .andExpect(jsonPath("$.data.notesForPsychologist").doesNotExist())
                .andExpect(jsonPath("$.data.beneficiaryId").doesNotExist())
                .andExpect(content().string(Matchers.not(Matchers.containsString(BENEFICIARY_NAME))));
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(notifications).notify(eq(2L), eq("You received a rating: 4/5"), text.capture(), eq("PSYCHOLOGICAL_REQUEST"), eq(10L));
        assertFalse(text.getValue().contains(BENEFICIARY_NAME), "the anonymous beneficiary is not named: " + text.getValue());
        assertFalse(text.getValue().contains("beneficiary1@"), "nor is their e-mail: " + text.getValue());
        verify(providerStats).refreshPsychologist(20L);   // AGG-1: the mean is recomputed in the same transaction

        // the same session again: refused; the other session: its own rating
        sessionsAre(stored(500L, 4), stored(501L, null));
        mockMvc.perform(post(FEEDBACK).contentType(MediaType.APPLICATION_JSON).content(FEEDBACK_BODY))
                .andExpect(status().isConflict());
        verify(providerStats).refreshPsychologist(20L);   // still once: a refused rating recomputes nothing
        mockMvc.perform(post(SESSIONS + "/501/feedback").contentType(MediaType.APPLICATION_JSON).content("{\"rating\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consultationId").value(501))
                .andExpect(jsonPath("$.data.rating").value(2));
    }

    @Test
    @WithMockUser(roles = "BENEFICIARY")
    void ratingASessionOfAnotherCaseIsNotFoundAndOutOfRangeIsRejected() throws Exception {
        actingAs(1L, UserRole.BENEFICIARY);
        // session 500 exists under case 10 only as far as the repository is asked; 777 is not this case's
        when(consultationRepository.findByIdAndPsychologicalRequestId(777L, 10L)).thenReturn(Optional.empty());
        mockMvc.perform(post(SESSIONS + "/777/feedback").contentType(MediaType.APPLICATION_JSON).content(FEEDBACK_BODY))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(FEEDBACK).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.rating").exists());
        verify(consultationRepository, never()).save(any());
    }

    @Test
    @WithMockUser(roles = "PSYCHOLOGIST")
    void psychologistCannotRateTheirOwnSession() throws Exception {
        actingAs(2L, UserRole.PSYCHOLOGIST);
        mockMvc.perform(post(FEEDBACK).contentType(MediaType.APPLICATION_JSON).content(FEEDBACK_BODY))
                .andExpect(status().isForbidden());
    }

    /** Gate 5, CS-1: the private note is returned to the assigned psychologist and to nobody else, the administrator included. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void thePrivateNoteReachesOnlyTheAssignedPsychologist() throws Exception {
        sessionsAre(stored(500L, 5), stored(501L, null));

        actingAs(1L, UserRole.BENEFICIARY);
        mockMvc.perform(get(SESSIONS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].consultationId").value(500))
                .andExpect(jsonPath("$.data[0].recommendations").value("Keep a sleep diary"))
                .andExpect(jsonPath("$.data[0].rating").value(5))
                .andExpect(jsonPath("$.data[1].rating").doesNotExist())
                .andExpect(jsonPath("$.data[0].notesForPsychologist").doesNotExist())
                .andExpect(jsonPath("$.data[1].notesForPsychologist").doesNotExist())
                .andExpect(content().string(Matchers.not(Matchers.containsString("PRIVATE"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString(BENEFICIARY_NAME))));

        actingAs(99L, UserRole.ADMIN);
        mockMvc.perform(get(SESSIONS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].recommendations").value("Keep a sleep diary"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("PRIVATE"))));

        actingAs(2L, UserRole.PSYCHOLOGIST);
        mockMvc.perform(get(SESSIONS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].notesForPsychologist").value("PRIVATE: consider referral"))
                .andExpect(jsonPath("$.data[1].notesForPsychologist").value("PRIVATE: consider referral"));

        actingAs(3L, UserRole.PSYCHOLOGIST);
        mockMvc.perform(get(SESSIONS)).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void noSessionsYetIsAnEmptyListForAParty() throws Exception {
        actingAs(99L, UserRole.ADMIN);
        sessionsAre();
        mockMvc.perform(get(SESSIONS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
