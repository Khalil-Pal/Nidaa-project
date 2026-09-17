package com.humanitarian.platform.dto;

/**
 * One provider's source data for the counter caches (AGG-1): how many records
 * exist and the mean of the ratings given so far ({@code null} when nobody has
 * rated yet, since AVG ignores NULL rows and returns NULL over none).
 */
public record RatingSummary(Long count, Double mean) {
}
