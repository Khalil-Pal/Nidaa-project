package com.humanitarian.platform.model;

import java.io.Serializable;
import java.util.Objects;

/** Composite key of {@link MessageReaction}: the (message, user) pair. */
public class MessageReactionId implements Serializable {

    private Long messageId;
    private Long userId;

    public MessageReactionId() {
    }

    public MessageReactionId(Long messageId, Long userId) {
        this.messageId = messageId;
        this.userId = userId;
    }

    public Long getMessageId() {
        return messageId;
    }

    public Long getUserId() {
        return userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MessageReactionId other)) return false;
        return Objects.equals(messageId, other.messageId) && Objects.equals(userId, other.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, userId);
    }
}
