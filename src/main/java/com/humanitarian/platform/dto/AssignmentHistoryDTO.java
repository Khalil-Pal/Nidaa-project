package com.humanitarian.platform.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignmentHistoryDTO {
    private Long assignmentId;
    private String requestType;
    private Long requestId;
    private String requestSummary;
    private String assigneeType;
    private Long assigneeId;
    private String assigneeName;
    private Long assignedById;
    private String assignedByName;
    private String assignmentSource;
    private boolean automated;
    private String status;
    private LocalDateTime assignedAt;
    private LocalDateTime completedAt;
    private Long waitingTimeMinutes;
    private Long assignmentDurationMinutes;
    private String notes;
}
