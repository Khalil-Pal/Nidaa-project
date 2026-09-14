package com.humanitarian.platform.security;

import com.humanitarian.platform.config.RequestCorrelationFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserDetailsServiceImpl userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            // Get JWT token from request header
            String jwt = parseJwt(request);

            // Validate token and set authentication
            if (jwt != null && jwtUtils.validateToken(jwt)) {
                String email = jwtUtils.getEmailFromToken(jwt);
                UserDetails userDetails = userDetailsService.loadUserByUsername(email);

                if (isRevoked(jwt, userDetails)) {
                    // Leave the request anonymous; the entry point answers 401 and
                    // the client's refresh attempt fails because refresh tokens
                    // were deleted at the same time.
                    filterChain.doFilter(request, response);
                    return;
                }

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities());

                authentication.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);
                MDC.put(RequestCorrelationFilter.MDC_USER, email);
            }
        } catch (Exception e) {
            logger.error("Cannot set user authentication: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * A signature-valid token is still refused when the account has been
     * deactivated or locked since it was issued, or when it was issued before
     * the account's tokens_valid_from (set on password change/reset, S-7).
     * Both instants are compared at whole-second precision because a JWT
     * "iat" claim has no sub-second part.
     */
    private boolean isRevoked(String jwt, UserDetails userDetails) {
        if (!userDetails.isEnabled() || !userDetails.isAccountNonLocked()) {
            logger.warn("Rejected token for inactive or locked account {}", userDetails.getUsername());
            return true;
        }
        if (!(userDetails instanceof NidaaUserDetails details) || details.getTokensValidFrom() == null) {
            return false;
        }
        Date issuedAt = jwtUtils.getIssuedAtFromToken(jwt);
        if (issuedAt == null) {
            return true;
        }
        long issuedAtSecond = issuedAt.getTime() / 1000;
        long validFromSecond = details.getTokensValidFrom()
                .truncatedTo(ChronoUnit.SECONDS)
                .atZone(ZoneId.systemDefault())
                .toEpochSecond();
        if (issuedAtSecond < validFromSecond) {
            logger.warn("Rejected token issued before password change for {}", userDetails.getUsername());
            return true;
        }
        return false;
    }

    // Extract token from Authorization header
    private String parseJwt(HttpServletRequest request) {
        String headerAuth = request.getHeader("Authorization");
        if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
            return headerAuth.substring(7);
        }
        return null;
    }
}