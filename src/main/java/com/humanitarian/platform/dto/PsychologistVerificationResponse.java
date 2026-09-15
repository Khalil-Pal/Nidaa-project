package com.humanitarian.platform.dto;

import java.time.LocalDateTime;

/** Result of an administrator's credential-verification action on a psychologist. */
public record PsychologistVerificationResponse(Long userId,
                                               Long psychologistId,
                                               boolean verified,
                                               boolean onDuty,
                                               LocalDateTime verifiedAt) {
}
