package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<Notification> findByUserIdAndReadAtIsNull(Long userId);
    long countByUserIdAndReadAtIsNull(Long userId);
    List<Notification> findByStatus(String status);
    List<Notification> findByStatusAndType(String status, String type);
}