package com.erp.accounting;

import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.support.AccountingFixtures;
import com.erp.support.AccountingFixtures.Books;
import com.erp.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ledger's guarantees hold in the database itself, whatever the application does (DATABASE.md
 * §8.1): posted entries and their lines cannot be changed or deleted (ACC-3), a posted entry must
 * balance at commit (ACC-1, deferred trigger), nothing is posted into a period that does not take
 * postings (ACC-4), control accounts take no manual lines and an event is booked once (ACC-5).
 * Each check runs raw SQL as the application role.
 */
class LedgerIntegrityIntegrationTest extends IntegrationTest {

    @Autowired
    AccountingFixtures acc;

    @Autowired
    DSLContext dsl;

    @Autowired
    TransactionTemplate tx;

    private Books b;
    private UUID posted;

    @BeforeEach
    void setUp() throws Exception {
        b = acc.setup();
        posted = id(
                acc.create(
                        b,
                        b.session(),
                        "/journal-entries",
                        acc.entry(b, b.today(), Map.of("6000", "50", "3000", "-50"))),
                201);
        expect(acc.action(b, b.session(), "/journal-entries/" + posted + "/post", 0, "post-" + posted, null), 200);
    }

    @Test
    void postedEntriesAndLinesCannotBeChanged() {
        assertRejected(
                d -> d.execute("UPDATE accounting.journal_entries SET description = 'tampered' WHERE id = ?", posted),
                "ck_journal_entries__immutable");
        assertRejected(
                d -> d.execute(
                        "UPDATE accounting.journal_entries SET total_debit = 1, total_credit = 1 WHERE id = ?", posted),
                "ck_journal_entries__immutable");
        assertRejected(
                d -> d.execute("DELETE FROM accounting.journal_entries WHERE id = ?", posted),
                "ck_journal_entries__immutable");
        assertRejected(
                d -> d.execute(
                        "UPDATE accounting.journal_lines SET debit = debit + 1 WHERE journal_entry_id = ?", posted),
                "ck_journal_lines__immutable");
        assertRejected(
                d -> d.execute("DELETE FROM accounting.journal_lines WHERE journal_entry_id = ?", posted),
                "ck_journal_lines__immutable");
        assertRejected(
                d -> d.execute(
                        "INSERT INTO accounting.journal_lines (company_id, journal_entry_id, line_no, account_id, debit,"
                                + " credit, currency_code, amount_currency, entry_date, period_id)"
                                + " SELECT company_id, id, 99, ?, 1, 0, 'USD', 1, entry_date, period_id"
                                + " FROM accounting.journal_entries WHERE id = ?",
                        b.account("6000"),
                        posted),
                "ck_journal_lines__immutable");
    }

    @Test
    void anUnbalancedEntryCannotBeCommittedAsPosted() {
        assertRejected(
                d -> {
                    UUID id = draftHeader(d, "100.00", "100.00");
                    line(d, id, 1, "6000", "100.00", "0");
                    line(d, id, 2, "3000", "0", "99.99");
                    post(d, id);
                },
                "ck_journal_balanced");
        // Header totals must match the lines, and one line is not an entry.
        assertRejected(
                d -> {
                    UUID id = draftHeader(d, "10.00", "10.00");
                    line(d, id, 1, "6000", "20.00", "0");
                    line(d, id, 2, "3000", "0", "20.00");
                    post(d, id);
                },
                "ck_journal_balanced");
        // A posted header carries equal totals.
        assertRejected(
                d -> {
                    UUID id = draftHeader(d, "10.00", "9.00");
                    post(d, id);
                },
                "ck_journal_entries__balanced");
        // A line has exactly one non-negative side.
        assertRejected(d -> line(d, draftHeader(d, "0", "0"), 1, "6000", "5.00", "5.00"), "ck_journal_lines__one_side");
        assertRejected(d -> line(d, draftHeader(d, "0", "0"), 1, "6000", "-5.00", "0"), "ck_journal_lines__one_side");
        // A balanced entry passes the same path.
        inCompany(d -> {
            UUID id = draftHeader(d, "12.34", "12.34");
            line(d, id, 1, "6000", "12.34", "0");
            line(d, id, 2, "3000", "0", "12.34");
            post(d, id);
        });
    }

    @Test
    void nothingIsPostedIntoAPeriodThatTakesNoPostings() {
        inCompany(d -> d.execute(
                "UPDATE accounting.periods SET status = 'CLOSED', closed_at = now() WHERE company_id = ?"
                        + " AND current_date BETWEEN start_date AND end_date",
                b.company()));
        assertRejected(
                d -> {
                    UUID id = draftHeader(d, "1.00", "1.00");
                    line(d, id, 1, "6000", "1.00", "0");
                    line(d, id, 2, "3000", "0", "1.00");
                    post(d, id);
                },
                "ck_journal_entries__period_open");
        inCompany(d -> d.execute(
                "UPDATE accounting.periods SET status = 'SOFT_CLOSED', closed_at = NULL WHERE company_id = ?"
                        + " AND current_date BETWEEN start_date AND end_date",
                b.company()));
        // Soft-closed: only with the flag the posting service sets after checking the permission.
        assertRejected(
                d -> {
                    UUID id = draftHeader(d, "1.00", "1.00");
                    line(d, id, 1, "6000", "1.00", "0");
                    line(d, id, 2, "3000", "0", "1.00");
                    post(d, id);
                },
                "ck_journal_entries__period_open");
        inCompany(d -> {
            d.execute("SELECT set_config('app.allow_soft_closed', 'on', true)");
            UUID id = draftHeader(d, "1.00", "1.00");
            line(d, id, 1, "6000", "1.00", "0");
            line(d, id, 2, "3000", "0", "1.00");
            post(d, id);
        });
    }

