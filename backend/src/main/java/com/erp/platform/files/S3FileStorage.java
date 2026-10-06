package com.erp.platform.files;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/** {@link FileStorage} on S3 or an S3-compatible store (MinIO in development and tests). */
final class S3FileStorage implements FileStorage, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(S3FileStorage.class);

    private final S3Client client;
    private final String bucket;
    private final boolean encrypt;

    S3FileStorage(FileProperties properties) {
        S3ClientBuilder builder = S3Client.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .region(Region.of(properties.region()))
                .forcePathStyle(properties.pathStyle());
        boolean custom = StringUtils.hasText(properties.endpoint());
        if (custom) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        if (StringUtils.hasText(properties.accessKey())) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        this.client = builder.build();
        this.bucket = properties.bucket();
        // AWS encrypts at rest with the bucket's default key (SSE-KMS); MinIO has no KMS locally.
        this.encrypt = !custom;
        if (properties.createBucket()) {
            try {
                client.headBucket(b -> b.bucket(bucket));
            } catch (NoSuchBucketException e) {
                client.createBucket(b -> b.bucket(bucket));
                log.info("Created object storage bucket {}", bucket);
            }
        }
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        client.putObject(
                b -> {
                    b.bucket(bucket).key(key).contentType(contentType).contentLength((long) content.length);
                    if (encrypt) {
                        b.serverSideEncryption(ServerSideEncryption.AWS_KMS);
                    }
                },
                RequestBody.fromBytes(content));
    }

    @Override
    public void put(String key, Path content, String contentType) {
        client.putObject(
                b -> {
                    b.bucket(bucket).key(key).contentType(contentType);
                    if (encrypt) {
                        b.serverSideEncryption(ServerSideEncryption.AWS_KMS);
                    }
                },
                RequestBody.fromFile(content));
    }

    @Override
    public InputStream open(String key) {
        return client.getObject(b -> b.bucket(bucket).key(key));
    }

    @Override
    public byte[] get(String key) {
        return client.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();
    }

    @Override
    public void delete(String key) {
        client.deleteObject(b -> b.bucket(bucket).key(key));
    }

    @Override
    public void close() {
        client.close();
    }
}
