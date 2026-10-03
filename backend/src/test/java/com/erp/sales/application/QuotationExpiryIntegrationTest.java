package com.erp.sales.application;

import static com.erp.support.ProcurementFixtures.expect;
import static com.erp.support.ProcurementFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;

import com.erp.org.api.OrgFacade;
import com.erp.platform.audit.AuditPort;
import com.erp.sales.persistence.QuotationRepository;
import com.erp.support.IntegrationTest;
import com.erp.support.SalesFixtures;
import com.erp.support.SalesFixtures.O2C;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** The daily quotation expiry (PRODUCT_SPEC.md §9.2), run with a clock past the validity. */
class QuotationExpiryIntegrationTest extends IntegrationTest {

    @Autowired
    SalesFixtures sales;

    @Autowired
    QuotationRepository quotations;

    @Autowired
    OrgFacade org;

    @Autowired
    AuditPort audit;

    @Autowired
    TransactionTemplate tx;

    @Test
    void sentQuotationsPastTheirValidityExpire() throws Exception {
        O2C o = sales.setup();
        UUID sent = id(sales.create(o, "/quotations", sales.order(o, sales.line(o.variant(), "1", null))), 201);
        expect(sales.action(o, o.session(), "/quotations/" + sent + "/send", 0, null, null), 200);
        UUID draft = id(sales.create(o, "/quotations", sales.order(o, sales.line(o.variant(), "1", null))), 201);

        QuotationExpiry today = new QuotationExpiry(quotations, org, audit, tx, Clock.systemUTC());
        assertThat(today.run(o.inv().company())).isZero();

        QuotationExpiry later =
                new QuotationExpiry(quotations, org, audit, tx, Clock.offset(Clock.systemUTC(), Duration.ofDays(40)));
        assertThat(later.run(o.inv().company())).isEqualTo(1);
        assertThat(sales.<String>read(o, "/quotations/" + sent, "$.status")).isEqualTo("EXPIRED");
        assertThat(sales.<String>read(o, "/quotations/" + draft, "$.status")).isEqualTo("DRAFT");
        expect(sales.action(o, o.session(), "/quotations/" + sent + "/accept", 2, null, null), 409);
        assertThat(later.run(o.inv().company())).isZero();
    }
}
