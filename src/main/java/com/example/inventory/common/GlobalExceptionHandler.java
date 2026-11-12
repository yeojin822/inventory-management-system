package com.example.inventory.common;

import com.example.inventory.product.IdempotencyKeyReusedException;
import com.example.inventory.product.InsufficientStockException;
import com.example.inventory.product.ProductNameRequiredException;
import com.example.inventory.product.ProductNotFoundException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.TransactionException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String INVALID_REQUEST = "INVALID_REQUEST";
    private static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    @ExceptionHandler(ProductNotFoundException.class)
    public ProblemDetail handleProductNotFound(ProductNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(ProductNameRequiredException.class)
    public ProblemDetail handleProductNameRequired(ProductNameRequiredException e) {
        return problem(HttpStatus.BAD_REQUEST, "PRODUCT_NAME_REQUIRED", e.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ProblemDetail handleInsufficientStock(InsufficientStockException e) {
        return problem(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ProblemDetail handleIdempotencyKeyReused(IdempotencyKeyReusedException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", e.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("데이터 무결성 제약 위반", e);
        return problem(HttpStatus.CONFLICT, "CONFLICT", "데이터 제약 조건과 충돌하는 요청입니다.");
    }

    @ExceptionHandler({
            TransientDataAccessException.class,
            DataAccessResourceFailureException.class,
            CannotCreateTransactionException.class
    })
    public ResponseEntity<ProblemDetail> handleTemporarilyUnavailable(Exception e) {
        log.warn("일시적인 DB 처리 실패", e);
        return temporarilyUnavailable();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
        if (isDatabaseUnavailable(e)) {
            log.warn("DB 연결 실패", e);
            return temporarilyUnavailable();
        }
        log.error("처리되지 않은 예외", e);
        return ResponseEntity.internalServerError()
                .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR, "서버 내부 오류가 발생했습니다."));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Violation> violations = ex.getFieldErrors().stream()
                .map(error -> new Violation(error.getField(), error.getDefaultMessage()))
                .toList();
        return validationFailed(violations);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Violation> violations = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            String parameterName = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                String field = error instanceof FieldError fieldError ? fieldError.getField() : parameterName;
                violations.add(new Violation(field, error.getDefaultMessage()));
            }
        }
        return validationFailed(violations);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, "요청 본문을 읽을 수 없습니다. JSON 형식과 필드 타입(수량은 정수)을 확인해 주세요.");
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem && !hasCode(problem)) {
            problem.setProperty("code", codeOf(statusCode));
        }
        return response;
    }

    private static ResponseEntity<ProblemDetail> temporarilyUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "TEMPORARILY_UNAVAILABLE", "일시적으로 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."));
    }

    private static boolean isDatabaseUnavailable(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLTransientConnectionException) {
                return true;
            }
            if (cause instanceof SQLException sqlException && isConnectionFailure(sqlException.getSQLState())) {
                return true;
            }
            if (cause instanceof TransactionException && cause.getCause() instanceof SQLException) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConnectionFailure(String sqlState) {
        return sqlState != null && (sqlState.startsWith("08") || sqlState.startsWith("57P"));
    }

    private ResponseEntity<Object> validationFailed(List<Violation> violations) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, INVALID_REQUEST, "요청 값이 올바르지 않습니다.");
        body.setProperty("errors", violations);
        return ResponseEntity.badRequest().body(body);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }

    private static String codeOf(HttpStatusCode statusCode) {
        if (statusCode.value() == HttpStatus.BAD_REQUEST.value()) {
            return INVALID_REQUEST;
        }
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status != null && status.is4xxClientError() ? status.name() : INTERNAL_ERROR;
    }

    private static boolean hasCode(ProblemDetail problem) {
        return problem.getProperties() != null && problem.getProperties().containsKey("code");
    }

    record Violation(String field, String message) {
    }
}
