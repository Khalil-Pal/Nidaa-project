package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.GroupSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface GroupSessionRepository extends JpaRepository<GroupSession, Long> {
    List<GroupSession> findByPsychologistId(Long psychologistId);
    List<GroupSession> findByStatus(String status);
    List<GroupSession> findByCategory(String category);
    List<GroupSession> findByScheduledAtAfterAndStatus(LocalDateTime now, String status);
}