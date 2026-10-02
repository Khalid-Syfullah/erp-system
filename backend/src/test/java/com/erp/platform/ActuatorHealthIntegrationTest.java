package com.erp.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.support.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Health probes and metrics on the separate management port, over real HTTP (ARCHITECTURE.md §6.8). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@ActiveProfiles("test")
class ActuatorHealthIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.registerProperties(registry);
    }

    @LocalServerPort
    int serverPort;

    @LocalManagementPort
    int managementPort;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void livenessAndReadinessAreUpWithoutDetails() throws Exception {
        assertThat(managementPort).isNotEqualTo(serverPort);
        for (String probe :
                new String[] {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            HttpResponse<String> response = get(managementPort, probe);
            assertThat(response.statusCode()).as(probe).isEqualTo(200);
            assertThat(response.body())
                    .as(probe)
                    .contains("\"status\":\"UP\"")
                    .doesNotContain("components")
                    .doesNotContain("details");
        }
    }

    @Test
    void prometheusMetricsAreExposedOnManagementPort() throws Exception {
        // The readiness group checks the database, which starts the connection pool and its metrics.
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode())
                .isEqualTo(200);
        HttpResponse<String> response = get(managementPort, "/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("jvm_memory_used_bytes", "hikaricp_connections");
    }

    @Test
    void actuatorIsNotReachableOnThePublicPort() throws Exception {
        assertThat(get(serverPort, "/actuator/health").statusCode()).isIn(401, 404);
    }

    @Test
    void publicPortServesTheApiWithProblemResponses() throws Exception {
        HttpResponse<String> response = get(serverPort, "/api/v1/reference/currencies");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
