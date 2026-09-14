package com.humanitarian.platform.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Body of PUT /api/psychologists/me/duty (UX-2). */
@Data
public class PsychologistDutyDto {
    @NotNull(message = "onDuty is required")
    private Boolean onDuty;
}
