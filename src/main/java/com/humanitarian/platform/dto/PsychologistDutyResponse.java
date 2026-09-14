package com.humanitarian.platform.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A psychologist's duty state (UX-2). {@code verified} is included because
 * crisis routing selects only psychologists that are both verified and on
 * duty; a psychologist who is on duty but not yet verified should see why no
 * crisis case reaches them.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PsychologistDutyResponse {
    private Long userId;
    private Long psychologistId;
    private Boolean onDuty;
    private Boolean verified;
    private long openCases;
}
