package com.humanitarian.platform.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** S-9: per-IP budget on /api/auth/**, per-user budget elsewhere, 429 with Retry-After. */
class RateLimitFilterTest {

    @RestController
    static class StubController {
        @PostMapping("/api/auth/login") String login() { return "ok"; }
        @GetMapping("/api/help-requests/my") String my() { return "ok"; }
        @GetMapping("/index.html") String page() { return "ok"; }
    }

    private MockMvc mockMvcWith(RateLimitFilter filter) {
        return MockMvcBuilders.standaloneSetup(new StubController()).addFilters(filter).build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authEndpointsAllowFiveRequestsPerMinutePerIpThenReturn429() throws Exception {
        MockMvc mockMvc = mockMvcWith(new RateLimitFilter(true, 5, 100));

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login").with(r -> { r.setRemoteAddr("10.0.0.1"); return r; }))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/auth/login").with(r -> { r.setRemoteAddr("10.0.0.1"); return r; }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", matchesPattern("\\d+")));

        // another address has its own budget
        mockMvc.perform(post("/api/auth/login").with(r -> { r.setRemoteAddr("10.0.0.2"); return r; }))
                .andExpect(status().isOk());
    }

    @Test
    void apiEndpointsAreBudgetedPerAuthenticatedUser() throws Exception {
        MockMvc mockMvc = mockMvcWith(new RateLimitFilter(true, 5, 3));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "alice@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_BENEFICIARY"))));

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/help-requests/my")).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/help-requests/my")).andExpect(status().isTooManyRequests());

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "bob@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_BENEFICIARY"))));
        mockMvc.perform(get("/api/help-requests/my")).andExpect(status().isOk());
    }

    @Test
    void staticPagesAreNeverLimited() throws Exception {
        MockMvc mockMvc = mockMvcWith(new RateLimitFilter(true, 1, 1));
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/index.html")).andExpect(status().isOk());
        }
    }

    @Test
    void disabledFilterPassesEverything() throws Exception {
        MockMvc mockMvc = mockMvcWith(new RateLimitFilter(false, 1, 1));
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/auth/login")).andExpect(status().isOk());
        }
    }
}
