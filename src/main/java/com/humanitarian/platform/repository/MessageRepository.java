package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    @Query("SELECT m FROM Message m WHERE " +
            "m.messageType = com.humanitarian.platform.model.MessageType.DIRECT AND " +
            "((m.senderId = :userId1 AND m.receiverId = :userId2) " +
            "OR (m.senderId = :userId2 AND m.receiverId = :userId1)) ORDER BY m.sentAt ASC")
    List<Message> findConversation(@Param("userId1") Long userId1, @Param("userId2") Long userId2);

    @Query("SELECT m FROM Message m WHERE " +
            "m.messageType = com.humanitarian.platform.model.MessageType.DIRECT " +
            "AND m.receiverId = :receiverId AND m.isRead = false")
    List<Message> findByReceiverIdAndIsReadFalse(@Param("receiverId") Long receiverId);

    @Query("SELECT COUNT(m) FROM Message m WHERE " +
            "m.messageType = com.humanitarian.platform.model.MessageType.DIRECT " +
            "AND m.receiverId = :receiverId AND m.isRead = false")
    long countByReceiverIdAndIsReadFalse(@Param("receiverId") Long receiverId);

    @Query("SELECT m FROM Message m WHERE " +
            "m.messageType = com.humanitarian.platform.model.MessageType.DIRECT " +
            "AND m.helpRequestId = :helpRequestId")
    List<Message> findByHelpRequestId(@Param("helpRequestId") Long helpRequestId);

    @Query("SELECT m FROM Message m WHERE " +
            "m.messageType = com.humanitarian.platform.model.MessageType.DIRECT " +
            "AND m.psychologicalRequestId = :psychologicalRequestId")
    List<Message> findByPsychologicalRequestId(
            @Param("psychologicalRequestId") Long psychologicalRequestId);

    @Query("SELECT m FROM Message m WHERE " +
            "m.messageType = com.humanitarian.platform.model.MessageType.COMMUNITY " +
            "AND m.isDeleted = false ORDER BY m.sentAt DESC, m.id DESC")
    Page<Message> findVisibleCommunityMessages(Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.id = :id " +
            "AND m.messageType = com.humanitarian.platform.model.MessageType.COMMUNITY " +
            "AND m.isDeleted = false")
    Optional<Message> findVisibleCommunityMessageById(@Param("id") Long id);
}
