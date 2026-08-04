package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.ProviderResource;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProviderResourceRepository extends JpaRepository<ProviderResource, Long> {
    List<ProviderResource> findByUserId(Long userId);
    List<ProviderResource> findByHelpType(String helpType);
    Optional<ProviderResource> findByUserIdAndHelpType(Long userId, String helpType);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT resource FROM ProviderResource resource "
            + "WHERE resource.userId = :userId AND resource.helpType = :helpType")
    Optional<ProviderResource> findByUserIdAndHelpTypeForUpdate(
            @Param("userId") Long userId,
            @Param("helpType") String helpType);
}
