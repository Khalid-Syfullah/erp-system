package com.erp.platform.web.paging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * A validated list request (API.md §8): page size, sort, typed filters, quick search, and the decoded
 * keyset position of the cursor (one value per sort key plus the tiebreaker), if any.
 */
public record ListQuery(
        String listName,
        int limit,
        List<SortOrder> sort,
        List<FilterCriterion> filters,
        @Nullable String search,
        @Nullable List<@Nullable String> after,
        boolean includeTotal) {

    public ListQuery {
        sort = List.copyOf(sort);
        filters = List.copyOf(filters);
        after = after == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(after));
    }

    /**
     * Identifies the result set independently of position and page size. A cursor is only valid for
     * the fingerprint it was issued with, so it cannot be replayed against other filters or sorts.
     */
    public byte[] fingerprint() {
        String canonical = listName
                + "|" + sort.stream().map(SortOrder::canonical).collect(Collectors.joining(","))
                + "|"
                + filters.stream()
                        .map(FilterCriterion::canonical)
                        .sorted(Comparator.naturalOrder())
                        .collect(Collectors.joining(","))
                + "|" + (search == null ? "" : search);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(digest, CursorCodec.FINGERPRINT_BYTES);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
