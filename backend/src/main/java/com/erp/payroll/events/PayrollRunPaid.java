package com.erp.payroll.events;

import com.erp.platform.events.DomainEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code payroll.run.paid} (ARCHITECTURE.md §7), schema version 1: published synchronously when a
 * posted run is marked paid. Accounting validates the bank account (an Accounting ID Payroll keeps
 * without FK), books Dr salaries payable / Cr bank and records the disbursement as a payment.
 */
public record PayrollRunPaid(
        EventMetadata metadata,
        UUID runId,
        String number,
        UUID bankAccountId,
        LocalDate paymentDate,
        String currencyCode,
        BigDecimal amount)
        implements DomainEvent {

    public static final String TYPE = "payroll.run.paid";
    public static final int SCHEMA_VERSION = 1;
}
