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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration-level tests for RateLimitFilter focusing on boundary conditions,
 * per-IP bucket isolation, and response header correctness.
 */
class RateLimitFilterIntegrationTest {

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
    }

    // ─── Boundary: exact limit ────────────────────────────────────────────────

    @Test
    void shouldAllow_exactly100Requests_beforeEnforcingLimit() throws Exception {
        String ip = "172.16.0.1";
        AtomicInteger allowed = new AtomicInteger(0);

        for (int i = 0; i < 100; i++) {
            FilterChain chain = mock(FilterChain.class);
            doAnswer(inv -> {
                allowed.incrementAndGet();
                return null;
            }).when(chain).doFilter(any(), any());

            invokeFilter(ip, chain);
        }

        assertThat(allowed.get()).isEqualTo(100);
    }

    @Test
    void shouldBlock_101stRequest_fromSameIp() throws Exception {
        String ip = "172.16.0.2";

        // Exhaust bucket
        for (int i = 0; i < 100; i++) {
            FilterChain chain = mock(FilterChain.class);
            invokeFilter(ip, chain);
        }

        // 101st must be rejected
        FilterChain chain = mock(FilterChain.class);
        HttpServletRequest req = buildPostChangesRequest(ip);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(resp.getWriter()).thenReturn(new PrintWriter(body));

        filter.doFilterInternal(req, resp, chain);

        verify(resp).setStatus(429);
        verify(resp).setHeader("Retry-After", "60");
        assertThat(body.toString()).contains("429");
        verify(chain, never()).doFilter(any(), any());
    }

    // ─── Per-IP bucket isolation ──────────────────────────────────────────────

    @Test
    void shouldNotShareBucket_acrossDifferentIPs() throws Exception {
        // Exhaust IpA
        String ipA = "10.0.10.1";
        for (int i = 0; i < 100; i++) {
            invokeFilter(ipA, mock(FilterChain.class));
        }

        // IpB should still be allowed
        String ipB = "10.0.10.2";
        FilterChain chainB = mock(FilterChain.class);
        invokeFilter(ipB, chainB);
        verify(chainB).doFilter(any(), any());

        // IpA must be rejected
        HttpServletResponse respA = mock(HttpServletResponse.class);
        HttpServletRequest reqA = buildPostChangesRequest(ipA);
        when(respA.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        filter.doFilterInternal(reqA, respA, mock(FilterChain.class));
        verify(respA).setStatus(429);
    }

    @Test
    void shouldUseXForwardedFor_forBucketKey_whenHeaderPresent() throws Exception {
        // IP-via-proxy exhausts its bucket
        String spoofedIp = "203.0.113.42";
        for (int i = 0; i < 100; i++) {
            HttpServletRequest req = mock(HttpServletRequest.class);
            when(req.getMethod()).thenReturn("POST");
            when(req.getRequestURI()).thenReturn("/api/v1/changes");
            when(req.getHeader("X-Forwarded-For")).thenReturn(spoofedIp);
            HttpServletResponse resp = mock(HttpServletResponse.class);
            filter.doFilterInternal(req, resp, mock(FilterChain.class));
        }

        // 101st with same X-Forwarded-For must be blocked
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("POST");
        when(req.getRequestURI()).thenReturn("/api/v1/changes");
        when(req.getHeader("X-Forwarded-For")).thenReturn(spoofedIp);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilterInternal(req, resp, mock(FilterChain.class));

        verify(resp).setStatus(429);
    }

    // ─── Retry-After header ───────────────────────────────────────────────────

    @Test
    void shouldIncludeRetryAfterHeader_whenRateLimited() throws Exception {
        String ip = "192.0.2.1";
        for (int i = 0; i < 100; i++) {
            invokeFilter(ip, mock(FilterChain.class));
        }

        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        filter.doFilterInternal(buildPostChangesRequest(ip), resp, mock(FilterChain.class));

        verify(resp).setHeader("Retry-After", "60");
        verify(resp).setContentType("application/json");
    }

    // ─── Non-POST requests are never rate-limited ─────────────────────────────

    @Test
    void shouldNeverRateLimit_getRequests() throws Exception {
        String ip = "198.51.100.1";
        // Simulate a GET request well beyond the POST limit
        for (int i = 0; i < 200; i++) {
            HttpServletRequest req = mock(HttpServletRequest.class);
            when(req.getMethod()).thenReturn("GET");
            when(req.getRequestURI()).thenReturn("/api/v1/changes");
            HttpServletResponse resp = mock(HttpServletResponse.class);
            FilterChain chain = mock(FilterChain.class);

            filter.doFilterInternal(req, resp, chain);

            verify(resp, never()).setStatus(429);
        }
    }

    @Test
    void shouldNeverRateLimit_actuatorEndpoints() throws Exception {
        String ip = "198.51.100.2";
        for (int i = 0; i < 200; i++) {
            HttpServletRequest req = mock(HttpServletRequest.class);
            when(req.getMethod()).thenReturn("GET");
            when(req.getRequestURI()).thenReturn("/actuator/health");
            HttpServletResponse resp = mock(HttpServletResponse.class);
            FilterChain chain = mock(FilterChain.class);

            filter.doFilterInternal(req, resp, chain);

            verify(resp, never()).setStatus(429);
        }
    }

    // ─── X-Forwarded-For spoofing ─────────────────────────────────────────────

    @Test
    void shouldAllowSpoofedIp_toBypassRateLimit_knownPocLimitation() throws Exception {
        // This test DOCUMENTS the known limitation: X-Forwarded-For is not validated
        // against trusted proxies, so a client can spoof it to bypass rate limiting.
        // Phase 2 must implement trusted proxy list validation.
        String realIp = "10.0.0.99";

        // Exhaust bucket for realIp
        for (int i = 0; i < 100; i++) {
            HttpServletRequest req = mock(HttpServletRequest.class);
            when(req.getMethod()).thenReturn("POST");
            when(req.getRequestURI()).thenReturn("/api/v1/changes");
            when(req.getHeader("X-Forwarded-For")).thenReturn(realIp);
            filter.doFilterInternal(req, mock(HttpServletResponse.class), mock(FilterChain.class));
        }

        // Same client spoofs a different IP — gets a fresh bucket
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("POST");
        when(req.getRequestURI()).thenReturn("/api/v1/changes");
        when(req.getHeader("X-Forwarded-For")).thenReturn("192.0.2.99"); // spoofed
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, resp, chain);

        // Request passes — limitation confirmed
        verify(resp, never()).setStatus(429);
        verify(chain).doFilter(any(), any());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void invokeFilter(String clientIp, FilterChain chain) throws ServletException, IOException {
        filter.doFilterInternal(buildPostChangesRequest(clientIp), mock(HttpServletResponse.class), chain);
    }

    private HttpServletRequest buildPostChangesRequest(String clientIp) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("POST");
        when(req.getRequestURI()).thenReturn("/api/v1/changes");
        when(req.getRemoteAddr()).thenReturn(clientIp);
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        return req;
    }
}
