package com.humanitarian.platform.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "reports")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_id")
    private Long id;

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "volunteer_id", nullable = false)
    private Long volunteerId;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "photos", columnDefinition = "TEXT")
    private String photos;

    @Column(name = "feedback_from_beneficiary", columnDefinition = "TEXT")
    private String feedbackFromBeneficiary;

    @Column(name = "beneficiary_rating")
    private Integer beneficiaryRating;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}