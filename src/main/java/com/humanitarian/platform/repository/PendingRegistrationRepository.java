package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.PendingRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface PendingRegistrationRepository extends JpaRepository<PendingRegistration, Long> {

    Optional<PendingRegistration> findByEmail(String email);

    boolean existsByEmail(String email);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM pending_registrations WHERE email = :email", nativeQuery = true)
    void deleteByEmail(@Param("email") String email);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM pending_registrations WHERE expires_at < :now", nativeQuery = true)
    void deleteByExpiresAtBefore(@Param("now") LocalDateTime now);
}