package com.humanitarian.platform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

/**
 * One user's like on one community post (CM-1). The (message, user) pair is the
 * primary key (V21), so a second like is refused by the database as well as by
 * the service.
 *
 * The key is assigned by the caller, so Spring Data would treat every save as a
 * merge (a SELECT then an UPDATE that changes nothing) and the key would never
 * refuse anything. {@link Persistable} tells it a reaction without a timestamp
 * has not been inserted yet, so a save is an INSERT and a duplicate is a
 * constraint violation.
 */
@Entity
@Table(name = "message_reactions")
@IdClass(MessageReactionId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageReaction implements Persistable<MessageReactionId> {

    @Id
    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Override
    @Transient
    public MessageReactionId getId() {
        return new MessageReactionId(messageId, userId);
    }

    @Override
    @Transient
    public boolean isNew() {
        return createdAt == null;
    }
}
