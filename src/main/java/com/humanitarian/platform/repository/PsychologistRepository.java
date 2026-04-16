package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.Psychologist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface PsychologistRepository extends JpaRepository<Psychologist, Long> {
    Optional<Psychologist> findByUserId(Long userId);
    List<Psychologist> findByIsVerifiedTrue();
    List<Psychologist> findByIsVerifiedFalse();
    List<Psychologist> findByIsVerifiedTrueAndIsOnDutyTrue();

    @Query("SELECT p FROM Psychologist p WHERE LOWER(p.specialization) LIKE LOWER(CONCAT('%', :spec, '%')) AND p.isVerified = true")
    List<Psychologist> findBySpecialization(@Param("spec") String specialization);
}