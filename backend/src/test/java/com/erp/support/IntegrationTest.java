package com.erp.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for integration tests: full application context against the shared PostgreSQL
 * container (migrated by Flyway on context start), MockMvc with all servlet filters, captured emails
 * and the {@link AuthTestSupport} fixtures.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({
    TestNotifierConfiguration.class,
    AuthTestSupport.class,
    OrgFixtures.class,
    TestTaxCodeUsage.class,
    InventoryFixtures.class,
    ProcurementFixtures.class,
    SalesFixtures.class,
    AccountingFixtures.class
})
public abstract class IntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.registerProperties(registry);
    }
}
