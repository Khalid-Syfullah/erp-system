package com.erp.platform.files;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * Object storage for file contents (ARCHITECTURE.md §8.6). Implementations talk to S3 or an
 * S3-compatible store; they are called outside database transactions.
 */
public interface FileStorage {

    void put(String key, byte[] content, String contentType);

    /** Streams a local file of known size to the store (large generated files, e.g. report exports). */
    void put(String key, Path content, String contentType);

    byte[] get(String key);

    /** The content as a stream; the caller closes it. */
    InputStream open(String key);

    void delete(String key);
}
