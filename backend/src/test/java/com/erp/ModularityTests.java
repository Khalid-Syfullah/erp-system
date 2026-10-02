package com.erp;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** Module boundaries of ARCHITECTURE.md §5 (no cycles, only allowed dependencies and exposed types). */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(ErpApplication.class);

    @Test
    void verifiesModuleStructure() {
        modules.verify();
    }

    @Test
    void writesModuleDocumentation() {
        new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
