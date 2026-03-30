package com.changeops.changeservice.infrastructure.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/**
 * Rate limiter for POST /api/v1/changes.
 * Allows 100 requests per minute per IP address.
 * Buckets are evicted after 10 minutes of inactivity and capped at 10 000 entries
 * to prevent unbounded memory growth.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String CHANGES_PATH = "/api/v1/changes";
    private static final int CAPACITY = 100;
    private static final Duration REFILL_DURATION = Duration.ofMinutes(1);

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(10_000)
            .build();

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (HttpMethod.POST.matches(Objects.requireNonNull(request.getMethod()))
                && CHANGES_PATH.equals(request.getRequestURI())) {

            String clientIp = resolveClientIp(request);
            if (clientIp == null || clientIp.isBlank()) {
                clientIp = "unknown";
            }
            Bucket bucket = buckets.get(clientIp, k -> newBucket());

            if (!bucket.tryConsume(1)) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setHeader("Retry-After", "60");
                response.getWriter().write(
                        "{\"title\":\"Too Many Requests\"," +
                        "\"detail\":\"Rate limit exceeded. Maximum 100 requests per minute.\"," +
                        "\"status\":429}");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private static Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(CAPACITY)
                .refillGreedy(CAPACITY, REFILL_DURATION)
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
