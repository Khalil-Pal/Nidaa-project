package com.humanitarian.platform.dto;

/** Open cases per psychologist, one row of the grouped query used to rank crisis routing (Q-1). */
public record PsychologistCaseLoad(Long psychologistId, Long openCases) {
}
