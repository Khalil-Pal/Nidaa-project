package com.humanitarian.platform.controller;

import com.humanitarian.platform.dto.ContactInfoResponse;
import com.humanitarian.platform.exception.GlobalExceptionHandler;
import com.humanitarian.platform.exception.UnauthorizedException;
import com.humanitarian.platform.service.ContactInfoService;
import com.humanitarian.platform.service.PsychologicalRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ContactControllerTest {

    @Test
    void materialContactReturnsForbiddenForUnrelatedUser() throws Exception {
        ContactInfoService contactInfoService = mock(ContactInfoService.class);
        HelpRequestController controller = new HelpRequestController();
        ReflectionTestUtils.setField(controller, "contactInfoService", contactInfoService);
        when(contactInfoService.getHelpRequestContact(10L)).thenThrow(
                new UnauthorizedException(
                        "Only the requester or assigned provider can view this contact information."));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/api/help-requests/10/contact"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void psychologicalContactReturnsAnonymousPayloadWithoutIdentityFields() throws Exception {
        ContactInfoService contactInfoService = mock(ContactInfoService.class);
        PsychologicalRequestController controller = new PsychologicalRequestController(
                mock(PsychologicalRequestService.class), contactInfoService);
        ContactInfoResponse anonymous = ContactInfoResponse.builder()
                .name("Anonymous beneficiary")
                .contactRole("BENEFICIARY")
                .anonymous(true)
                .message("Identity protected")
                .build();
        when(contactInfoService.getPsychologicalRequestContact(21L)).thenReturn(anonymous);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/api/psychological-requests/21/contact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.anonymous").value(true))
                .andExpect(jsonPath("$.data.name").value("Anonymous beneficiary"))
                .andExpect(jsonPath("$.data.email").isEmpty())
                .andExpect(jsonPath("$.data.phone").isEmpty());
    }
}
