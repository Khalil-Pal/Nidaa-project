package com.humanitarian.platform.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "group_sessions")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class GroupSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "session_id")
    private Long id;

    @Column(name = "psychologist_id", nullable = false)
    private Long psychologistId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "category")
    private String category;

    @Column(name = "max_participants")
    @Builder.Default
    private Integer maxParticipants = 10;

    @Column(name = "current_participants")
    @Builder.Default
    private Integer currentParticipants = 0;

    @Column(name = "format")
    private String format;

    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "is_recurring")
    @Builder.Default
    private Boolean isRecurring = false;

    @Column(name = "recurrence_pattern", columnDefinition = "jsonb")
    private String recurrencePattern;

    @Column(name = "status")
    @Builder.Default
    private String status = "PLANNED";

    @Column(name = "meeting_link", columnDefinition = "TEXT")
    private String meetingLink;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "groupSession", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<GroupParticipant> participants;
}