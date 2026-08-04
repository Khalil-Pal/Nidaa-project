package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.Organization;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, Long> {
    boolean existsByRegistrationNumber(String registrationNumber);
    List<Organization> findByVerifiedAtIsNotNull();
    List<Organization> findByVerifiedAtIsNull();

    @EntityGraph(attributePaths = {"user", "user.profile"})
    List<Organization> findByIsAvailableTrue();

    @EntityGraph(attributePaths = {"user", "user.profile"})
    Optional<Organization> findByUserId(Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE organizations SET is_available = false " +
            "WHERE organization_id = :organizationId AND is_available = true " +
            "AND availability_preference = true",
            nativeQuery = true)
    int claimIfAvailable(@Param("organizationId") Long organizationId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE organizations SET is_available = availability_preference " +
            "WHERE organization_id = :organizationId",
            nativeQuery = true)
    int release(@Param("organizationId") Long organizationId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE organizations SET is_available = :available, " +
            "availability_preference = :available WHERE user_id = :userId",
            nativeQuery = true)
    int setManualAvailability(@Param("userId") Long userId,
                              @Param("available") boolean available);
}
