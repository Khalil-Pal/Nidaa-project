package com.humanitarian.platform.service;

import com.humanitarian.platform.dto.NotificationDto;
import com.humanitarian.platform.exception.ResourceNotFoundException;
import com.humanitarian.platform.model.Notification;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.repository.NotificationRepository;
import com.humanitarian.platform.repository.UserRepository;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notifications (N-1). Other services call {@link #notify} when
 * something happened that the person would otherwise learn only by reloading:
 * a request assigned, a status change, a case accepted, a crisis routed, an
 * account approved, a resource used up, a post removed by a moderator.
 *
 * A notification is written in the caller's transaction, so it exists exactly
 * when the event it announces does. Only {@code IN_APP} is delivered: the row is
 * SENT as soon as it is stored and the browser polls for it. The
 * {@code notification_type} enum also lists EMAIL, SMS and PUSH; the model
 * anticipates them, nothing sends them.
 *
 * Reading is owner-scoped: another person's notification is 404, never 403,
 * so ids cannot be probed.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    public static final String IN_APP = "IN_APP";
    public static final String REF_HELP_REQUEST = "HELP_REQUEST";
    public static final String REF_PSYCHOLOGICAL_REQUEST = "PSYCHOLOGICAL_REQUEST";
    public static final String REF_USER = "USER";
    public static final String REF_PROVIDER_RESOURCE = "PROVIDER_RESOURCE";
    public static final String REF_MESSAGE = "MESSAGE";

    private static final int MAX_PAGE_SIZE = 50;

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final UserService userService;

    public NotificationService(NotificationRepository notificationRepository,
                               UserRepository userRepository,
                               UserService userService) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.userService = userService;
    }

    /**
     * Stores a notification for {@code userId}. A null recipient is a no-op so
     * callers can pass optional parties (a filer, an unassigned provider) as is.
     */
    @Transactional
    public Notification create(Long userId, String type, String title, String content,
                               String referenceType, Long referenceId) {
        if (userId == null) {
            return null;
        }
        if (!IN_APP.equals(type)) {
            throw new IllegalArgumentException("Only IN_APP notifications are delivered; got " + type);
        }
        LocalDateTime now = LocalDateTime.now();
        Notification saved = notificationRepository.save(Notification.builder()
                .user(userRepository.getReferenceById(userId))
                .type(type)
                .title(title)
                .content(content)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .status("SENT")
                .sentAt(now)
                .build());
        log.debug("Notification {} for user {}: {} ({} {})", saved.getId(), userId, title, referenceType, referenceId);
        return saved;
    }

    /** {@link #create} for the one delivered channel. */
    public void notify(Long userId, String title, String content, String referenceType, Long referenceId) {
        create(userId, IN_APP, title, content, referenceType, referenceId);
    }

    @Transactional(readOnly = true)
    public Page<NotificationDto> listMine(int page, int size) {
        User me = userService.getCurrentUser();
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(me.getId(), pageable)
                .map(NotificationDto::from);
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return notificationRepository.countByUserIdAndReadAtIsNull(userService.getCurrentUser().getId());
    }

    /** Marks one of the caller's notifications read; someone else's id is 404. Already read is a no-op. */
    @Transactional
    public NotificationDto markRead(Long id) {
        User me = userService.getCurrentUser();
        Notification n = notificationRepository.findByIdAndUserId(id, me.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        if (n.getReadAt() == null) {
            n.setReadAt(LocalDateTime.now());
            n.setStatus("READ");
            n = notificationRepository.save(n);
        }
        return NotificationDto.from(n);
    }

    /** Marks every unread notification of the caller read; returns how many changed. */
    @Transactional
    public int markAllRead() {
        return notificationRepository.markAllRead(userService.getCurrentUser().getId(), LocalDateTime.now());
    }
}
