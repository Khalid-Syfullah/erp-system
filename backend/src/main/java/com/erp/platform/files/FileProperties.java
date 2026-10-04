package com.erp.platform.files;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Object storage settings under {@code erp.files} (ARCHITECTURE.md §8.6).
 *
 * @param endpoint an S3-compatible endpoint (MinIO); empty for AWS S3
 * @param accessKey static credentials (MinIO, local); empty to use the AWS default credential chain
 * @param pathStyle path-style bucket addressing (MinIO)
 * @param createBucket create the bucket at startup when missing (development and tests only)
 * @param maxSize upload limit
 * @param allowedContentTypes the content types accepted for uploads
 */
@Validated
@ConfigurationProperties(prefix = "erp.files")
public record FileProperties(
        @Nullable String endpoint,
        @NotNull @DefaultValue("us-east-1") String region,
        @NotNull @DefaultValue("erp-files") String bucket,
        @Nullable String accessKey,
        @Nullable String secretKey,
        @DefaultValue("false") boolean pathStyle,
        @DefaultValue("false") boolean createBucket,
        @NotNull @DefaultValue("25MB") DataSize maxSize,

        @NotEmpty @DefaultValue({
            "application/pdf",
            "image/png",
            "image/jpeg",
            "text/csv",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        })
        List<String> allowedContentTypes) {}
