package com.humanitarian.platform.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C-4: every request carries a correlation id in the MDC and in the response. */
class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    private String run(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        AtomicReference<String> seenInsideChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> seenInsideChain.set(MDC.get(RequestCorrelationFilter.MDC_REQUEST_ID));
        filter.doFilter(request, response, chain);
        return seenInsideChain.get();
    }

    @Test
    void generatesAnIdWhenTheCallerSendsNone() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String inside = run(new MockHttpServletRequest("GET", "/api/x"), response);

        assertTrue(inside.matches("[0-9a-f]{16}"), inside);
        assertEquals(inside, response.getHeader(RequestCorrelationFilter.HEADER));
        assertNull(MDC.get(RequestCorrelationFilter.MDC_REQUEST_ID), "cleared after the request: threads are reused");
    }

    @Test
    void keepsASafeCallerSuppliedId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        request.addHeader(RequestCorrelationFilter.HEADER, "client-42.a_b");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertEquals("client-42.a_b", run(request, response));
        assertEquals("client-42.a_b", response.getHeader(RequestCorrelationFilter.HEADER));
    }

    @Test
    void replacesAnIdThatCouldForgeOrBreakLogLines() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        request.addHeader(RequestCorrelationFilter.HEADER, "abc] ERROR fake line\n[x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String inside = run(request, response);
        assertNotEquals("abc] ERROR fake line\n[x", inside);
        assertTrue(inside.matches("[0-9a-f]{16}"));
    }
}
