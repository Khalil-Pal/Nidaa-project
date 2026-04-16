package com.humanitarian.platform.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "volunteers")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Volunteer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "volunteer_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "skills", columnDefinition = "TEXT")
    private String skills;

    @Column(name = "availability", columnDefinition = "jsonb")
    private String availability;

    @Column(name = "total_completed_requests")
    @Builder.Default
    private Integer totalCompletedRequests = 0;

    @Column(name = "rating", columnDefinition = "numeric")
    @Builder.Default
    private Double rating = 0.0;

    @CreationTimestamp
    @Column(name = "joined_at", updatable = false)
    private LocalDateTime joinedAt;

    @Column(name = "is_available")
    @Builder.Default
    private Boolean isAvailable = true;
}