package com.humanitarian.platform.dto;

/** One row of a GROUP BY count: the grouped value and how many rows share it (Q-2). */
public record KeyCount(String key, Long count) {
}
