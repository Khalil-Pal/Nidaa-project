package com.humanitarian.platform.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "organizations")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "organization_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "official_name", nullable = false)
    private String officialName;

    @Column(name = "registration_number", unique = true)
    private String registrationNumber;

    @Column(name = "tax_id")
    private String taxId;

    @Column(name = "website")
    private String website;

    @Column(name = "mission_statement", columnDefinition = "TEXT")
    private String missionStatement;

    @Column(name = "operational_areas", columnDefinition = "TEXT")
    private String operationalAreas;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "verified_by")
    private Long verifiedBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}