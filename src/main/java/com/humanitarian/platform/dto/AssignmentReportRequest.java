package com.humanitarian.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Body of POST /api/assignments/{id}/report: what the volunteer delivered (R-1). */
@Data
public class AssignmentReportRequest {
    @NotBlank(message = "Describe what was delivered")
    @Size(max = 4000, message = "The description may be at most 4000 characters")
    private String description;
}
