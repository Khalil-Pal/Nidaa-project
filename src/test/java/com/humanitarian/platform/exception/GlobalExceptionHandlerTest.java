package com.humanitarian.platform.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** S-10: error responses must carry a status chosen by exception type and never leak internals. */
class GlobalExceptionHandlerTest {

    private static final String CONSTRAINT = "messages_sender_id_fkey";

    @RestController
    static class ThrowingController {
        @GetMapping("/t/integrity")
        String integrity() {
            throw new DataIntegrityViolationException(
                    "could not execute statement; constraint [" + CONSTRAINT + "]",
                    new RuntimeException("ERROR: update or delete on table \"users\" violates foreign key constraint \""
                            + CONSTRAINT + "\" on table \"messages\""));
        }

        @GetMapping("/t/boom")
        String boom() {
            throw new IllegalStateException("column help_requests.secret_column does not exist");
        }

        @GetMapping("/t/not-found-worded")
        String notFoundWorded() {
            throw new RuntimeException("something was not found in table users");
        }

        @GetMapping("/t/typed/{id}")
        String typed(@PathVariable Long id) {
            return "ok";
        }

        @GetMapping("/t/param")
        String param(@RequestParam String status) {
            return status;
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void dataIntegrityViolationIs409WithoutConstraintName() throws Exception {
        mockMvc.perform(get("/t/integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(matchesPattern(".*Reference: [0-9a-f]{8}$")))
                .andExpect(content().string(not(containsString(CONSTRAINT))))
                .andExpect(content().string(not(containsString("users"))));
    }

    @Test
    void unexpectedExceptionIs500WithReferenceOnly() throws Exception {
        mockMvc.perform(get("/t/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(matchesPattern("^An unexpected error occurred[.] Reference: [0-9a-f]{8}$")))
                .andExpect(content().string(not(containsString("secret_column"))));
    }

    @Test
    void statusIsNoLongerChosenByMessageText() throws Exception {
        // Before S-10 a message containing "not found" became a 404.
        mockMvc.perform(get("/t/not-found-worded"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("table users"))));
    }

    @Test
    void wrongParameterTypeIs400() throws Exception {
        mockMvc.perform(get("/t/typed/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("'id'")));
    }

    @Test
    void missingParameterIs400() throws Exception {
        mockMvc.perform(get("/t/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("'status'")));
    }

    @Test
    void unsupportedMethodIs405() throws Exception {
        mockMvc.perform(post("/t/param"))
                .andExpect(status().isMethodNotAllowed());
    }
}
