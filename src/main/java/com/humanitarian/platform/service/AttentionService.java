package com.humanitarian.platform.service;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.UserRepository;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escalation to a human (GAP-1, GAP-2). Two things the matching rules cannot
 * solve raise the same flag: three providers declined a request, or it waited
 * past the escalation age with nobody available to take it. Both mean the queue
 * has done what it can and an administrator has to look.
 *
 * The flag is raised once — the guarded UPDATE only fires on a request that is
 * not already flagged — so a request that stays stale does not notify every
 * administrator every half hour. It comes down when the request is finally
 * assigned.
 */
@Service
public class AttentionService {

    private static final Logger log = LoggerFactory.getLogger(AttentionService.class);

    private final HelpRequestRepository helpRequestRepository;
    private final UserRepository userRepository;
    private final NotificationService notifications;

    public AttentionService(HelpRequestRepository helpRequestRepository,
                            UserRepository userRepository,
                            NotificationService notifications) {
        this.helpRequestRepository = helpRequestRepository;
        this.userRepository = userRepository;
        this.notifications = notifications;
    }

    /**
     * Flags the request and tells every administrator, unless it is already
     * flagged. Returns true when this call was the one that raised it.
     */
    @Transactional
    public boolean flag(HelpRequest request, String reason) {
        if (helpRequestRepository.flagForAttention(request.getId(), LocalDateTime.now(), reason) == 0) {
            return false;
        }
        log.info("Request {} flagged for administrator attention: {}", request.getId(), reason);
        for (User admin : userRepository.findByRole(UserRole.ADMIN)) {
            notifications.notify(admin.getId(), "A request needs your attention",
                    "\"" + request.getTitle() + "\" could not be matched: " + reason + ".",
                    NotificationService.REF_HELP_REQUEST, request.getId());
        }
        return true;
    }

    /** Assignment answers the escalation; the flag comes down with it. */
    @Transactional
    public void clear(Long requestId) {
        if (helpRequestRepository.clearAttention(requestId) > 0) {
            log.info("Request {} is assigned; the attention flag is cleared", requestId);
        }
    }
}
