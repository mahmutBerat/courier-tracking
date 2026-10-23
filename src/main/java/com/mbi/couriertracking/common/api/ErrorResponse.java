package com.mbi.couriertracking.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Error body returned by every API failure. New fields are added here so all errors stay uniform.
 *
 * @param status      HTTP status code
 * @param error       HTTP reason phrase
 * @param code        stable machine-readable code, such as {@code COURIER_NOT_FOUND} or {@code VALIDATION_FAILED}
 * @param message     human-readable explanation
 * @param path        request path that produced the error
 * @param fieldErrors field-level violations; omitted when empty
 */
public record ErrorResponse(
        int status,
        String error,
        String code,
        String message,
        String path,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldError> fieldErrors
) {

    public record FieldError(String field, String message) {
    }
}
