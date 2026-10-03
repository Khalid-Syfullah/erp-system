package com.erp.platform.tx;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * Transaction manager that binds the {@link RequestContext} to every database transaction
 * (ARCHITECTURE.md §6.3, DATABASE.md §3).
 *
 * <p>At transaction start it executes {@code set_config('app.company_id', …, true)},
 * {@code set_config('app.user_id', …, true)} and {@code set_config('app.global_access', …, true)}
 * (the latter only for {@code @GlobalAccess} system-administration requests). The settings are transaction-local, so they can never
 * leak to the next user of a pooled connection. Row-level-security policies compare
 * {@code company_id} with {@code app.company_id}; without an active company no company-scoped row is
 * visible (fail closed). Read-only transactions are enforced with {@code SET TRANSACTION READ ONLY}.
 *
 * <p>The context is captured when the transaction begins; changing it mid-transaction has no effect.
 */
public class CompanyScopedTransactionManager extends JdbcTransactionManager {

    static final String SET_CONTEXT_SQL =
            "SELECT set_config('app.company_id', ?, true), set_config('app.user_id', ?, true),"
                    + " set_config('app.global_access', ?, true)";

    public CompanyScopedTransactionManager(DataSource dataSource) {
        super(dataSource);
        setEnforceReadOnly(true);
    }

    @Override
    protected void prepareTransactionalConnection(Connection connection, TransactionDefinition definition)
            throws SQLException {
        super.prepareTransactionalConnection(connection, definition);
        RequestContext context = CurrentContext.get().orElse(null);
        try (PreparedStatement statement = connection.prepareStatement(SET_CONTEXT_SQL)) {
            statement.setString(1, text(context == null ? null : context.companyId()));
            statement.setString(2, text(context == null ? null : context.userId()));
            statement.setString(3, context != null && context.globalAccess() ? "on" : "");
            statement.execute();
        }
    }

    private static String text(@Nullable UUID id) {
        return id == null ? "" : id.toString();
    }
}
