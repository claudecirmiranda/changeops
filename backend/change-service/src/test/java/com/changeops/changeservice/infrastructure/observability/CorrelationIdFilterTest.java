package com.changeops.changeservice.infrastructure.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void shouldUseProvidedCorrelationId_whenHeaderPresent() throws ServletException, IOException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        String expectedId = "test-correlation-123";
        when(request.getHeader("X-Correlation-Id")).thenReturn(expectedId);

        doAnswer(invocation -> {
            assertThat(MDC.get("correlation_id")).isEqualTo(expectedId);
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        verify(response).setHeader("X-Correlation-Id", expectedId);
        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldGenerateCorrelationId_whenHeaderNotPresent() throws ServletException, IOException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(request.getHeader("X-Correlation-Id")).thenReturn(null);

        doAnswer(invocation -> {
            String generated = MDC.get("correlation_id");
            assertThat(generated).isNotNull().isNotBlank();
            // Should be a valid UUID format
            assertThat(generated).matches(
                    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldClearMdc_afterRequestCompletes() throws ServletException, IOException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(request.getHeader("X-Correlation-Id")).thenReturn("some-id");

        filter.doFilter(request, response, chain);

        assertThat(MDC.get("correlation_id")).isNull();
        assertThat(MDC.get("service")).isNull();
    }

    @Test
    void shouldClearMdc_evenWhenChainThrows() throws ServletException, IOException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(request.getHeader("X-Correlation-Id")).thenReturn("some-id");
        doThrow(new ServletException("test error")).when(chain).doFilter(request, response);

        try {
            filter.doFilter(request, response, chain);
        } catch (ServletException ignored) {
        }

        assertThat(MDC.get("correlation_id")).isNull();
        assertThat(MDC.get("service")).isNull();
    }
}
