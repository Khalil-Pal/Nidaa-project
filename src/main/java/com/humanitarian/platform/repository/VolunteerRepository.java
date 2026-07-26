package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.Volunteer;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface VolunteerRepository extends JpaRepository<Volunteer, Long> {
    @EntityGraph(attributePaths = "user")
    List<Volunteer> findByIsAvailableTrue();

    @EntityGraph(attributePaths = "user")
    Optional<Volunteer> findByUserId(Long userId);

    @Override
    @EntityGraph(attributePaths = "user")
    List<Volunteer> findAll();

    List<Volunteer> findByOrganizationId(Long organizationId);

    @Query("SELECT v FROM Volunteer v ORDER BY v.rating DESC")
    List<Volunteer> findTopRatedVolunteers();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE volunteers SET is_available = false " +
            "WHERE volunteer_id = :volunteerId AND is_available = true",
            nativeQuery = true)
    int claimIfAvailable(@Param("volunteerId") Long volunteerId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE volunteers SET is_available = true WHERE volunteer_id = :volunteerId",
            nativeQuery = true)
    int release(@Param("volunteerId") Long volunteerId);
}
