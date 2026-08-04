package com.humanitarian.platform.dto;

public record ProviderCapacityReservation(
        Long userId,
        String helpType,
        Integer reservedAmount) {

    public boolean hasNumericReservation() {
        return reservedAmount != null && reservedAmount > 0;
    }
}
