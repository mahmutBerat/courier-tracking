package com.mbi.couriertracking.courier.api;

import com.mbi.couriertracking.common.api.ErrorResponse;
import com.mbi.couriertracking.courier.application.CourierAlreadyExistsException;
import com.mbi.couriertracking.courier.application.CourierNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts application and framework exceptions into the uniform {@link ErrorResponse} body.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    private static final String VALIDATION_MESSAGE = "Request validation failed.";

    @ExceptionHandler(CourierNotFoundException.class)
    ResponseEntity<ErrorResponse> handleCourierNotFound(CourierNotFoundException exception, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "COURIER_NOT_FOUND", "Courier not found.", request.getRequestURI(), List.of());
    }

    @ExceptionHandler(CourierAlreadyExistsException.class)
    ResponseEntity<ErrorResponse> handleCourierAlreadyExists(CourierAlreadyExistsException exception, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "COURIER_ALREADY_EXISTS", "Courier already exists.", request.getRequestURI(), List.of());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = new ArrayList<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.add(new ErrorResponse.FieldError(fieldError.getField(), fieldError.getDefaultMessage()));
        }
        return validationFailure(fieldErrors, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception, HttpHeaders headers,
                                                                            HttpStatusCode status, WebRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = new ArrayList<>();
        for (ParameterValidationResult result : exception.getParameterValidationResults()) {
            String parameterName = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                fieldErrors.add(new ErrorResponse.FieldError(parameterName, error.getDefaultMessage()));
            }
        }
        return validationFailure(fieldErrors, headers, request);
    }

    /**
     * Framework exceptions arrive here with a {@link ProblemDetail} body; it is translated so every error shares one shape.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            HttpStatus status = HttpStatus.valueOf(statusCode.value());
            ErrorResponse errorResponse = new ErrorResponse(status.value(), status.getReasonPhrase(), status.name(),
                    problem.getDetail(), requestPath(request), List.of());
            return new ResponseEntity<>(errorResponse, headers, statusCode);
        }
        return new ResponseEntity<>(body, headers, statusCode);
    }

    private ResponseEntity<Object> validationFailure(List<ErrorResponse.FieldError> fieldErrors, HttpHeaders headers, WebRequest request) {
        ErrorResponse errorResponse = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.getReasonPhrase(),
                VALIDATION_FAILED, VALIDATION_MESSAGE, requestPath(request), List.copyOf(fieldErrors));
        return new ResponseEntity<>(errorResponse, headers, HttpStatus.BAD_REQUEST);
    }

    private static ResponseEntity<ErrorResponse> respond(HttpStatus status, String code, String message, String path,
                                                         List<ErrorResponse.FieldError> fieldErrors) {
        return ResponseEntity.status(status).body(new ErrorResponse(status.value(), status.getReasonPhrase(), code, message, path, fieldErrors));
    }

    private static String requestPath(WebRequest request) {
        return ((ServletWebRequest) request).getRequest().getRequestURI();
    }
}
