package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.SelfHelpMaterial;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SelfHelpMaterialRepository extends JpaRepository<SelfHelpMaterial, Long> {
    List<SelfHelpMaterial> findByIsPublishedTrue();
    List<SelfHelpMaterial> findByIsPublishedTrueAndContentType(String contentType);
    List<SelfHelpMaterial> findByIsPublishedTrueAndCategory(String category);
    List<SelfHelpMaterial> findByAuthorId(Long authorId);
    List<SelfHelpMaterial> findByIsPublishedTrueOrderByViewsCountDesc();
    List<SelfHelpMaterial> findByLanguage(String language);

    @Query("SELECT m FROM SelfHelpMaterial m WHERE LOWER(m.tags) LIKE LOWER(CONCAT('%', :tag, '%')) AND m.isPublished = true")
    List<SelfHelpMaterial> findByTag(@Param("tag") String tag);
}