package com.humanitarian.platform.controller;

import com.humanitarian.platform.exception.GlobalExceptionHandler;
import com.humanitarian.platform.exception.UnauthorizedException;
import com.humanitarian.platform.service.ProviderAvailabilityService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProviderAvailabilityControllerTest {

    @Test
    void missingAvailabilityReturnsBadRequest() throws Exception {
        ProviderAvailabilityService service = mock(ProviderAvailabilityService.class);

        mockMvc(service).perform(put("/api/provider-availability/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.available")
                        .value("Availability is required"));
    }

    @Test
    void wrongRoleReturnsForbidden() throws Exception {
        ProviderAvailabilityService service = mock(ProviderAvailabilityService.class);
        when(service.setMyAvailability(any())).thenThrow(new UnauthorizedException(
                "Only volunteers and organizations can manage provider availability."));

        mockMvc(service).perform(put("/api/provider-availability/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"available\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    private MockMvc mockMvc(ProviderAvailabilityService service) {
        return MockMvcBuilders.standaloneSetup(new ProviderAvailabilityController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
