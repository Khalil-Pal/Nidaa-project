package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, Long> {
    boolean existsByRegistrationNumber(String registrationNumber);
    List<Organization> findByVerifiedAtIsNotNull();
    List<Organization> findByVerifiedAtIsNull();
}