package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.List;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /** Live accounts only: a soft-deleted user must not be able to log in or be resolved by email. */
    @Query("SELECT u FROM User u WHERE u.email = :email AND u.deletedAt IS NULL")
    Optional<User> findByEmail(@Param("email") String email);
    boolean existsByEmail(String email);
    List<User> findByRole(UserRole role);
    List<User> findByIsActiveTrue();
    List<User> findByIsActiveFalseAndDeletedAtIsNull();  // for pending approvals
    List<User> findByIsVerifiedFalse();
    List<User> findByIsLockedTrue();

    @Query("SELECT u FROM User u WHERE LOWER(u.fullName) LIKE LOWER(CONCAT('%', :name, '%'))")
    List<User> searchByName(@Param("name") String name);

    @Modifying
    @Transactional
    @Query(value = "UPDATE users SET is_active = true, is_verified = true WHERE user_id = :userId",
            nativeQuery = true)
    void approveUser(@Param("userId") Long userId);

    @Modifying
    @Transactional
    @Query(value = "UPDATE users SET is_locked = :locked WHERE user_id = :userId",
            nativeQuery = true)
    void setLocked(@Param("userId") Long userId, @Param("locked") boolean locked);

    @Modifying
    @Transactional
    @Query(value = "UPDATE users SET is_active = :active WHERE user_id = :userId",
            nativeQuery = true)
    void setActive(@Param("userId") Long userId, @Param("active") boolean active);

    @Modifying
    @Transactional
    @Query(value = "UPDATE users SET last_login = :lastLogin WHERE user_id = :userId",
            nativeQuery = true)
    void updateLastLogin(@Param("userId") Long userId, @Param("lastLogin") java.time.LocalDateTime lastLogin);

    /**
     * Anonymises an account in place (D-2). Aid history keeps its foreign keys;
     * the person becomes unidentifiable and can never log in again. The
     * placeholder address uses the reserved .invalid TLD so it satisfies the
     * email_valid CHECK constraint. Returns 0 if already deleted or missing.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE users SET deleted_at = :deletedAt, is_active = false, "
            + "tokens_valid_from = :deletedAt, "
            + "email = 'deleted-' || user_id || '@deleted.invalid', "
            + "full_name = 'Deleted user', phone = NULL "
            + "WHERE user_id = :id AND deleted_at IS NULL", nativeQuery = true)
    int softDelete(@Param("id") Long id, @Param("deletedAt") java.time.LocalDateTime deletedAt);

    /**
     * Replaces the password and, in the same statement, invalidates every
     * access token issued before {@code changedAt} (see JwtAuthenticationFilter).
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE users SET password_hash = :hash, tokens_valid_from = :changedAt "
            + "WHERE user_id = :userId", nativeQuery = true)
    void updatePassword(@Param("userId") Long userId,
                        @Param("hash") String hash,
                        @Param("changedAt") java.time.LocalDateTime changedAt);
}