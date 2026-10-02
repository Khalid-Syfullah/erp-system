package com.erp.platform.web.paging;

import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** The list response envelope (API.md §8.1). */
public record PageResponse<T>(List<T> data, Page page, Meta meta) {

    public record Page(int limit, @Nullable String nextCursor, boolean hasMore) {}

    /** {@code totalCount} is only computed when the client asks for it ({@code includeTotal=true}). */
    public record Meta(@Nullable Long totalCount) {}

    public PageResponse {
        data = List.copyOf(data);
    }

    public <R> PageResponse<R> map(Function<? super T, ? extends R> mapper) {
        return new PageResponse<>(data.stream().<R>map(mapper).toList(), page, meta);
    }
}
