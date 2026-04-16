package com.humanitarian.platform.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "help_requests")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class HelpRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "request_id")
    private Long id;

    @Column(name = "beneficiary_id", nullable = false)
    private Long beneficiaryId;

    @Column(name = "title")
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    // No columnDefinition needed — stringtype=unspecified in JDBC URL handles enum cast
    @Column(name = "help_type")
    private String helpType;

    @Column(name = "urgency_level")
    private String urgencyLevel;

    @Column(name = "priority_score")
    @Builder.Default
    private Integer priorityScore = 0;

    @Column(name = "people_count")
    @Builder.Default
    private Integer peopleCount = 1;

    @Column(name = "has_children")
    @Builder.Default
    private Boolean hasChildren = false;

    @Column(name = "has_elderly")
    @Builder.Default
    private Boolean hasElderly = false;

    @Column(name = "has_disabled")
    @Builder.Default
    private Boolean hasDisabled = false;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "latitude", columnDefinition = "numeric")
    private Double latitude;

    @Column(name = "longitude", columnDefinition = "numeric")
    private Double longitude;

    @Column(name = "status")
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "assigned_volunteer_id")
    private Long assignedVolunteerId;

    @Column(name = "assigned_organization_id")
    private Long assignedOrganizationId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;
}