package com.erp.platform.files;

/**
 * Object storage for file contents (ARCHITECTURE.md §8.6). Implementations talk to S3 or an
 * S3-compatible store; they are called outside database transactions.
 */
public interface FileStorage {

    void put(String key, byte[] content, String contentType);

    byte[] get(String key);

    void delete(String key);
}
