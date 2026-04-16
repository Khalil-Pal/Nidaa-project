package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, Long> {
    Optional<Organization> findByUserId(Long userId);
    boolean existsByRegistrationNumber(String registrationNumber);
    List<Organization> findByVerifiedAtIsNotNull();
    List<Organization> findByVerifiedAtIsNull();
}