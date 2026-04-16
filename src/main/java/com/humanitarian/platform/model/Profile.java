package com.humanitarian.platform.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "profiles")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Profile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "profile_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "bio", columnDefinition = "TEXT")
    private String bio;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "latitude", columnDefinition = "numeric")
    private Double latitude;

    @Column(name = "longitude", columnDefinition = "numeric")
    private Double longitude;

    @Column(name = "preferred_language")
    private String preferredLanguage;

    @Column(name = "notification_settings", columnDefinition = "jsonb")
    private String notificationSettings;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}