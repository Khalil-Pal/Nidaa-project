package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.ActivityLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {
    List<ActivityLog> findByUserIdOrderByTimestampDesc(Long userId);
    List<ActivityLog> findByAction(String action);
    List<ActivityLog> findByTimestampBetween(LocalDateTime from, LocalDateTime to);
    List<ActivityLog> findByEntityTypeAndEntityId(String entityType, Long entityId);
}