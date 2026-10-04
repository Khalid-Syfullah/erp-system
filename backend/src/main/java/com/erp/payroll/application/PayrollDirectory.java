package com.erp.payroll.application;

import com.erp.payroll.api.PayrollFacade;
import com.erp.payroll.persistence.PayrollConfigRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link PayrollFacade} for Accounting. */
@Service
class PayrollDirectory implements PayrollFacade {

    private final PayrollConfigRepository config;
    private final PayrollContext context;

    PayrollDirectory(PayrollConfigRepository config, PayrollContext context) {
        this.config = config;
        this.context = context;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ComponentInfo> component(UUID componentId) {
        return config.component(context.companyId(), componentId)
                .map(c -> new ComponentInfo(c.id(), c.code(), c.name(), c.kind(), c.active()));
    }
}
