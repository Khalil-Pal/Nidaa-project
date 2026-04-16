package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.Volunteer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface VolunteerRepository extends JpaRepository<Volunteer, Long> {
    Optional<Volunteer> findByUserId(Long userId);
    List<Volunteer> findByIsAvailableTrue();
    List<Volunteer> findByOrganizationId(Long organizationId);

    @Query("SELECT v FROM Volunteer v ORDER BY v.rating DESC")
    List<Volunteer> findTopRatedVolunteers();
}