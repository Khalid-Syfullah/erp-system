package com.erp.platform.web;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Translates PostgreSQL errors into API errors by SQLSTATE and constraint name (ARCHITECTURE.md
 * §6.7). Responses never reveal SQL, table or constraint names; those are logged instead.
 */
@Component
public class DatabaseErrorTranslator {

    private static final Logger log = LoggerFactory.getLogger(DatabaseErrorTranslator.class);

    private final Map<String, ErrorCode> constraintErrors;

    @Autowired
    public DatabaseErrorTranslator(ObjectProvider<ConstraintErrorMapping> mappings) {
        this(mappings.orderedStream().toList());
    }

    DatabaseErrorTranslator(List<ConstraintErrorMapping> mappings) {
        Map<String, ErrorCode> merged = new HashMap<>();
        for (ConstraintErrorMapping mapping : mappings) {
            mapping.constraintErrors().forEach((constraint, code) -> {
                ErrorCode previous = merged.putIfAbsent(constraint, code);
                if (previous != null && previous != code) {
                    throw new IllegalStateException("Conflicting error mappings for constraint " + constraint);
                }
            });
        }
        this.constraintErrors = Map.copyOf(merged);
    }

    /** The translated exception, or empty if {@code failure} was not caused by an SQL error. */
    public Optional<ApiException> translate(Throwable failure) {
        SQLException sqlException = findSqlException(failure);
        if (sqlException == null || sqlException.getSQLState() == null) {
            return Optional.empty();
        }
        String sqlState = sqlException.getSQLState();
        String constraint = constraintName(sqlException);
        ApiException translated = translate(sqlState, constraint, sqlException.getMessage());
        if (translated.errorCode().status().is5xxServerError()) {
            log.error("Database error sqlState={} constraint={}", sqlState, constraint, failure);
        } else if ("42501".equals(sqlState)) {
            // Row-level security or privilege violation: application code tried to touch data outside
            // its company or permissions. Must never happen; alert (SECURITY.md §4.4, layer 6).
            log.error("Database privilege/RLS violation sqlState={} constraint={}", sqlState, constraint, failure);
        } else {
            log.info("Database constraint violation sqlState={} constraint={}", sqlState, constraint);
        }
        return Optional.of(translated);
    }

    ApiException translate(String sqlState, @Nullable String constraint, @Nullable String message) {
        ErrorCode mapped = constraint == null ? null : constraintErrors.get(constraint);
        return switch (sqlState) {
            case "23505" ->
                new ApiException(
                        mapped != null ? mapped : PlatformErrorCode.CONFLICT,
                        "A record with the same unique value already exists.");
            case "23503" -> {
                boolean referencedRowInUse = message != null && message.contains("update or delete on table");
                yield referencedRowInUse
                        ? new ApiException(
                                mapped != null ? mapped : PlatformErrorCode.RESOURCE_IN_USE,
                                "The record is referenced by other records and cannot be changed or deleted.")
                        : new ApiException(
                                mapped != null ? mapped : PlatformErrorCode.VALIDATION_FAILED,
                                "A referenced record does not exist.");
            }
            case "23502", "23514" ->
                new ApiException(
                        mapped != null ? mapped : PlatformErrorCode.VALIDATION_FAILED,
                        "The data violates a validation rule.");
            case "23P01" ->
                new ApiException(
                        mapped != null ? mapped : PlatformErrorCode.CONFLICT,
                        "The data conflicts with an existing record.");
            case "40001", "40P01", "55P03" ->
                new ApiException(PlatformErrorCode.RESOURCE_BUSY, "The resource is busy. Please retry the request.");
            case "57014" ->
                new ApiException(
                        PlatformErrorCode.SERVICE_UNAVAILABLE, "The operation took too long and was cancelled.");
            case "42501" -> ApiException.notFound();
            default -> {
                if (sqlState.startsWith("22")) {
                    yield new ApiException(PlatformErrorCode.VALIDATION_FAILED, "The data has an invalid value.");
                }
                if (sqlState.startsWith("08") || sqlState.startsWith("53") || sqlState.startsWith("57P")) {
                    yield new ApiException(
                            PlatformErrorCode.SERVICE_UNAVAILABLE, "The database is temporarily unavailable.");
                }
                yield new ApiException(PlatformErrorCode.INTERNAL_ERROR, "An unexpected error occurred.");
            }
        };
    }

    private static @Nullable SQLException findSqlException(Throwable failure) {
        SQLException found = null;
        for (Throwable t = failure; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                found = sql;
                if (sql.getSQLState() != null) {
                    return sql;
                }
            }
        }
        return found;
    }

    private static @Nullable String constraintName(SQLException exception) {
        return Optional.of(exception)
                .filter(PSQLException.class::isInstance)
                .map(e -> ((PSQLException) e).getServerErrorMessage())
                .map(ServerErrorMessage::getConstraint)
                .orElse(null);
    }
}
