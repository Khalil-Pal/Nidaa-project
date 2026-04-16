package com.humanitarian.platform.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "psychologists")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Psychologist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "psychologist_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "specialization", columnDefinition = "TEXT")
    private String specialization;

    @Column(name = "education", columnDefinition = "jsonb")
    private String education;

    @Column(name = "certificates", columnDefinition = "TEXT")
    private String certificates;

    @Column(name = "experience_years")
    private Integer experienceYears;

    @Column(name = "languages", columnDefinition = "TEXT")
    private String languages;

    @Column(name = "consultation_count")
    @Builder.Default
    private Integer consultationCount = 0;

    @Column(name = "rating", columnDefinition = "numeric")
    @Builder.Default
    private Double rating = 0.0;

    @Column(name = "is_verified")
    @Builder.Default
    private Boolean isVerified = false;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "verified_by")
    private Long verifiedBy;

    @Column(name = "available_schedule", columnDefinition = "jsonb")
    private String availableSchedule;

    @Column(name = "is_on_duty")
    @Builder.Default
    private Boolean isOnDuty = false;

    @Column(name = "hourly_rate")
    private Integer hourlyRate;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}