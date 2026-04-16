package com.humanitarian.platform.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "assignments")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Assignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "assignment_id")
    private Long id;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Column(name = "volunteer_id", nullable = false)
    private Long volunteerId;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "assigned_by")
    private Long assignedBy;

    @CreationTimestamp
    @Column(name = "assigned_at", updatable = false)
    private LocalDateTime assignedAt;

    @Column(name = "status")
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}