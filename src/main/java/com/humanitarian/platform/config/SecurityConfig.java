package com.humanitarian.platform.config;

import com.humanitarian.platform.security.JwtAuthenticationFilter;
import com.humanitarian.platform.security.UserDetailsServiceImpl;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Autowired private UserDetailsServiceImpl userDetailsService;
    @Autowired private JwtAuthenticationFilter jwtAuthenticationFilter;

    // Explicit origins only: a wildcard pattern combined with allowCredentials
    // makes Spring reflect any caller's origin, which defeats the browser's
    // same-origin protection for credentialed requests.
    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider();
        p.setUserDetailsService(userDetailsService);
        p.setPasswordEncoder(passwordEncoder());
        return p;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(c -> c.disable())
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // No valid token (missing, malformed or expired) is 401 so the
                // frontend can attempt a refresh. Without an entry point Spring
                // falls back to 403, which is reserved for authenticated callers
                // that lack the role or do not own the record.
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                writeJson(res, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required."))
                        // Written directly rather than via sendError(403): sendError
                        // re-dispatches to /error, which would pass through this chain
                        // again without the caller's authentication and turn the 403
                        // into a 401 at the entry point above.
                        .accessDeniedHandler((req, res, ex) ->
                                writeJson(res, HttpServletResponse.SC_FORBIDDEN,
                                        "Access denied. You don't have permission to perform this action.")))
                .authorizeHttpRequests(auth -> auth
                        // Let the container's error page render for other sendError()
                        // paths instead of being blocked as an unauthenticated request
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // Static files — no auth needed
                        .requestMatchers("/*.html", "/*.css", "/*.js", "/*.png",
                                "/*.jpg", "/*.ico", "/*.svg", "/*.woff", "/*.woff2",
                                "/", "/favicon.ico", "/static/**", "/assets/**",
                                "/js/**", "/css/**", "/images/**").permitAll()
                        // Auth endpoints — no auth needed
                        .requestMatchers("/api/auth/**").permitAll()
                        // Landing-page statistics are read by logged-out visitors
                        .requestMatchers(HttpMethod.GET, "/api/dashboard/public-stats").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/help-requests/ranked")
                        .hasAnyRole("ADMIN", "VOLUNTEER", "ORGANIZATION")
                        .requestMatchers("/api/v1/admin/**", "/api/admin/**").hasRole("ADMIN")
                        // Coarse role rules by URL so that a controller method
                        // missing its @PreAuthorize fails closed instead of opening
                        // the endpoint to every logged-in user. Row-level ownership
                        // is still enforced in the services.
                        .requestMatchers(HttpMethod.GET, "/api/help-requests", "/api/help-requests/pending")
                        .hasAnyRole("ADMIN", "VOLUNTEER", "ORGANIZATION")
                        .requestMatchers(HttpMethod.POST, "/api/help-requests")
                        .hasAnyRole("BENEFICIARY", "VOLUNTEER", "ORGANIZATION")
                        .requestMatchers(HttpMethod.PUT, "/api/help-requests/*/status")
                        .hasAnyRole("ADMIN", "BENEFICIARY", "VOLUNTEER", "ORGANIZATION")
                        .requestMatchers("/api/psychological-requests/**")
                        .hasAnyRole("BENEFICIARY", "PSYCHOLOGIST", "ADMIN")
                        .requestMatchers("/api/provider-resources/**", "/api/provider-availability/**")
                        .hasAnyRole("VOLUNTEER", "ORGANIZATION")
                        // Everything else needs a valid JWT; @PreAuthorize narrows further
                        .anyRequest().authenticated()
                )
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private static void writeJson(HttpServletResponse res, int status, String message)
            throws java.io.IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"success\":false,\"message\":\"" + message + "\"}");
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(allowedOrigins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }
}
