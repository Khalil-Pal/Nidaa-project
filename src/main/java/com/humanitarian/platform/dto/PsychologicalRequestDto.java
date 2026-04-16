package com.humanitarian.platform.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PsychologicalRequestDto {

    @NotBlank(message = "Support type is required")
    private String supportType;

    @NotBlank(message = "Category is required")
    private String category;

    private String urgencyLevel;
    private String preferredFormat;
    private String description;
    private Boolean isAnonymous;
}