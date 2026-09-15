package com.humanitarian.platform.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Body of POST /api/assignments/{id}/feedback: the beneficiary's rating of the help (R-1). */
@Data
public class AssignmentFeedbackRequest {
    @NotNull(message = "rating is required")
    @Min(value = 1, message = "rating is 1 to 5")
    @Max(value = 5, message = "rating is 1 to 5")
    private Integer rating;

    @Size(max = 2000, message = "Feedback may be at most 2000 characters")
    private String feedback;
}
