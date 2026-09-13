package com.humanitarian.platform.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token-bucket rate limiting (S-9).
 *
 * <ul>
 *   <li>{@code /api/auth/**}: a small per-IP budget, because these endpoints
 *       are reachable without a token and drive login, registration and
 *       password-reset emails.</li>
 *   <li>every other {@code /api/**} path: a larger budget per authenticated
 *       user, falling back to the IP for anonymous calls.</li>
 * </ul>
 *
 * Runs after the security filter chain so the caller's identity is known.
 * Over-budget requests get 429 with a Retry-After header. Buckets live in
 * memory and idle ones are swept so the map cannot grow without bound.
 */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final long IDLE_EVICTION_MILLIS = 2 * 60_000L;
    private static final long SWEEP_INTERVAL_MILLIS = 60_000L;

    private final boolean enabled;
    private final int authPerMinute;
    private final int apiPerMinute;

    private final Map<String, Entry> buckets = new ConcurrentHashMap<>();
    private final AtomicLong lastSweep = new AtomicLong(System.currentTimeMillis());

    public RateLimitFilter(@Value("${app.ratelimit.enabled:true}") boolean enabled,
                           @Value("${app.ratelimit.auth-per-minute:5}") int authPerMinute,
                           @Value("${app.ratelimit.api-per-minute:100}") int apiPerMinute) {
        this.enabled = enabled;
        this.authPerMinute = authPerMinute;
        this.apiPerMinute = apiPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        sweepIfDue();

        String key;
        int limit;
        if (request.getRequestURI().startsWith("/api/auth/")) {
            key = "auth:" + clientIp(request);
            limit = authPerMinute;
        } else {
            key = "api:" + principalOrIp(request);
            limit = apiPerMinute;
        }

        Entry entry = buckets.computeIfAbsent(key, k -> new Entry(newBucket(limit)));
        entry.lastAccess = System.currentTimeMillis();
        ConsumptionProbe probe = entry.bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
        log.warn("Rate limit exceeded for {} on {} {}", key, request.getMethod(), request.getRequestURI());
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"Too many requests. Try again in "
                + retryAfterSeconds + " seconds.\"}");
    }

    private Bucket newBucket(int perMinute) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(perMinute).refillGreedy(perMinute, WINDOW).build())
                .build();
    }

    private static String principalOrIp(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return "user:" + auth.getName();
        }
        return "ip:" + clientIp(request);
    }

    /**
     * The socket address. Behind a reverse proxy, configure
     * server.forward-headers-strategy so the container resolves X-Forwarded-For
     * itself; the header is deliberately not read here because a client can
     * forge it.
     */
    private static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private void sweepIfDue() {
        long now = System.currentTimeMillis();
        long last = lastSweep.get();
        if (now - last < SWEEP_INTERVAL_MILLIS || !lastSweep.compareAndSet(last, now)) {
            return;
        }
        buckets.entrySet().removeIf(e -> now - e.getValue().lastAccess > IDLE_EVICTION_MILLIS);
    }

    /** Visible for tests. */
    int trackedKeys() {
        return buckets.size();
    }

    private static final class Entry {
        final Bucket bucket;
        volatile long lastAccess = System.currentTimeMillis();

        Entry(Bucket bucket) {
            this.bucket = bucket;
        }
    }
}
