package com.humanitarian.platform.repository;

import com.humanitarian.platform.dto.IdCount;
import com.humanitarian.platform.model.MessageComment;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageCommentRepository extends JpaRepository<MessageComment, Long> {

    Page<MessageComment> findByMessageIdAndIsDeletedFalseOrderByCreatedAtAscIdAsc(Long messageId, Pageable pageable);

    Optional<MessageComment> findByIdAndMessageIdAndIsDeletedFalse(Long id, Long messageId);

    long countByMessageIdAndIsDeletedFalse(Long messageId);

    /** Visible comment counts for a page of posts in one query; posts with none are absent. */
    @Query("SELECT new com.humanitarian.platform.dto.IdCount(c.messageId, COUNT(c)) "
         + "FROM MessageComment c WHERE c.messageId IN :messageIds AND c.isDeleted = false GROUP BY c.messageId")
    List<IdCount> countVisibleByMessageIds(@Param("messageIds") Collection<Long> messageIds);
}
