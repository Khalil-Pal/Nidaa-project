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

    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    List<User> findByRole(UserRole role);
    List<User> findByIsActiveTrue();
    List<User> findByIsActiveFalse();  // for pending approvals
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

    @Modifying
    @Transactional
    @Query(value = "UPDATE users SET password_hash = :hash WHERE user_id = :userId",
            nativeQuery = true)
    void updatePassword(@Param("userId") Long userId, @Param("hash") String hash);
}