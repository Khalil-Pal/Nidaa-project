package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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
}