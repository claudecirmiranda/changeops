package com.changeops.changeservice.infrastructure.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RateLimitFilterTest {

    private RateLimitFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        chain = mock(FilterChain.class);
    }

    @Test
    void shouldAllowRequest_whenUnderLimit() throws ServletException, IOException {
        HttpServletRequest request = postChangesRequest("192.168.1.1");
        HttpServletResponse response = mock(HttpServletResponse.class);

        invokeFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    void shouldReturn429_whenLimitExceeded() throws ServletException, IOException {
        String clientIp = "10.0.0.1";

        // Exhaust the 100-request bucket
        for (int i = 0; i < 100; i++) {
            HttpServletRequest req = postChangesRequest(clientIp);
            HttpServletResponse resp = mock(HttpServletResponse.class);
            invokeFilter(req, resp, chain);
        }

        // 101st request should be rejected
        HttpServletRequest request = postChangesRequest(clientIp);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        invokeFilter(request, response, chain);

        verify(response).setStatus(429);
        verify(response).setHeader("Retry-After", "60");
        assertThat(body.toString()).contains("Too Many Requests");
        // Chain must NOT be called when rate limited
        verify(chain, times(100)).doFilter(any(), any());
    }

    @Test
    void shouldNotApplyRateLimit_toGetRequests() throws ServletException, IOException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/v1/changes");

        invokeFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    void shouldNotApplyRateLimit_toOtherPaths() throws ServletException, IOException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/other");

        invokeFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    void shouldTrackLimitsPerIp() throws ServletException, IOException {
        // Exhaust limit for IP-A
        for (int i = 0; i < 100; i++) {
            HttpServletRequest req = postChangesRequest("10.0.0.1");
            HttpServletResponse resp = mock(HttpServletResponse.class);
            invokeFilter(req, resp, chain);
        }

        // IP-B should still be allowed
        HttpServletRequest request = postChangesRequest("10.0.0.2");
        HttpServletResponse response = mock(HttpServletResponse.class);

        invokeFilter(request, response, chain);

        verify(chain, times(101)).doFilter(any(), any());
        verify(response, never()).setStatus(429);
    }

    @Test
    void shouldUseXForwardedFor_whenPresent() throws ServletException, IOException {
        // Exhaust limit for the forwarded IP
        for (int i = 0; i < 100; i++) {
            HttpServletRequest req = mock(HttpServletRequest.class);
            when(req.getMethod()).thenReturn("POST");
            when(req.getRequestURI()).thenReturn("/api/v1/changes");
            when(req.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50, 10.0.0.1");
            when(req.getRemoteAddr()).thenReturn("10.0.0.1");
            HttpServletResponse resp = mock(HttpServletResponse.class);
            invokeFilter(req, resp, chain);
        }

        // Same forwarded IP should be blocked
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/changes");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50, 10.0.0.1");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        invokeFilter(request, response, chain);

        verify(response).setStatus(429);
    }

    private HttpServletRequest postChangesRequest(String remoteAddr) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/changes");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        return request;
    }

    private void invokeFilter(HttpServletRequest request,
                              HttpServletResponse response,
                              FilterChain filterChain) throws ServletException, IOException {
        filter.doFilterInternal(
                Objects.requireNonNull(request),
                Objects.requireNonNull(response),
                Objects.requireNonNull(filterChain));
    }
}
