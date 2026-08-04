package com.humanitarian.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VolunteerOccupationDto {

    @NotBlank(message = "Occupation is required")
    @Size(max = 150, message = "Occupation must not exceed 150 characters")
    private String occupation;
}
