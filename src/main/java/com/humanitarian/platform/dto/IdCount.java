package com.humanitarian.platform.dto;

/** One row of a GROUP BY count keyed by an id: the id and how many rows share it (CM-1). */
public record IdCount(Long id, Long count) {
}
