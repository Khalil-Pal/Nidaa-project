package com.humanitarian.platform.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A consultation record as its parties see it (CS-1). The beneficiary's rating
 * and feedback are on the same row and appear once given.
 *
 * The response never carries the beneficiary's identity (no id, no name), so an
 * anonymous request stays anonymous through this surface as well.
 * {@code notesForPsychologist} is set only when the caller is the assigned
 * psychologist; for everyone else the service leaves it null and the key is
 * omitted from the JSON, not sent as null.
 */
public record ConsultationResponse(Long consultationId,
                                   Long requestId,
                                   Long assignmentId,
                                   Long psychologistId,
                                   String psychologistName,
                                   String format,
                                   LocalDateTime startedAt,
                                   LocalDateTime endedAt,
                                   Integer durationMinutes,
                                   List<String> topicsDiscussed,
                                   String recommendations,
                                   Boolean isCrisis,
                                   String feedbackFromBeneficiary,
                                   Integer rating,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) String notesForPsychologist) {
}
