package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    @Query("SELECT m FROM Message m WHERE (m.senderId = :userId1 AND m.receiverId = :userId2) " +
            "OR (m.senderId = :userId2 AND m.receiverId = :userId1) ORDER BY m.sentAt ASC")
    List<Message> findConversation(@Param("userId1") Long userId1, @Param("userId2") Long userId2);

    List<Message> findByReceiverIdAndIsReadFalse(Long receiverId);
    long countByReceiverIdAndIsReadFalse(Long receiverId);
    List<Message> findByHelpRequestId(Long helpRequestId);
    List<Message> findByPsychologicalRequestId(Long psychologicalRequestId);
}