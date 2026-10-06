package com.erp.reporting.persistence;

import com.erp.platform.tx.CompanyScopedTransactionManager;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The read-only reporting connection pool (DATABASE.md §2.7, ADR-040): logs in as
 * {@code erp_reporting}, which may only SELECT the published {@code v_rpt_*} views (and, for their
 * security-invoker evaluation, the columns behind them). Transactions bind the request's company
 * like the application's own ({@link CompanyScopedTransactionManager}), so row-level security
 * applies, and run READ ONLY with a statement timeout. The URL may point at a read replica.
 *
 * <p>Deliberately not a {@code DataSource} bean: the application's DataSource, transaction manager
 * and jOOQ context stay the only auto-configured ones.
 */
public final class ReportingDatabase implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final CompanyScopedTransactionManager transactions;
    private final DSLContext dsl;

    ReportingDatabase(HikariDataSource dataSource) {
        this.dataSource = dataSource;
        this.transactions = new CompanyScopedTransactionManager(dataSource);
        this.dsl = DSL.using(new DefaultConfiguration()
                .set(SQLDialect.POSTGRES)
                .set(new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(dataSource))));
    }

    /** Runs {@code work} in a read-only transaction of the current company with a statement timeout. */
    public <T> T read(Duration timeout, Function<DSLContext, T> work) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(status -> {
            // SET takes no bind parameters; the value is a number of milliseconds computed here.
            dsl.execute("SET LOCAL statement_timeout = " + Math.max(1, timeout.toMillis()));
            return work.apply(dsl);
        });
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
