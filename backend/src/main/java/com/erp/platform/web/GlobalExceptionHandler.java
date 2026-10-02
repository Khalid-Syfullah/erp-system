package com.erp.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.TransactionException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Central mapping of exceptions to RFC 9457 problem responses (API.md §5–§7). Unexpected failures
 * are logged with their stack trace and answered with {@code INTERNAL_ERROR} plus the request ID;
 * internal details never reach the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ProblemResponses problems;
    private final DatabaseErrorTranslator databaseErrors;

    public GlobalExceptionHandler(ProblemResponses problems, DatabaseErrorTranslator databaseErrors) {
        this.problems = problems;
        this.databaseErrors = databaseErrors;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiProblem> handleApiException(ApiException ex, HttpServletRequest request) {
        return respond(ex, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiProblem> handleInvalidBody(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            violations.add(FieldViolation.atPointer(
                    JsonPointers.fromPropertyPath(error.getField()), constraintCode(error.getCode()), message(error)));
        }
        for (ObjectError error : ex.getBindingResult().getGlobalErrors()) {
            violations.add(FieldViolation.atPointer("", constraintCode(error.getCode()), message(error)));
        }
        return respond(ApiException.validationFailed("The request body is invalid.", violations), request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiProblem> handleInvalidParameters(
            HandlerMethodValidationException ex, HttpServletRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors()
                    .forEach(error -> violations.add(FieldViolation.atParameter(
                            name == null ? "" : name,
                            constraintCode(error.getCodes() == null ? null : error.getCodes()[0]),
                            error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage())));
        });
        return respond(ApiException.badRequest("One or more request parameters are invalid.", violations), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiProblem> handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        if (findCause(ex, RequestBodyLimitFilter.PayloadTooLargeException.class) != null) {
            return respond(payloadTooLarge(), request);
        }
        UnrecognizedPropertyException unknown = findCause(ex, UnrecognizedPropertyException.class);
        if (unknown != null) {
            return respond(
                    ApiException.badRequest(
                            "The request body contains an unknown property.",
                            List.of(FieldViolation.atPointer(
                                    JsonPointers.fromJacksonPath(unknown.getPath()),
                                    "UNKNOWN_PROPERTY",
                                    "is not a recognized property"))),
                    request);
        }
        MismatchedInputException mismatch = findCause(ex, MismatchedInputException.class);
        if (mismatch != null) {
            return respond(
                    ApiException.badRequest(
                            "The request body contains a value of the wrong type or format.",
                            List.of(FieldViolation.atPointer(
                                    JsonPointers.fromJacksonPath(mismatch.getPath()),
                                    "INVALID_VALUE",
                                    expectedFormat(mismatch.getTargetType())))),
                    request);
        }
        if (findCause(ex, StreamReadException.class) != null || findCause(ex, JacksonException.class) != null) {
            return respond(ApiException.badRequest("The request body is not valid JSON.", List.of()), request);
        }
        return respond(ApiException.badRequest("The request body is missing or unreadable.", List.of()), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiProblem> handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        return respond(
                ApiException.badRequest(
                        "A required parameter is missing.",
                        List.of(FieldViolation.atParameter(ex.getParameterName(), "REQUIRED", "is required"))),
                request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiProblem> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        if (EntityTags.IF_MATCH.equalsIgnoreCase(ex.getHeaderName())) {
            return respond(
                    new ApiException(
                            PlatformErrorCode.PRECONDITION_REQUIRED,
                            "This request requires an If-Match header with the resource's current ETag.",
                            List.of(FieldViolation.atParameter(
                                    EntityTags.IF_MATCH, "REQUIRED", "If-Match header is required"))),
                    request);
        }
        return respond(
                ApiException.badRequest(
                        "A required header is missing.",
                        List.of(FieldViolation.atParameter(ex.getHeaderName(), "REQUIRED", "is required"))),
                request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiProblem> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        if (ex.getParameter().hasParameterAnnotation(PathVariable.class)) {
            // Malformed identifiers are indistinguishable from unknown ones (API.md §3).
            return respond(ApiException.notFound(), request);
        }
        return respond(
                ApiException.badRequest(
                        "A request parameter has an invalid value.",
                        List.of(FieldViolation.atParameter(
                                ex.getName(), "INVALID_VALUE", expectedFormat(ex.getRequiredType())))),
                request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiProblem> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        ApiProblem problem = problems.problem(
                PlatformErrorCode.METHOD_NOT_ALLOWED,
                "The " + ex.getMethod() + " method is not supported for this resource.",
                request,
                List.of());
        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(problem.status()).header("Content-Type", ApiProblem.MEDIA_TYPE);
        if (ex.getSupportedHttpMethods() != null) {
            builder.allow(ex.getSupportedHttpMethods().toArray(org.springframework.http.HttpMethod[]::new));
        }
        return builder.body(problem);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiProblem> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return respond(
                new ApiException(
                        PlatformErrorCode.UNSUPPORTED_MEDIA_TYPE, "The request content type is not supported."),
                request);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ApiProblem> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
        return respond(
                new ApiException(
                        PlatformErrorCode.NOT_ACCEPTABLE, "The requested response content type is not available."),
                request);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<ApiProblem> handleNoHandler(Exception ex, HttpServletRequest request) {
        return respond(ApiException.notFound(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiProblem> handleMaxUpload(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return respond(payloadTooLarge(), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiProblem> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return respond(
                new ApiException(
                        PlatformErrorCode.UNAUTHENTICATED, "Authentication is required to access this resource."),
                request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiProblem> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return respond(
                new ApiException(PlatformErrorCode.FORBIDDEN, "You do not have permission to perform this action."),
                request);
    }

    /** Spring data access failures; untranslated jOOQ exceptions reach {@link #handleUnexpected}. */
    @ExceptionHandler({org.springframework.dao.DataAccessException.class, TransactionException.class})
    ResponseEntity<ApiProblem> handleDataAccess(RuntimeException ex, HttpServletRequest request) {
        ApiException translated = databaseErrors.translate(ex).orElseGet(() -> {
            log.error("Data access failure without SQL state", ex);
            return new ApiException(PlatformErrorCode.SERVICE_UNAVAILABLE, "The database is temporarily unavailable.");
        });
        return respond(translated, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiProblem> handleUnexpected(Exception ex, HttpServletRequest request) {
        return databaseErrors
                .translate(ex)
                .map(translated -> respond(translated, request))
                .orElseGet(() -> {
                    log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
                    return respond(
                            new ApiException(
                                    PlatformErrorCode.INTERNAL_ERROR,
                                    "An unexpected error occurred. Quote the request ID when reporting it."),
                            request);
                });
    }

    private ResponseEntity<ApiProblem> respond(ApiException ex, HttpServletRequest request) {
        if (log.isDebugEnabled() && ex.errorCode().status().is4xxClientError()) {
            log.debug("Request rejected code={} detail={}", ex.errorCode().code(), ex.getMessage());
        }
        return problems.entity(problems.problem(ex.errorCode(), ex.getMessage(), request, ex.violations()));
    }

    private static ApiException payloadTooLarge() {
        return new ApiException(PlatformErrorCode.PAYLOAD_TOO_LARGE, "The request body is too large.");
    }

    private static <T extends Throwable> @Nullable T findCause(Throwable ex, Class<T> type) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        return null;
    }

    private static String message(ObjectError error) {
        return error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage();
    }

    /** {@code NotBlank} → {@code NOT_BLANK}; unknown → {@code INVALID}. */
    static String constraintCode(@Nullable String constraint) {
        if (constraint == null || constraint.isBlank()) {
            return "INVALID";
        }
        String simple = constraint.substring(constraint.lastIndexOf('.') + 1);
        return simple.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    /** Describes the expected format without exposing Java type names. */
    static String expectedFormat(@Nullable Class<?> type) {
        if (type == null) {
            return "has an invalid value";
        }
        if (type == java.math.BigDecimal.class) {
            return "must be a decimal number encoded as a JSON string, e.g. \"1234.50\"";
        }
        if (type == String.class) {
            return "must be a string without control characters";
        }
        if (type == UUID.class) {
            return "must be a UUID";
        }
        if (type == LocalDate.class) {
            return "must be a date in the format YYYY-MM-DD";
        }
        if (type == OffsetDateTime.class || type == java.time.Instant.class) {
            return "must be an ISO-8601 timestamp, e.g. 2026-10-02T14:03:11Z";
        }
        if (type == Integer.class
                || type == int.class
                || type == Long.class
                || type == long.class
                || type == Short.class
                || type == short.class) {
            return "must be an integer";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "must be true or false";
        }
        if (type.isEnum()) {
            return "must be one of "
                    + Arrays.stream(type.getEnumConstants())
                            .map(Object::toString)
                            .collect(Collectors.joining(", ", "[", "]"));
        }
        if (Map.class.isAssignableFrom(type) || type.isRecord()) {
            return "must be a JSON object";
        }
        if (java.util.Collection.class.isAssignableFrom(type) || type.isArray()) {
            return "must be a JSON array";
        }
        return "has an invalid value";
    }
}
