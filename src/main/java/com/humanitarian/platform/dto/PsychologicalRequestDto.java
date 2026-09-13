package com.humanitarian.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PsychologicalRequestDto {

    @NotBlank(message = "Support type is required")
    private String supportType;

    @NotBlank(message = "Category is required")
    private String category;

    private String urgencyLevel;
    private String preferredFormat;
    @Size(max = 4000, message = "Description must be at most 4000 characters")
    private String description;
    private Boolean isAnonymous;
}