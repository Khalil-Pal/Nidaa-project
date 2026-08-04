package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.ProviderResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProviderResourceRepository extends JpaRepository<ProviderResource, Long> {
    List<ProviderResource> findByUserId(Long userId);
    List<ProviderResource> findByHelpType(String helpType);
    Optional<ProviderResource> findByUserIdAndHelpType(Long userId, String helpType);
}
