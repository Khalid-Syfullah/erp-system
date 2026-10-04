package com.erp.payroll.domain.statutory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** The registered statutory rules by code. */
public final class StatutoryRules {

    private final Map<String, StatutoryRule> rules = new TreeMap<>();

    public StatutoryRules(Collection<? extends StatutoryRule> rules) {
        for (StatutoryRule rule : rules) {
            if (!rule.code().matches("^[A-Z0-9_]{1,40}$")) {
                throw new IllegalArgumentException("Invalid statutory rule code " + rule.code());
            }
            if (this.rules.put(rule.code(), rule) != null) {
                throw new IllegalArgumentException("Duplicate statutory rule " + rule.code());
            }
        }
    }

    public Optional<StatutoryRule> find(String code) {
        return Optional.ofNullable(rules.get(code));
    }

    public List<StatutoryRule> all() {
        return List.copyOf(rules.values());
    }
}
