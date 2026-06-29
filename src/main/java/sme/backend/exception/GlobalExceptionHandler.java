package sme.backend.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ---------------------------------------------------------------
    // 404 Not Found
    // ---------------------------------------------------------------
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex,
            HttpServletRequest request) {
        return buildError(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
    }

    // ---------------------------------------------------------------
    // 409 Conflict - Tồn kho không đủ
    // ---------------------------------------------------------------
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ApiError> handleInsufficientStock(InsufficientStockException ex,
            HttpServletRequest request) {
        return buildError(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", ex.getMessage(), request);
    }

    // ---------------------------------------------------------------
    // 409 Optimistic Locking - Race Condition
    // ---------------------------------------------------------------
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException ex,
            HttpServletRequest request) {
        log.warn("Optimistic locking conflict: {}", ex.getMessage());
        return buildError(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "Dữ liệu đã bị thay đổi bởi người dùng khác. Vui lòng thử lại.", request);
    }

    // ---------------------------------------------------------------
    // 400 Business Logic
    // ---------------------------------------------------------------
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex,
            HttpServletRequest request) {
        log.error("Business error at {}: code={}, message={}", request.getRequestURI(), ex.getCode(), ex.getMessage());
        return buildError(HttpStatus.BAD_REQUEST, ex.getCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArg(IllegalArgumentException ex,
            HttpServletRequest request) {
        return buildError(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex,
            HttpServletRequest request) {
        return buildError(HttpStatus.BAD_REQUEST, "INVALID_STATE", ex.getMessage(), request);
    }

    // ---------------------------------------------------------------
    // 400 Validation
    // ---------------------------------------------------------------
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            fieldErrors.put(fieldName, error.getDefaultMessage());
        });
        ApiError apiError = new ApiError(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_FAILED",
                "Dữ liệu đầu vào không hợp lệ",
                request.getRequestURI(),
                fieldErrors);
        log.error("Validation failed at {}: {}", request.getRequestURI(), fieldErrors);
        return ResponseEntity.badRequest().body(apiError);
    }

    // ---------------------------------------------------------------
    // 401 / 403
    // ---------------------------------------------------------------
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException ex,
            HttpServletRequest request) {
        return buildError(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "Tên đăng nhập hoặc mật khẩu không đúng", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex,
            HttpServletRequest request) {
        return buildError(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "Bạn không có quyền truy cập tài nguyên này", request);
    }

    // ---------------------------------------------------------------
    // 500 Internal
    // ---------------------------------------------------------------
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneral(Exception ex, HttpServletRequest request) {
        log.error("╔══ UNHANDLED EXCEPTION ══════════════════════════════════════╗");
        log.error("║ URI  : {}", request.getRequestURI());
        log.error("║ Type : {}", ex.getClass().getName());
        log.error("║ Msg  : {}", ex.getMessage());
        if (ex.getCause() != null) {
            log.error("║ Cause: {} - {}", ex.getCause().getClass().getName(), ex.getCause().getMessage());
        }
        log.error("╚═════════════════════════════════════════════════════════════╝", ex);
        String detailedMessage = ex.getClass().getSimpleName() + ": " + ex.getMessage()
                + (ex.getCause() != null ? " → Caused by: " + ex.getCause().getMessage() : "");
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                detailedMessage, request);
    }

    // ---------------------------------------------------------------
    // Helper
    // ---------------------------------------------------------------
    private ResponseEntity<ApiError> buildError(HttpStatus status, String code,
            String message, HttpServletRequest request) {
        ApiError error = new ApiError(
                Instant.now(), status.value(), code, message,
                request.getRequestURI(), null);
        return ResponseEntity.status(status).body(error);
    }

    public record ApiError(
            Instant timestamp,
            int status,
            String code,
            String message,
            String path,
            Map<String, String> fieldErrors) {
    }
}
