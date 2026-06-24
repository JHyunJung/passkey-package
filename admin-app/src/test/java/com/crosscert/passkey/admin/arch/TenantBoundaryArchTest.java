package com.crosscert.passkey.admin.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * QW-1 (sec-admin-vpd-exempt-sole-layer) — cross-tenant 회귀 방지.
 *
 * <p>VPD 제거됨 — admin-app 은 cross-tenant 리소스를 의도적으로 관리하므로
 * cross-tenant 격리가 애플리케이션 레벨 TenantBoundary 단일 계층에만 의존한다. 어느
 * tenant-scoped 서비스가 boundary 참조를 통째로 잃으면 즉시 cross-tenant
 * 노출이므로, 그 회귀를 빌드 시점에 잡는다.
 *
 * <p>의도적으로 좁은 규칙: 강제 지점이 균일하지 않다(일부는 assertCanAccessTenant,
 * list 계열은 currentTenantScope). 전수 규칙은 거짓양성을 내므로, 명시한
 * tenant-scoped 서비스 집합이 TenantBoundary 타입을 참조하는지만 검사한다.
 */
class TenantBoundaryArchTest {

    private static final JavaClasses ADMIN_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.crosscert.passkey.admin");

    @Test
    void tenantScopedServicesReferenceTenantBoundary() {
        DescribedPredicate<JavaClass> isTenantScopedService =
                new DescribedPredicate<>("is a tenant-scoped admin service") {
                    private final Set<String> TARGETS = Set.of(
                            "CredentialAdminService", "ApiKeyAdminService",
                            "TenantAdminService", "WebauthnDiffService", "FunnelService");
                    @Override public boolean test(JavaClass c) {
                        return TARGETS.contains(c.getSimpleName());
                    }
                };

        ArchRule rule = classes()
                .that(isTenantScopedService)
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("com.crosscert.passkey.admin.auth.TenantBoundary")
                .as("tenant-scoped admin services must reference TenantBoundary "
                        + "(VPD removed; admin-app does cross-tenant queries, so "
                        + "TenantBoundary is the sole cross-tenant isolation layer)")
                .because("sec-admin-vpd-exempt-sole-layer: losing the boundary "
                        + "reference silently re-enables cross-tenant access");

        rule.check(ADMIN_CLASSES);
    }
}
