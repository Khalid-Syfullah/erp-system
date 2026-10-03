package com.erp.platform.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.platform.security.ActorType;
import com.erp.platform.security.AuthenticatedActor;
import com.erp.support.IntegrationTest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class CompanyScopedTransactionManagerIntegrationTest extends IntegrationTest {

    @Autowired
    DSLContext dsl;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void isTheApplicationTransactionManager() {
        assertThat(transactionManager).isInstanceOf(CompanyScopedTransactionManager.class);
    }

    @Test
    void bindsRequestContextToTheTransaction() {
        UUID company = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        RequestContext context = RequestContext.forRequest("req-12345678")
                .withActor(new AuthenticatedActor(
                        user, ActorType.USER, UUID.randomUUID(), false, Instant.now(), null, null, null, false, null))
                .withCompany(company);

        Record2<String, String> settings = CurrentContext.callWith(
                context, () -> new TransactionTemplate(transactionManager).execute(status -> readSettings()));

        assertThat(settings.value1()).isEqualTo(company.toString());
        assertThat(settings.value2()).isEqualTo(user.toString());
    }

    @Test
    void settingsAreTransactionLocalAndNeverLeakToTheNextTransaction() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        for (int i = 0; i < 20; i++) {
            CurrentContext.runWith(
                    RequestContext.forRequest("req-12345678").withCompany(UUID.randomUUID()),
                    () -> tx.executeWithoutResult(status -> readSettings()));

            Record2<String, String> withoutContext = tx.execute(status -> readSettings());

            assertThat(withoutContext.value1()).isEmpty();
            assertThat(withoutContext.value2()).isEmpty();
        }
    }

    @Test
    void readOnlyTransactionsRejectWrites() {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        assertThatThrownBy(() -> readOnly.executeWithoutResult(status -> dsl.execute("CREATE TEMP TABLE t (id int)")))
                .rootCause()
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo("25006");
    }

    private Record2<String, String> readSettings() {
        return dsl.select(
                        DSL.field("coalesce(current_setting('app.company_id', true), '')", String.class),
                        DSL.field("coalesce(current_setting('app.user_id', true), '')", String.class))
                .fetchOne();
    }
}
