package com.erp.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every request and response type of the API gets a schema name of its own (OpenApiSchemaNames), so
 * the frontend's generated client (API.md §18) types each of them correctly.
 */
class OpenApiSchemaNamesTest {

    @Test
    void everyRequestAndResponseTypeHasItsOwnSchemaName() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Method> handlers = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("com.erp")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            handlers.addAll(List.of(controller.getDeclaredMethods()));
        }
        Set<Class<?>> types = OpenApiSchemaNames.reachableTypes(handlers);
        Map<Class<?>, String> qualified = OpenApiSchemaNames.qualifiedNames(types);

        Map<String, Set<String>> classesByName = new TreeMap<>();
        for (Class<?> type : types) {
            if (type.getTypeParameters().length == 0) {
                classesByName
                        .computeIfAbsent(qualified.getOrDefault(type, type.getSimpleName()), k -> new TreeSet<>())
                        .add(type.getName());
            }
        }
        classesByName.values().removeIf(classes -> classes.size() == 1);

        assertThat(types).hasSizeGreaterThan(200);
        assertThat(classesByName).as("schema names shared by several types").isEmpty();
        assertThat(qualified)
                .containsEntry(
                        Class.forName("com.erp.procurement.web.PurchaseOrderController$LineRequest"),
                        "PurchaseOrderLineRequest")
                .containsEntry(Class.forName("com.erp.accounting.web.AccountingResponses$Period"), "AccountingPeriod")
                .doesNotContainKey(Class.forName("com.erp.sales.web.SalesResponses$SalesOrder"));
    }
}
