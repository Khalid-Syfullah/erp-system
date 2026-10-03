package com.erp.auth;

import static com.erp.db.auth.Tables.PERMISSIONS;
import static com.erp.db.auth.Tables.ROLES;
import static org.assertj.core.api.Assertions.assertThat;

import com.erp.admin.AdminPermissions;
import com.erp.org.OrgPermissions;
import com.erp.platform.security.RequiresPermission;
import com.erp.support.IntegrationTest;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The permission catalogue is defined in SECURITY.md §4.2 and seeded by a repeatable migration
 * (ADR-029). This test keeps the document, the database and the code in step.
 */
class PermissionCatalogIntegrationTest extends IntegrationTest {

    private static final Path SECURITY_MD = Path.of("..", "docs", "SECURITY.md");
    private static final Pattern CODE = Pattern.compile("`([a-z_]+\\.[a-z_]+\\.[a-z_]+)`");
    private static final Pattern ROLE_ROW = Pattern.compile("^\\| `([A-Z_]+)` \\|", Pattern.MULTILINE);

    @Autowired
    DSLContext dsl;

    @Autowired
    @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;

    @Test
    void databaseCatalogueEqualsTheSecurityDocument() throws Exception {
        Set<String> documented = documentedPermissions();

        Set<String> active = new HashSet<>(dsl.select(PERMISSIONS.CODE)
                .from(PERMISSIONS)
                .where(PERMISSIONS.DEPRECATED_AT.isNull())
                .fetch(PERMISSIONS.CODE));

        assertThat(documented).hasSizeGreaterThan(100);
        assertThat(active).isEqualTo(documented);
    }

    @Test
    void sensitivePermissionsFollowTheDocumentedRule() {
        dsl.selectFrom(PERMISSIONS).where(PERMISSIONS.DEPRECATED_AT.isNull()).forEach(permission -> {
            String code = permission.getCode();
            boolean expected = code.endsWith(".manage_bank")
                    || code.endsWith(".read_bank")
                    || code.equals("hr.employee.read_sensitive")
                    || code.startsWith("payroll.")
                    || code.equals("accounting.period.reopen")
                    || code.equals("accounting.payment.void")
                    || code.equals("auth.role.manage")
                    || code.equals("auth.role_assignment.manage");
            assertThat(permission.getIsSensitive()).as(code).isEqualTo(expected);
        });
    }

    @Test
    void permissionConstantsAndEndpointAnnotationsReferenceCataloguedCodes() throws Exception {
        Set<String> documented = documentedPermissions();
        Set<String> used = new LinkedHashSet<>();
        for (Class<?> constants :
                new Class<?>[] {AuthPermissions.class, OrgPermissions.class, AdminPermissions.class}) {
            for (Field field : constants.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                    field.setAccessible(true);
                    used.add((String) field.get(null));
                }
            }
        }
        handlerMapping.getHandlerMethods().values().forEach(handler -> {
            RequiresPermission annotation =
                    AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), RequiresPermission.class);
            if (annotation == null) {
                annotation =
                        AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), RequiresPermission.class);
            }
            if (annotation != null
                    && handler.getBeanType().getPackageName().startsWith("com.erp.")
                    && !handler.getBeanType().getSimpleName().contains("Probe")) {
                used.addAll(Set.of(annotation.value()));
            }
        });

        assertThat(used).isNotEmpty();
        assertThat(documented).containsAll(used);
        assertThat(documented).containsAll(AuthPermissions.GLOBAL_ONLY);
    }

    @Test
    void systemRolesMatchTheSecurityDocument() throws Exception {
        String document = Files.readString(SECURITY_MD);
        String section = document.substring(document.indexOf("### 4.3"), document.indexOf("### 4.4"));
        Set<String> documented = new LinkedHashSet<>();
        Matcher matcher = ROLE_ROW.matcher(section);
        while (matcher.find()) {
            documented.add(matcher.group(1));
        }

        Set<String> seeded = new HashSet<>(dsl.select(ROLES.CODE)
                .from(ROLES)
                .where(ROLES.IS_SYSTEM.isTrue())
                .fetch(ROLES.CODE));

        assertThat(documented).hasSize(18);
        assertThat(seeded).isEqualTo(documented);
        // Every system role except EMPLOYEE (self-service is authorized by the employee link, §4.3) grants
        // at least one permission, and none grants a global-only permission.
        assertThat(dsl.fetchValue("select count(*) from auth.roles r where r.is_system and r.code <> 'EMPLOYEE'"
                        + " and not exists"
                        + " (select 1 from auth.role_permissions rp where rp.role_id = r.id)"))
                .isEqualTo(0L);
        assertThat(dsl.fetchValue(
                        "select count(*) from auth.role_permissions rp join auth.roles r on r.id = rp.role_id"
                                + " where r.is_system and rp.permission_code = any(?)",
                        (Object) AuthPermissions.GLOBAL_ONLY.toArray(String[]::new)))
                .isEqualTo(0L);
    }

    private static Set<String> documentedPermissions() throws Exception {
        String document = Files.readString(SECURITY_MD);
        String table = document.substring(document.indexOf("### 4.2"), document.indexOf("Permissions flagged"));
        Set<String> codes = new LinkedHashSet<>();
        Matcher matcher = CODE.matcher(table);
        while (matcher.find()) {
            codes.add(matcher.group(1));
        }
        return codes;
    }
}
