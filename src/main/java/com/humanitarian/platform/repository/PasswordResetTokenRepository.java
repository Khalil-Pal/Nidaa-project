package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByEmail(String email);

    @Transactional
    void deleteByEmail(String email);

    @Transactional
    void deleteByExpiresAtBefore(LocalDateTime now);
}