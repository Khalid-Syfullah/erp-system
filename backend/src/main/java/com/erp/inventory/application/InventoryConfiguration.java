package com.erp.inventory.application;

import com.erp.org.api.OrgFacade;
import com.erp.platform.numbering.DocumentType;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import java.time.LocalTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Numbered document types (PRODUCT_SPEC.md G-6) and the nightly invariant check of the Inventory module. */
@Configuration(proxyBeanMethods = false)
class InventoryConfiguration {

    @Bean
    DocumentType stockMovementDocumentType() {
        return new DocumentType(PostingEngine.DOCUMENT_TYPE, "SM-{FY}-", 6);
    }

    @Bean
    DocumentType stockCountDocumentType() {
        return new DocumentType(StockCountService.DOCUMENT_TYPE, "SC-{FY}-", 6);
    }

    @Bean
    RecurringTask<Void> inventoryInvariantTask(InventoryInvariantCheck check, OrgFacade org) {
        return Tasks.recurring("inventory-invariants", Schedules.daily(LocalTime.of(2, 30)))
                .execute((instance, context) -> org.allCompanyIds().forEach(check::check));
    }
}
