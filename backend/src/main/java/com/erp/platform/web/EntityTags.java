package com.erp.platform.web;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Weak ETags derived from the optimistic-locking {@code version} column and {@code If-Match}
 * evaluation (API.md §9).
 */
public final class EntityTags {

    public static final String IF_MATCH = "If-Match";
    private static final Pattern TAG = Pattern.compile("^(?:W/)?\"(\\d{1,10})\"$");

    private EntityTags() {}

    public static String forVersion(int version) {
        return "W/\"" + version + "\"";
    }

    /**
     * Ensures {@code If-Match} names the current version: missing → 428, malformed or stale → 412.
     * A comma-separated list matches if any entry matches; {@code *} is not accepted.
     */
    public static void requireMatch(@Nullable String ifMatch, int currentVersion) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(
                    PlatformErrorCode.PRECONDITION_REQUIRED,
                    "This request requires an If-Match header with the resource's current ETag.",
                    List.of(FieldViolation.atParameter(IF_MATCH, "REQUIRED", "If-Match header is required")));
        }
        for (String candidate : ifMatch.split(",")) {
            Matcher matcher = TAG.matcher(candidate.strip());
            if (matcher.matches() && Long.parseLong(matcher.group(1)) == currentVersion) {
                return;
            }
        }
        throw new ApiException(
                PlatformErrorCode.PRECONDITION_FAILED,
                "The resource was modified by someone else. Reload it and try again.",
                List.of(FieldViolation.atParameter(IF_MATCH, "STALE", "If-Match does not match the current version")));
    }
}
