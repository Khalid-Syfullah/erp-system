package com.erp.reporting.application;

import java.io.IOException;
import org.jspecify.annotations.Nullable;

/** Receives exported rows one by one, in column order (streaming exports). */
@FunctionalInterface
public interface RowSink {

    void row(@Nullable Object[] values) throws IOException;
}
