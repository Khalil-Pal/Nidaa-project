package com.humanitarian.platform.dto;

import java.time.LocalDateTime;

/**
 * A completion report as its parties see it (R-1). The beneficiary's rating and
 * feedback are on the same row and appear once given.
 */
public record AssignmentReportResponse(Long reportId,
                                       Long assignmentId,
                                       Long requestId,
                                       Long volunteerId,
                                       String volunteerName,
                                       String description,
                                       String feedbackFromBeneficiary,
                                       Integer beneficiaryRating,
                                       LocalDateTime createdAt) {
}
