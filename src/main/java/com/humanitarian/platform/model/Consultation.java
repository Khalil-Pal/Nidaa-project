package com.humanitarian.platform.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "consultations")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Consultation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "consultation_id")
    private Long id;

    @Column(name = "psychological_request_id", nullable = false)
    private Long psychologicalRequestId;

    @Column(name = "psychologist_id", nullable = false)
    private Long psychologistId;

    @Column(name = "beneficiary_id", nullable = false)
    private Long beneficiaryId;

    @Column(name = "format", columnDefinition = "varchar(50)")
    private String format;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    // PostgreSQL _text (ARRAY) type — store as plain TEXT
    @Column(name = "topics_discussed", columnDefinition = "_text")
    private String topicsDiscussed;

    @Column(name = "recommendations", columnDefinition = "TEXT")
    private String recommendations;

    @Column(name = "feedback_from_beneficiary", columnDefinition = "TEXT")
    private String feedbackFromBeneficiary;

    @Column(name = "rating")
    private Integer rating;

    @Column(name = "notes_for_psychologist", columnDefinition = "TEXT")
    private String notesForPsychologist;

    @Column(name = "is_crisis")
    @Builder.Default
    private Boolean isCrisis = false;

    @Column(name = "chat_session_id")
    private UUID chatSessionId;
}