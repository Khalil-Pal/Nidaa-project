package com.humanitarian.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.humanitarian.platform.model.ActivityLog;
import com.humanitarian.platform.repository.ActivityLogRepository;
import com.humanitarian.platform.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

/**
 * Writes administrator actions to {@code activity_logs} and to the log (C-4):
 * who did what to which record, from where. The row is saved in the caller's
 * transaction, so an action and its record commit or roll back together.
 *
 * The actor is the authenticated user of the current request; when called
 * outside a request (a scheduler, a test without a security context) the
 * entry is stored without an actor rather than skipped.
 */
@Service
public class AdminAuditService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditService.class);

    private final ActivityLogRepository activityLogRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AdminAuditService(ActivityLogRepository activityLogRepository, UserRepository userRepository) {
        this.activityLogRepository = activityLogRepository;
        this.userRepository = userRepository;
    }

    public void record(String action, String entityType, Long entityId, Map<String, Object> details) {
        Long actorId = currentActorId();
        HttpServletRequest request = currentRequest();
        ActivityLog entry = ActivityLog.builder()
                .userId(actorId)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .details(toJson(details))
                .ipAddress(request == null ? null : request.getRemoteAddr())
                .userAgent(request == null ? null : request.getHeader("User-Agent"))
                .build();
        activityLogRepository.save(entry);
        log.info("ADMIN {} {} {} by user {} {}", action, entityType, entityId, actorId, details);
    }

    /** The authenticated administrator's id, or null outside a request (see class comment). */
    public Long currentActorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            return null;
        }
        return userRepository.findByEmail(auth.getName()).map(u -> u.getId()).orElse(null);
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest() : null;
    }

    private String toJson(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            return "{\"unserialisable\":true}";
        }
    }
}
