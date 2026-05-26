package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByToken(String token);

    Optional<RefreshToken> findByEmail(String email);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM refresh_tokens WHERE email = :email", nativeQuery = true)
    void deleteByEmail(@Param("email") String email);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM refresh_tokens WHERE expires_at < :now", nativeQuery = true)
    void deleteByExpiresAtBefore(@Param("now") LocalDateTime now);
}