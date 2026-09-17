package com.humanitarian.platform.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * Body of POST /api/psychological-requests/{id}/consultation: what the assigned
 * psychologist records once the case is completed (CS-1). {@code notesForPsychologist}
 * is the psychologist's private note and is returned to nobody else.
 */
@Data
public class ConsultationRequest {
    @NotBlank(message = "Format is required")
    @Pattern(regexp = "(?i)\\s*(CHAT|AUDIO|AUDIO_CALL|VIDEO|VIDEO_SESSION)\\s*",
             message = "Format must be one of: CHAT, AUDIO, VIDEO")
    private String format;

    /** When the session took place; defaults to the moment of recording. */
    private LocalDateTime startedAt;

    @Min(value = 0, message = "Duration is in minutes and cannot be negative")
    @Max(value = 1440, message = "Duration is in minutes, at most one day")
    private Integer durationMinutes;

    @Size(max = 20, message = "At most 20 topics")
    private List<@NotBlank(message = "A topic cannot be blank")
                 @Size(max = 100, message = "A topic may be at most 100 characters") String> topicsDiscussed;

    @Size(max = 4000, message = "Recommendations may be at most 4000 characters")
    private String recommendations;

    @Size(max = 4000, message = "Notes may be at most 4000 characters")
    private String notesForPsychologist;
}
