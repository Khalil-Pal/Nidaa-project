package com.humanitarian.platform.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "psychological_requests")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PsychologicalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "request_id")
    private Long id;

    @Column(name = "beneficiary_id", nullable = false)
    private Long beneficiaryId;

    @Column(name = "assigned_psychologist_id")
    private Long assignedPsychologistId;

    // No columnDefinition needed — stringtype=unspecified handles enum cast
    @Column(name = "support_type")
    private String supportType;

    @Column(name = "category")
    private String category;

    @Column(name = "urgency_level")
    private String urgencyLevel;

    @Column(name = "preferred_format")
    private String preferredFormat;

    @Column(name = "preferred_time")
    private LocalDateTime preferredTime;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_anonymous")
    @Builder.Default
    private Boolean isAnonymous = false;

    @Column(name = "status")
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "is_crisis")
    @Builder.Default
    private Boolean isCrisis = false;

    @Column(name = "crisis_detected_at")
    private LocalDateTime crisisDetectedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}