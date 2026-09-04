package com.supremebilliardshall.billiards_hall_system.exception;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    // Resource is not found
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<APIResponse<Object>> handleNotFound(ResourceNotFoundException ex) {
        APIResponse<Object> response = new APIResponse<>(null, ex.getMessage(), false);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    // Duplicate Resource
    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<APIResponse<Object>> handleDuplicate(DuplicateResourceException ex) {
        APIResponse<Object> response = new APIResponse<>(null, ex.getMessage(), false);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    // Resource is in use and cannot be changed
    @ExceptionHandler(ResourceInUseException.class)
    public ResponseEntity<APIResponse<Object>> handleInUse(ResourceInUseException ex) {
        APIResponse<Object> response = new APIResponse<>(null, ex.getMessage(), false);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    // A request that conflicts with the current state of the domain.
    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<APIResponse<Object>> handleBusinessRule(BusinessRuleException ex) {
        APIResponse<Object> response = new APIResponse<>(null, ex.getMessage(), false);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    // A reference already seen in this branch. The client gets a code so it can offer
    // "record anyway" rather than treating it as a hard failure.
    @ExceptionHandler(DuplicateReferenceException.class)
    public ResponseEntity<APIResponse<Object>> handleDuplicateReference(DuplicateReferenceException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(APIResponse.failure(ex.getMessage(), DuplicateReferenceException.CODE));
    }

    // Two counter tabs settling the same bill. Coded too, because the client must reload
    // rather than simply retry.
    @ExceptionHandler(StaleBillVersionException.class)
    public ResponseEntity<APIResponse<Object>> handleStaleBillVersion(StaleBillVersionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(APIResponse.failure(ex.getMessage(), StaleBillVersionException.CODE));
    }

    // Hibernate's own optimistic lock, if two transactions raced past the version check.
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<APIResponse<Object>> handleOptimisticLock(
            org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(APIResponse.failure("This bill was changed by someone else. Reload it and try again.",
                        StaleBillVersionException.CODE));
    }

    // A global admin acting with no branch selected. Never resolved by guessing.
    @ExceptionHandler(BranchNotSelectedException.class)
    public ResponseEntity<APIResponse<Object>> handleBranchNotSelected(BranchNotSelectedException ex) {
        APIResponse<Object> response = new APIResponse<>(null, ex.getMessage(), false);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<APIResponse<Object>> handleValidationErrors(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .reduce((msg1, msg2) -> msg1 + "; " + msg2)
                .orElse("Invalid input");

        APIResponse<Object> response = new APIResponse<>(null, errors, false);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    // Keys are the real constraint and index names from docs/schema.sql. The database is
    // what enforces these, so the message is chosen here rather than pre-checked in a service.
    private static final Map<String, String> UNIQUE_CONSTRAINT_MESSAGES = Map.ofEntries(
            Map.entry("branch_code_key", "Branch code already exists"),
            Map.entry("app_user_username_key", "Username already exists"),
            Map.entry("product_category_name_key", "Category name already exists"),
            Map.entry("product_name_key", "Product name already exists"),
            Map.entry("pool_table_name_key", "Table name already exists"),
            Map.entry("customer_type_name_key", "Customer type name already exists"),
            Map.entry("pool_table_rate_no_overlap", "That table already has a rate in force for this period"),
            Map.entry("pool_table_rate_period_chk", "A rate period must end after it starts"),
            Map.entry("table_session_one_open_per_table_key", "That table already has an open session"),
            Map.entry("payment_one_per_bill_key", "That bill has already been paid"),
            Map.entry("payment_idempotency_key", "That checkout has already been recorded"),
            Map.entry("cash_count_day_key", "The drawer has already been counted for that business day"),
            Map.entry("receipt_bill_key", "That bill already has a receipt")
    );

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<APIResponse<Object>> handleDataIntegrityViolation(
            DataIntegrityViolationException ex) {

        String message = "That change conflicts with an existing record";
        Throwable rootCause = ex.getRootCause();
        if (rootCause != null && rootCause.getMessage() != null) {
            String causeMessage = rootCause.getMessage().toLowerCase();
            for (Map.Entry<String, String> entry : UNIQUE_CONSTRAINT_MESSAGES.entrySet()) {
                if (causeMessage.contains(entry.getKey())) {
                    message = entry.getValue();
                    break;
                }
            }
        }

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new APIResponse<>(null, message, false));
    }

    // Bigger than the container will even read into memory, so the upload never reaches the
    // service that would name the real limit. Without this it is a 500.
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<APIResponse<Object>> handleUploadTooLarge(MaxUploadSizeExceededException ex) {

        return ResponseEntity.badRequest()
                .body(new APIResponse<>(null, "That file is too large to upload. The limit is 2 MB.", false));
    }

    // A body that ran past the ceiling RequestSizeLimitFilter imposes. The message converter
    // wraps whatever the input stream threw, so the cause is what identifies it. Anything else
    // that could not be read is a malformed body — the client's error either way, and answered
    // in the envelope rather than through the container's default error page.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<APIResponse<Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {

        if (NestedExceptionUtils.getMostSpecificCause(ex) instanceof RequestBodyTooLargeException) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(APIResponse.failure("That request is too large."));
        }
        return ResponseEntity.badRequest()
                .body(APIResponse.failure("The request body could not be read."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<APIResponse<Object>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {

        return ResponseEntity.badRequest()
                .body(new APIResponse<>(null, "Invalid ID format", false));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<APIResponse<Object>> handleConstraintViolation(
            ConstraintViolationException ex) {

        String message = ex.getConstraintViolations()
                .iterator().next().getMessage();

        return ResponseEntity.badRequest()
                .body(new APIResponse<>(null, message, false));
    }

    // Bad credentials on login. Everything else unauthenticated is stopped earlier by the
    // security entry point.
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<APIResponse<Object>> handleAuthentication(AuthenticationException ex) {

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new APIResponse<>(null, "Invalid username or password", false));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<APIResponse<Object>> handleAccessDenied(AccessDeniedException ex) {

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new APIResponse<>(null, "You are not allowed to perform this action", false));
    }

    // Login throttled after too many failures. 429 so the client can back off rather than
    // hammer, and the message names the wait rather than looking like a wrong password.
    @ExceptionHandler(TooManyLoginAttemptsException.class)
    public ResponseEntity<APIResponse<Object>> handleTooManyLoginAttempts(TooManyLoginAttemptsException ex) {

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new APIResponse<>(null, ex.getMessage(), false));
    }
}
