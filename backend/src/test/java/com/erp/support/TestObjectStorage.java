package com.erp.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * One S3-compatible object store per test JVM (Adobe S3Mock; ADR-039). The application creates its
 * bucket at startup ({@code erp.files.create-bucket}).
 */
public final class TestObjectStorage {

    private static final GenericContainer<?> CONTAINER = new GenericContainer<>(
                    System.getProperty("erp.test.s3-image", "adobe/s3mock:4.7.0"))
            .withExposedPorts(9090)
            .waitingFor(Wait.forHttp("/").forPort(9090).forStatusCode(200));

    static {
        CONTAINER.start();
    }

    private TestObjectStorage() {}

    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("erp.files.endpoint", () -> "http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(9090));
        registry.add("erp.files.bucket", () -> "erp-test");
        registry.add("erp.files.access-key", () -> "test");
        registry.add("erp.files.secret-key", () -> "test");
        registry.add("erp.files.path-style", () -> "true");
        registry.add("erp.files.create-bucket", () -> "true");
    }
}
