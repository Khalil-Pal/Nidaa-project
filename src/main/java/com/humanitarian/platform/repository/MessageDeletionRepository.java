package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.MessageDeletion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageDeletionRepository extends JpaRepository<MessageDeletion, Long> {

    Page<MessageDeletion> findAllByOrderByDeletedAtDescIdDesc(Pageable pageable);
}
