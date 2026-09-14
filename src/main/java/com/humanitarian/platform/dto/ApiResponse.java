package com.humanitarian.platform.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The one response envelope (P-2). Every success body is
 * {@code {"success":true,"message":...,"data":...}} and every error body,
 * whether written by {@code GlobalExceptionHandler} or by a servlet filter,
 * is {@code {"success":false,"message":...}} with {@code details} only when
 * there is something structured to say (validation failures: field to message).
 * {@code data} and {@code details} are omitted rather than written as null, so
 * the servlet filters that write JSON by hand produce byte-identical bodies.
 * The client reads {@code success} first and never has to know which layer answered.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    private boolean success;
    private String message;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private T data;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Object details;

    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .build();
    }

    public static <T> ApiResponse<T> error(String message) {
        return error(message, null);
    }

    public static <T> ApiResponse<T> error(String message, Object details) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .details(details)
                .build();
    }
}