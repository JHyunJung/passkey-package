package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.api.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantBoundaryTest {

    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final ActiveTenantResolver activeTenant = mock(ActiveTenantResolver.class);
    private final TenantBoundary boundary = new TenantBoundary(publisher, activeTenant);

    private void login(AdminUserDetails me) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(me, "x", me.getAuthorities()));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private AdminUserDetails rpAdmin(Set<UUID> tenants) {
        return new AdminUserDetails(UUID.randomUUID(), "rp@x.com", "hash",
                "RP_ADMIN", tenants, true, null, Clock.systemUTC());
    }

    @Test
    void rpAdminCanAccessAnyAllowedTenant() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        login(rpAdmin(Set.of(a, b)));
        boundary.assertCanAccessTenant(a);   // no throw
        boundary.assertCanAccessTenant(b);   // no throw
    }

    @Test
    void rpAdminCannotAccessTenantOutsideAllowed() {
        UUID a = UUID.randomUUID();
        login(rpAdmin(Set.of(a)));
        assertThatThrownBy(() -> boundary.assertCanAccessTenant(UUID.randomUUID()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void scopeReturnsActiveTenantForRpAdmin() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        AdminUserDetails me = rpAdmin(Set.of(a, b));
        login(me);
        when(activeTenant.resolve(me)).thenReturn(b);
        assertThat(boundary.currentTenantScope()).isEqualTo(Optional.of(b));
    }

    @Test
    void scopeEmptyForPlatformOperator() {
        AdminUserDetails me = new AdminUserDetails(UUID.randomUUID(), "ops@x.com", "hash",
                "PLATFORM_OPERATOR", Set.of(), true, null, Clock.systemUTC());
        login(me);
        assertThat(boundary.currentTenantScope()).isEmpty();
    }
}
