package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.Notification;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /** Scoped by owner, so a foreign id is simply "not found" (N-1: 404, never 403). */
    Optional<Notification> findByIdAndUserId(Long id, Long userId);

    long countByUserIdAndReadAtIsNull(Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE notifications SET read_at = :now, status = 'READ' "
            + "WHERE user_id = :userId AND read_at IS NULL", nativeQuery = true)
    int markAllRead(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
