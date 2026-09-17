package com.humanitarian.platform.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What a psychological case recorded once it was completed (CS-1): one row per
 * psychological request, written by the assigned psychologist, rated by the
 * beneficiary. {@code notesForPsychologist} is private to the psychologist and
 * never leaves the service for anyone else.
 */
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

    // The assignment that produced the session (V20); nullable because rows may
    // predate the link.
    @Column(name = "assignment_id")
    private Long assignmentId;

    @Column(name = "psychologist_id", nullable = false)
    private Long psychologistId;

    @Column(name = "beneficiary_id", nullable = false)
    private Long beneficiaryId;

    @Column(name = "format", nullable = false, columnDefinition = "consultation_format")
    private String format;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    // PostgreSQL text[]; Hibernate 6 binds a List<String> as a typed array
    @Column(name = "topics_discussed", columnDefinition = "text[]")
    private List<String> topicsDiscussed;

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
