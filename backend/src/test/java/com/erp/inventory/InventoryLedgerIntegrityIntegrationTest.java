package com.erp.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.inventory.application.InventoryInvariantCheck;
import com.erp.support.IntegrationTest;
import com.erp.support.InventoryFixtures;
import com.erp.support.InventoryFixtures.Setup;
import com.erp.support.TestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The database itself protects the stock history (DATABASE.md §9): even the application role cannot
 * rewrite the ledger or a posted movement, and the nightly invariant check detects drift.
 */
class InventoryLedgerIntegrityIntegrationTest extends IntegrationTest {

    @Autowired
    InventoryFixtures inv;

    @Autowired
    InventoryInvariantCheck invariants;

    private Setup s;
    private UUID movement;

    @BeforeEach
    void setUp() throws Exception {
        s = inv.setup();
        movement = inv.opening(s, s.variant(), s.warehouse().stock(), "5", "2");
    }

    @Test
    void theLedgerIsAppendOnlyForTheApplicationRole() throws Exception {
        assertThat(failureAsApp("UPDATE inventory.inventory_transactions SET quantity_base = 50 WHERE movement_id = '"
                                + movement + "'")
                        .getSQLState())
                .isEqualTo("42501");
        assertThat(failureAsApp("DELETE FROM inventory.inventory_transactions WHERE movement_id = '" + movement + "'")
                        .getSQLState())
                .isEqualTo("42501");
        // Not even the table owner may change or remove ledger rows (trigger).
        try (Connection owner = TestDatabase.superuserConnection(TestDatabase.DATABASE);
                Statement statement = owner.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate(
                            "UPDATE inventory.inventory_transactions SET quantity_base = 50 WHERE movement_id = '"
                                    + movement + "'"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(
                            "DELETE FROM inventory.inventory_transactions WHERE movement_id = '" + movement + "'"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void postedMovementsAndTheirLinesCannotBeChanged() throws Exception {
        assertThat(constraint(
                        failureAsApp("UPDATE inventory.stock_movements SET notes = 'x' WHERE id = '" + movement + "'")))
                .isEqualTo("ck_stock_movements__immutable");
        assertThat(constraint(
                        failureAsApp("UPDATE inventory.stock_movement_lines SET quantity = 1 WHERE movement_id = '"
                                + movement + "'")))
                .isEqualTo("ck_stock_movement_lines__immutable");
        assertThat(constraint(failureAsApp("DELETE FROM inventory.stock_movements WHERE id = '" + movement + "'")))
                .isEqualTo("ck_stock_movements__immutable");
    }

    @Test
    void stockCanNeverBeNegativeInTheDatabase() throws Exception {
        assertThat(constraint(failureAsApp(
                        "UPDATE inventory.stock_balances SET on_hand = -1 WHERE variant_id = '" + s.variant() + "'")))
                .isEqualTo("ck_stock_balances__on_hand");
        assertThat(constraint(
                        failureAsApp("UPDATE inventory.warehouse_stock SET reserved = on_hand + 1 WHERE variant_id = '"
                                + s.variant() + "'")))
                .isEqualTo("ck_warehouse_stock__reserved_covered");
        assertThat(constraint(failureAsApp("UPDATE inventory.item_valuations SET quantity_base = 0 WHERE variant_id = '"
                        + s.variant() + "'")))
                .isEqualTo("ck_item_valuations__no_residual_value");
    }

    @Test
    void theInvariantCheckDetectsDrift() throws Exception {
        assertThat(invariants.check(s.company()).clean()).isTrue();

        // Simulate a bug that changed a balance without a ledger row.
        try (Connection owner = TestDatabase.superuserConnection(TestDatabase.DATABASE);
                Statement statement = owner.createStatement()) {
            statement.executeUpdate("UPDATE inventory.stock_balances SET on_hand = on_hand + 1 WHERE variant_id = '"
                    + s.variant() + "'");
            statement.executeUpdate(
                    "UPDATE inventory.item_valuations SET total_value_base = total_value_base + 1 WHERE variant_id = '"
                            + s.variant() + "'");
        }

        InventoryInvariantCheck.Report report = invariants.check(s.company());
        assertThat(report.clean()).isFalse();
        assertThat(report.balances()).isEqualTo(1);
        assertThat(report.valuations()).isEqualTo(1);
    }

    /**
     * Runs one statement as the application role in the setup's company context (a transaction of its
     * own: the context settings are transaction-local) and returns the error it must raise.
     */
    private SQLException failureAsApp(String sql) throws SQLException {
        try (Connection app = TestDatabase.connectAs("erp_app", TestDatabase.APP_PASSWORD)) {
            app.setAutoCommit(false);
            try (PreparedStatement context = app.prepareStatement(
                    "SELECT set_config('app.company_id', ?, true), set_config('app.user_id', ?, true)")) {
                context.setString(1, s.company().toString());
                context.setString(2, s.user().id().toString());
                context.execute();
            }
            try {
                int rows = execute(app, sql);
                throw new AssertionError("Expected a failure, but " + rows + " row(s) changed: " + sql);
            } catch (SQLException expected) {
                return expected;
            } finally {
                app.rollback();
            }
        }
    }

    private static String constraint(SQLException e) {
        return e instanceof PSQLException p && p.getServerErrorMessage() != null
                ? p.getServerErrorMessage().getConstraint()
                : null;
    }

    private static int execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }
}
