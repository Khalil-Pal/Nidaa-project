package com.humanitarian.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PsychologicalRequestDto {

    /** Human-readable list for validation messages; the form sends the enum names. */
    public static final String CATEGORIES =
            "ANXIETY, DEPRESSION, PTSD, GRIEF, VIOLENCE, CRISIS, CHILD, OTHER";

    // Values are rejected here, with a field-level 400, rather than coerced
    // to a default in the service (B-2). Patterns are case-insensitive and
    // accept the display labels the form used before D-1 as well.
    @NotBlank(message = "Support type is required")
    @Pattern(regexp = "(?i)\\s*(INDIVIDUAL|GROUP|CRISIS)\\s*",
             message = "Support type must be one of: INDIVIDUAL, GROUP, CRISIS")
    private String supportType;

    @NotBlank(message = "Category is required")
    @Pattern(regexp = "(?i)\\s*(ANXIETY|DEPRESSION|PTSD|GRIEF|GRIEF[ _]?(&|AND)?[ _]?LOSS|VIOLENCE|DOMESTIC[ _]VIOLENCE|CRISIS|CRISIS[ _]SUPPORT|CHILD|OTHER)\\s*",
             message = "Category must be one of: " + CATEGORIES)
    private String category;

    @Pattern(regexp = "(?i)\\s*(LOW|MEDIUM|HIGH|CRITICAL)\\s*",
             message = "Urgency level must be one of: LOW, MEDIUM, HIGH, CRITICAL")
    private String urgencyLevel;

    @Pattern(regexp = "(?i)\\s*(CHAT|AUDIO|AUDIO_CALL|VIDEO|VIDEO_SESSION)\\s*",
             message = "Preferred format must be one of: CHAT, AUDIO, VIDEO")
    private String preferredFormat;
    @Size(max = 4000, message = "Description must be at most 4000 characters")
    private String description;
    private Boolean isAnonymous;
}