    @Test
    void controlAccountsTakeNoManualLinesAndInactiveAccountsNone() throws Exception {
        assertRejected(
                d -> line(d, draftHeader(d, "0", "0"), 1, "1100", "5.00", "0"), "ck_journal_lines__control_account");
        UUID travel = acc.account(b, "6950", "EXPENSE", "OPERATING_EXPENSE");
        expect(acc.action(b, b.session(), "/accounts/" + travel + "/deactivate", 0, null, null), 200);
        assertRejected(
                d -> line(d, draftHeader(d, "0", "0"), 1, "6950", "5.00", "0"), "ck_journal_lines__account_postable");
        // System accounts stay active.
        assertRejected(
                d -> d.execute("UPDATE accounting.accounts SET status = 'INACTIVE' WHERE id = ?", b.account("1100")),
                "ck_accounts__system_active");
    }

    @Test
    void anEventIsBookedOnce() {
        UUID eventId = UUID.randomUUID();
        assertRejected(
                d -> {
                    d.execute(
                            "UPDATE accounting.journal_entries SET source_event_id = ? WHERE id = ?",
                            eventId,
                            draftEntry());
                    d.execute(
                            "UPDATE accounting.journal_entries SET source_event_id = ? WHERE id = ?",
                            eventId,
                            draftEntry());
                },
                "uq_journal_entries__company_id_source_event_id");
        assertRejected(
                d -> d.execute(
                        "INSERT INTO accounting.journal_entries (company_id, journal_id, entry_date, period_id, entry_type,"
                                + " description, currency_code, source_module, source_type, source_id)"
                                + " SELECT company_id, journal_id, entry_date, period_id, 'SYSTEM', 'twice', 'USD',"
                                + " 'test', 'DOC', ? FROM accounting.journal_entries WHERE id = ?"
                                + " UNION ALL SELECT company_id, journal_id, entry_date, period_id, 'SYSTEM', 'twice',"
                                + " 'USD', 'test', 'DOC', ? FROM accounting.journal_entries WHERE id = ?",
                        eventId,
                        posted,
                        eventId,
                        posted),
                "uq_journal_entries__system_source");
    }

    // ------------------------------------------------------------------------------ helpers

    private UUID draftEntry() {
        UUID[] id = new UUID[1];
        inCompany(d -> id[0] = draftHeader(d, "0", "0"));
        return id[0];
    }

    private UUID draftHeader(DSLContext d, String debit, String credit) {
        return d.fetchSingle(
                        "INSERT INTO accounting.journal_entries (company_id, journal_id, entry_date, period_id, entry_type,"
                                + " description, currency_code, total_debit, total_credit)"
                                + " SELECT company_id, journal_id, entry_date, period_id, 'MANUAL', 'raw', 'USD', ?::numeric,"
                                + " ?::numeric FROM accounting.journal_entries WHERE id = ? RETURNING id",
                        debit,
                        credit,
                        posted)
                .get(0, UUID.class);
    }

    private void line(DSLContext d, UUID entry, int no, String code, String debit, String credit) {
        d.execute(
                "INSERT INTO accounting.journal_lines (company_id, journal_entry_id, line_no, account_id, debit, credit,"
                        + " currency_code, amount_currency, entry_date, period_id)"
                        + " SELECT company_id, id, ?, ?, ?::numeric, ?::numeric, 'USD', ?::numeric - ?::numeric,"
                        + " entry_date, period_id FROM accounting.journal_entries WHERE id = ?",
                no,
                b.account(code),
                debit,
                credit,
                debit,
                credit,
                entry);
    }

    private void post(DSLContext d, UUID entry) {
        d.execute("UPDATE accounting.journal_lines SET is_posted = true WHERE journal_entry_id = ?", entry);
        d.execute(
                "UPDATE accounting.journal_entries SET status = 'POSTED', number = 'RAW-' || left(id::text, 8),"
                        + " posted_at = now() WHERE id = ?",
                entry);
    }

    private void inCompany(Consumer<DSLContext> work) {
        CurrentContext.callWith(
                RequestContext.forRequest("integrity-" + UUID.randomUUID()).withCompany(b.company()),
                () -> tx.execute(status -> {
                    work.accept(dsl);
                    return null;
                }));
    }

    /** The work fails, and the database names {@code constraint} as the guard that refused it. */
    private void assertRejected(Consumer<DSLContext> work, String constraint) {
        Throwable thrown = catchThrowable(() -> inCompany(work));
        assertThat(thrown).as("expected %s to refuse the change", constraint).isNotNull();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof PSQLException e && e.getServerErrorMessage() != null) {
                assertThat(e.getServerErrorMessage().getConstraint())
                        .as(e.getMessage())
                        .isEqualTo(constraint);
                return;
            }
        }
        throw new AssertionError("No database error for " + constraint, thrown);
    }
}
