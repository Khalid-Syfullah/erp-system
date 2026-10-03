package com.erp;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.erp.platform.security.AuthenticatedEndpoint;
import com.erp.platform.security.GlobalAccess;
import com.erp.platform.security.PublicEndpoint;
import com.erp.platform.security.RequiresPermission;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Code-level architecture rules (ARCHITECTURE.md §3.1, §5.3; SECURITY.md §1; ADR-008). */
@AnalyzeClasses(packages = "com.erp", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

    private static final String GENERATED = "com.erp.db..";

    /** Money and quantities never use binary floating point (ADR-008). */
    @ArchTest
    static final ArchRule noFloatingPointFields = noFields()
            .that()
            .areDeclaredInClassesThat()
            .resideOutsideOfPackage(GENERATED)
            .should()
            .haveRawType(double.class)
            .orShould()
            .haveRawType(float.class)
            .orShould()
            .haveRawType(Double.class)
            .orShould()
            .haveRawType(Float.class);

    @ArchTest
    static final ArchRule noFloatingPointReturnTypes = noMethods()
            .that()
            .areDeclaredInClassesThat()
            .resideOutsideOfPackage(GENERATED)
            .should()
            .haveRawReturnType(double.class)
            .orShould()
            .haveRawReturnType(float.class)
            .orShould()
            .haveRawReturnType(Double.class)
            .orShould()
            .haveRawReturnType(Float.class);

    /** Deny by default: every endpoint declares its access rule (SECURITY.md §1). */
    @ArchTest
    static final ArchRule everyEndpointDeclaresAccess = methods()
            .that()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(RestController.class)
            .and()
            .areMetaAnnotatedWith(RequestMapping.class)
            .should(declareAccessAnnotation());

    @ArchTest
    static final ArchRule controllersDoNotUsePersistence = noClasses()
            .that()
            .resideInAPackage("..web..")
            .and()
            .resideOutsideOfPackage("com.erp.platform..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..persistence..", "org.jooq..", GENERATED);

    /** SQL lives in persistence packages (and the platform's generic jOOQ helpers). */
    @ArchTest
    static final ArchRule jooqOnlyInPersistence = noClasses()
            .that()
            .resideOutsideOfPackages("..persistence..", "com.erp.platform.jooq..", GENERATED)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.jooq..", GENERATED);

    /** A module reads and writes only its own schema (ARCHITECTURE.md §5.1 rule 1). */
    @ArchTest
    static final ArchRule modulesUseOnlyTheirOwnSchema =
            classes().that().resideOutsideOfPackage(GENERATED).should(onlyAccessOwnSchema());

    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..", "org.jooq..", "jakarta.servlet..", "tools.jackson..", "com.fasterxml..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule requestContextIsImmutable = fields().that()
            .areDeclaredInClassesThat()
            .resideInAPackage("com.erp.platform.context..")
            .should()
            .beFinal();

    private static ArchCondition<JavaMethod> declareAccessAnnotation() {
        return new ArchCondition<>("be annotated with exactly one of @PublicEndpoint, @AuthenticatedEndpoint or"
                + " @RequiresPermission (and @GlobalAccess only with @RequiresPermission)") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                int declared = 0;
                for (Class<? extends java.lang.annotation.Annotation> type : java.util.List.of(
                        PublicEndpoint.class, AuthenticatedEndpoint.class, RequiresPermission.class)) {
                    if (method.isAnnotatedWith(type) || method.getOwner().isAnnotatedWith(type)) {
                        declared++;
                    }
                }
                if (declared != 1) {
                    events.add(SimpleConditionEvent.violated(
                            method,
                            method.getFullName() + " declares " + declared
                                    + " access annotations (exactly 1 required)"));
                }
                boolean global = method.isAnnotatedWith(GlobalAccess.class)
                        || method.getOwner().isAnnotatedWith(GlobalAccess.class);
                boolean permission = method.isAnnotatedWith(RequiresPermission.class)
                        || method.getOwner().isAnnotatedWith(RequiresPermission.class);
                if (global && !permission) {
                    // Cross-company reads bypass RLS; they are reserved for permission-checked endpoints.
                    events.add(SimpleConditionEvent.violated(
                            method, method.getFullName() + " uses @GlobalAccess without @RequiresPermission"));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> onlyAccessOwnSchema() {
        Pattern modulePackage = Pattern.compile("^com\\.erp\\.([a-z]+)(\\..*)?$");
        Pattern schemaPackage = Pattern.compile("^com\\.erp\\.db\\.([a-z_]+)(\\..*)?$");
        return new ArchCondition<>("only access the generated classes of their own schema") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                Matcher owner = modulePackage.matcher(javaClass.getPackageName());
                String module = owner.matches() ? owner.group(1) : "";
                javaClass.getDirectDependenciesFromSelf().forEach(dependency -> {
                    Matcher target =
                            schemaPackage.matcher(dependency.getTargetClass().getPackageName());
                    if (target.matches() && !target.group(1).equals(module)) {
                        events.add(SimpleConditionEvent.violated(
                                dependency, javaClass.getName() + " accesses schema '" + target.group(1) + "'"));
                    }
                });
            }
        };
    }
}
