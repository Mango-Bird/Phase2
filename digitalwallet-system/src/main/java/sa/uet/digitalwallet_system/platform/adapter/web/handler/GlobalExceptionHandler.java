package sa.uet.digitalwallet_system.platform.adapter.web.handler;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import sa.uet.digitalwallet_system.platform.api.web.ErrorResponse;

import java.time.LocalDateTime;
import sa.uet.digitalwallet_system.platform.api.error.ModuleFailure;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleInvalidRequest(Exception ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Invalid request body or parameter", request.getRequestURI(), null);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(OptimisticLockingFailureException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, "Concurrent transaction conflict. Please try again.", request.getRequestURI(), null);
    }

    @ExceptionHandler(ModuleFailure.class)
    public ResponseEntity<ErrorResponse> handleModuleFailure(ModuleFailure ex, HttpServletRequest request) {
        HttpStatus status = switch (ex.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_STATE, INSUFFICIENT_BALANCE -> HttpStatus.BAD_REQUEST;
            case DUPLICATE_USER -> HttpStatus.CONFLICT;
            case INVALID_CREDENTIALS -> HttpStatus.UNAUTHORIZED;
        };
        if (Set.of("IDEMPOTENCY_KEY_REUSED", "REQUEST_IN_PROGRESS", "RESOURCE_BUSY", "CONCURRENT_UPDATE").contains(ex.code() == null ? "" : ex.code()))
            status = HttpStatus.CONFLICT;
        if ("PAYLOAD_TOO_LARGE".equals(ex.code())) status = HttpStatus.CONTENT_TOO_LARGE;
        var response = buildErrorResponse(status, ex.getMessage(), request.getRequestURI(), null);
        response.getBody().setCode(ex.code() != null ? ex.code() : switch (ex.kind()) {
            case INSUFFICIENT_BALANCE -> "INSUFFICIENT_BALANCE";
            case DUPLICATE_USER -> "USER_ALREADY_EXISTS";
            case INVALID_CREDENTIALS -> "UNAUTHENTICATED";
            case NOT_FOUND -> "RESOURCE_NOT_FOUND";
            default -> "INVALID_REQUEST";
        });
        response.getBody().setRetryable(ex.retryable());
        if ("REQUEST_IN_PROGRESS".equals(ex.code()))
            return ResponseEntity.status(status).header("Retry-After", "1").body(response.getBody());
        return response;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }
        var response = buildErrorResponse(HttpStatus.BAD_REQUEST, "Validation failed", request.getRequestURI(), fieldErrors);
        // Keep field errors and existing messages, while aligning @Min with application amount validation.
        boolean invalidAmount = ex.getBindingResult().getFieldErrors("amount").stream()
                .anyMatch(error -> "Min".equals(error.getCode()));
        if (invalidAmount) response.getBody().setCode("INVALID_AMOUNT");
        return response;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), request.getRequestURI(), null);
    }

    private ResponseEntity<ErrorResponse> buildErrorResponse(HttpStatus status, String message, String path, Map<String, String> errors) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(path)
                .errors(errors)
                .code(status == HttpStatus.BAD_REQUEST ? "INVALID_REQUEST" : status == HttpStatus.CONFLICT ? "CONCURRENT_UPDATE" : status == HttpStatus.NOT_FOUND ? "RESOURCE_NOT_FOUND" : status == HttpStatus.UNAUTHORIZED ? "UNAUTHENTICATED" : "INTERNAL_ERROR")
                .retryable(status == HttpStatus.CONFLICT || status.is5xxServerError())
                .traceId(UUID.randomUUID().toString())
                .build();
        return ResponseEntity.status(status).body(body);
    }
}
