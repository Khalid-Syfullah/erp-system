package com.erp.inventory.application;

import com.erp.inventory.persistence.StockRepository;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of stock: warehouse levels (on hand, reserved, available), location balances, the
 * inventory ledger and valuation — limited to the caller's branches' warehouses.
 */
@Service
public class StockQueryService {

    private final StockRepository stock;
    private final InventoryContext context;

    StockQueryService(StockRepository stock, InventoryContext context) {
        this.stock = stock;
        this.context = context;
    }

    @Transactional(readOnly = true)
    public PageResponse<InventoryViews.WarehouseStockLevel> stockLevels(ListQuery query) {
        return stock.stockLevels(CurrentContext.requireCompany(), context.visibleWarehouses(), query);
    }

    @Transactional(readOnly = true)
    public PageResponse<InventoryViews.LocationStockLevel> byLocation(ListQuery query) {
        return stock.balances(CurrentContext.requireCompany(), context.visibleWarehouses(), query);
    }

    @Transactional(readOnly = true)
    public PageResponse<InventoryViews.LedgerEntry> ledger(ListQuery query) {
        return stock.ledger(CurrentContext.requireCompany(), context.visibleWarehouses(), query);
    }

    /**
     * Company-wide valuation per variant, now or on a past date (from the ledger). Valuation is not
     * branch-scoped: moving-average cost is per company (INV-4) — access needs inventory.valuation.read.
     */
    @Transactional(readOnly = true)
    public List<InventoryViews.ItemValuation> valuation(@Nullable LocalDate asOf, @Nullable UUID variantId) {
        UUID companyId = CurrentContext.requireCompany();
        return asOf == null ? stock.valuations(companyId, variantId) : stock.valuationsAsOf(companyId, asOf, variantId);
    }
}
