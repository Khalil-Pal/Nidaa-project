package com.humanitarian.platform.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Body of PUT /api/admin/psychologists/{userId}/verification. */
@Data
public class PsychologistVerificationDto {
    @NotNull(message = "verified is required")
    private Boolean verified;
}
