package com.changeops.changeservice.api.controller;

import com.changeops.changeservice.domain.exception.ChangeNotFoundException;
import com.changeops.changeservice.domain.exception.InvalidChangeStateException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.NonNull;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final @NonNull URI VALIDATION_TYPE = errorType("https://changeops.io/errors/validation");
    private static final @NonNull URI BAD_REQUEST_TYPE = errorType("https://changeops.io/errors/bad-request");
    private static final @NonNull URI NOT_FOUND_TYPE = errorType("https://changeops.io/errors/not-found");
    private static final @NonNull URI INVALID_STATE_TYPE = errorType("https://changeops.io/errors/invalid-state");
    private static final @NonNull URI INVALID_PARAMETER_TYPE = errorType("https://changeops.io/errors/invalid-parameter");
    private static final @NonNull URI INTERNAL_TYPE = errorType("https://changeops.io/errors/internal");

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage,
                        (a, b) -> a));

        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Validation failed");
        pd.setType(VALIDATION_TYPE);
        pd.setTitle("Validation Error");
        pd.setProperty("fields", fields);
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleNotReadable(HttpMessageNotReadableException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Malformed or unreadable request body");
        pd.setType(BAD_REQUEST_TYPE);
        pd.setTitle("Bad Request");
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    @ExceptionHandler(ChangeNotFoundException.class)
    public ProblemDetail handleNotFound(ChangeNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                detailOrDefault(ex.getMessage(), "Change not found"));
        pd.setType(NOT_FOUND_TYPE);
        pd.setTitle("Resource Not Found");
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    @ExceptionHandler(InvalidChangeStateException.class)
    public ProblemDetail handleInvalidState(InvalidChangeStateException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, detailOrDefault(ex.getMessage(), "Invalid state transition"));
        pd.setType(INVALID_STATE_TYPE);
        pd.setTitle("Invalid State Transition");
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String paramName = ex.getName();
        Class<?> requiredType = ex.getRequiredType();
        String typeName = requiredType != null ? requiredType.getSimpleName() : "expected type";
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Parameter '" + paramName + "' must be a valid " + typeName);
        pd.setType(INVALID_PARAMETER_TYPE);
        pd.setTitle("Invalid Parameter");
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        pd.setType(INTERNAL_TYPE);
        pd.setTitle("Internal Server Error");
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }

    private static @NonNull String detailOrDefault(String detail, @NonNull String fallback) {
        return detail != null ? detail : fallback;
    }

    @SuppressWarnings("null")
    private static @NonNull URI errorType(String value) {
        return URI.create(value);
    }
}
