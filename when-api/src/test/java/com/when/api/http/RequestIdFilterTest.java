package com.when.api.http;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {
    @Test
    void preservesValidIdAndRegeneratesInvalidId() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest valid = new MockHttpServletRequest("GET", "/api/v1/messages/id");
        valid.addHeader(RequestIds.HEADER, "req.valid-1");
        MockHttpServletResponse validResponse = new MockHttpServletResponse();
        filter.doFilter(valid, validResponse, new MockFilterChain());
        assertEquals("req.valid-1", validResponse.getHeader(RequestIds.HEADER));

        MockHttpServletRequest invalid = new MockHttpServletRequest("GET", "/admin/v1/messages");
        invalid.addHeader(RequestIds.HEADER, "bad id");
        MockHttpServletResponse invalidResponse = new MockHttpServletResponse();
        filter.doFilter(invalid, invalidResponse, new MockFilterChain());
        assertTrue(invalidResponse.getHeader(RequestIds.HEADER).matches("req_[A-Za-z0-9]{32}"));
    }
}
