package com.example.payment.presentation.error;

import com.example.payment.application.service.AmountMismatchException;
import com.example.payment.application.service.IdempotencyConflictException;
import com.example.payment.application.service.MarketplaceCheckoutException;
import com.example.payment.domain.exception.DomainException;
import com.example.payment.infrastructure.tenancy.TenantContext;
import com.example.payment.infrastructure.util.ResourceReservationInfrastructureException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AmountMismatchException.class)
    ProblemDetail amountMismatch(AmountMismatchException exception) {
        return problem(HttpStatus.CONFLICT, "AMOUNT_MISMATCH", exception.getMessage(), false);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail idempotencyConflict(IdempotencyConflictException exception) {
        return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", exception.getMessage(), false);
    }

    @ExceptionHandler(MarketplaceCheckoutException.class)
    ProblemDetail marketplace(MarketplaceCheckoutException exception) {
        return problem(exception.getStatus(), "MARKETPLACE_REQUEST_REJECTED", exception.getMessage(), false);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail responseStatus(ResponseStatusException exception) {
        String code = exception.getStatusCode().value() == 401
                ? "AUTHENTICATION_REQUIRED"
                : exception.getStatusCode().value() == 403 ? "ACCESS_DENIED" : "REQUEST_REJECTED";
        return problem(HttpStatus.valueOf(exception.getStatusCode().value()), code,
                exception.getReason(), false);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "요청 값이 올바르지 않습니다.", false);
        detail.setProperty("errors", errors);
        return detail;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail constraintViolation(ConstraintViolationException exception) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", exception.getMessage(), false);
    }

    @ExceptionHandler(ResourceReservationInfrastructureException.class)
    ProblemDetail reservationInfrastructure(ResourceReservationInfrastructureException exception) {
        log.error("Reservation infrastructure failure: correlationId={}", TenantContext.getCorrelationId(), exception);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "RESERVATION_INFRASTRUCTURE_UNAVAILABLE",
                "재고 서비스를 일시적으로 사용할 수 없습니다.", true);
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail database(DataAccessException exception) {
        log.error("Database failure: correlationId={}", TenantContext.getCorrelationId(), exception);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE",
                "데이터 서비스를 일시적으로 사용할 수 없습니다.", true);
    }

    @ExceptionHandler(DomainException.class)
    ProblemDetail domain(DomainException exception) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "DOMAIN_RULE_VIOLATION", exception.getMessage(), false);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail noResource(NoResourceFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "요청한 리소스를 찾을 수 없습니다.", false);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ProblemDetail methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "지원하지 않는 요청 방식입니다.", false);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail illegalArgument(IllegalArgumentException exception) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), false);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception) {
        log.error("Unexpected API failure: correlationId={}", TenantContext.getCorrelationId(), exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "요청 처리 중 예기치 않은 오류가 발생했습니다.", true);
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail, boolean retryable) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status,
                detail == null || detail.isBlank() ? status.getReasonPhrase() : detail);
        problem.setType(URI.create("https://api.everysale.local/problems/" + code.toLowerCase()));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("correlationId", TenantContext.getCorrelationId());
        problem.setProperty("retryable", retryable);
        return problem;
    }
}
