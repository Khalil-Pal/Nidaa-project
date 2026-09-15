package com.humanitarian.platform.dto;

import com.humanitarian.platform.model.Notification;
import java.time.LocalDateTime;

/** One in-app notification as the owner sees it (N-1). */
public record NotificationDto(Long id,
                              String type,
                              String title,
                              String content,
                              String referenceType,
                              Long referenceId,
                              boolean read,
                              LocalDateTime createdAt,
                              LocalDateTime readAt) {

    public static NotificationDto from(Notification n) {
        return new NotificationDto(n.getId(), n.getType(), n.getTitle(), n.getContent(),
                n.getReferenceType(), n.getReferenceId(), n.getReadAt() != null,
                n.getCreatedAt(), n.getReadAt());
    }
}
