package com.erp.inventory.web;

import com.erp.inventory.InventoryPermissions;
import com.erp.inventory.application.InventoryListings;
import com.erp.inventory.application.InventoryViews;
import com.erp.inventory.application.StockQueryService;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Stock levels, location balances, the inventory ledger and valuation (API.md §17.5). */
@RestController
class StockController {

    private static final String C = ApiPaths.V1 + "/companies/{companyId}";

    private final StockQueryService stock;
    private final ListQueryParser parser;

    StockController(StockQueryService stock, ListQueryParser parser) {
        this.stock = stock;
        this.parser = parser;
    }

    /** On hand, reserved and available per variant and warehouse (counting locations only). */
    @RequiresPermission(InventoryPermissions.STOCK_READ)
    @GetMapping(C + "/stock-levels")
    PageResponse<InventoryResponses.StockLevel> levels(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return stock.stockLevels(parser.parse(parameters, InventoryListings.STOCK_LEVELS))
                .map(InventoryResponses.StockLevel::from);
    }

    @RequiresPermission(InventoryPermissions.STOCK_READ)
    @GetMapping(C + "/stock-levels/by-location")
    PageResponse<InventoryResponses.LocationLevel> byLocation(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return stock.byLocation(parser.parse(parameters, InventoryListings.STOCK_BY_LOCATION))
                .map(InventoryResponses.LocationLevel::from);
    }

    /** The append-only ledger: what changed, when, where, by which movement and by whom. */
    @RequiresPermission(InventoryPermissions.STOCK_READ)
    @GetMapping(C + "/inventory-transactions")
    PageResponse<InventoryResponses.LedgerEntry> ledger(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return stock.ledger(parser.parse(parameters, InventoryListings.LEDGER))
                .map(InventoryResponses.LedgerEntry::from);
    }

    @RequiresPermission(InventoryPermissions.VALUATION_READ)
    @GetMapping(C + "/stock-valuation")
    InventoryResponses.ValuationResponse valuation(
            @PathVariable UUID companyId,
            @RequestParam(required = false) @Nullable LocalDate asOf,
            @RequestParam(required = false) @Nullable UUID variantId) {
        List<InventoryViews.ItemValuation> rows = stock.valuation(asOf, variantId);
        return new InventoryResponses.ValuationResponse(
                asOf,
                rows.stream()
                        .map(InventoryViews.ItemValuation::totalValueBase)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                rows.stream().map(InventoryResponses.Valuation::from).toList());
    }
}
