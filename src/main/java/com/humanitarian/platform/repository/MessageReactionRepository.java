package com.humanitarian.platform.repository;

import com.humanitarian.platform.dto.IdCount;
import com.humanitarian.platform.model.MessageReaction;
import com.humanitarian.platform.model.MessageReactionId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageReactionRepository extends JpaRepository<MessageReaction, MessageReactionId> {

    boolean existsByMessageIdAndUserId(Long messageId, Long userId);

    long countByMessageId(Long messageId);

    long deleteByMessageIdAndUserId(Long messageId, Long userId);

    /** Like counts for a page of posts in one query; posts with none are absent. */
    @Query("SELECT new com.humanitarian.platform.dto.IdCount(r.messageId, COUNT(r)) "
         + "FROM MessageReaction r WHERE r.messageId IN :messageIds GROUP BY r.messageId")
    List<IdCount> countByMessageIds(@Param("messageIds") Collection<Long> messageIds);

    /** Which of a page of posts the viewer has liked. */
    @Query("SELECT r.messageId FROM MessageReaction r WHERE r.userId = :userId AND r.messageId IN :messageIds")
    List<Long> likedMessageIds(@Param("userId") Long userId, @Param("messageIds") Collection<Long> messageIds);
}
