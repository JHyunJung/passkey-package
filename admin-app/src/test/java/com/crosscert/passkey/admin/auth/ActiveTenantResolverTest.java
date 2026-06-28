package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.api.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActiveTenantResolverTest {

    private final ActiveTenantResolver resolver = new ActiveTenantResolver();

    private void bindRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @AfterEach
    void clear() { RequestContextHolder.resetRequestAttributes(); }

    private AdminUserDetails rpAdmin(Set<UUID> tenants) {
        return new AdminUserDetails(UUID.randomUUID(), "rp@x.com", "hash",
                "RP_ADMIN", tenants, true, null, Clock.systemUTC());
    }

    @Test
    void defaultsToFirstSortedTenantWhenSessionEmpty() {
        bindRequest();
        UUID a = new UUID(0, 1), b = new UUID(0, 2);
        AdminUserDetails me = rpAdmin(Set.of(b, a));
        UUID expectedFirst = new TreeSet<>(Set.of(a, b)).first();
        assertThat(resolver.resolve(me)).isEqualTo(expectedFirst);
    }

    @Test
    void setActiveWithinAllowedPersists() {
        bindRequest();
        UUID a = new UUID(0, 1), b = new UUID(0, 2);
        AdminUserDetails me = rpAdmin(Set.of(a, b));
        resolver.setActive(me, b);
        assertThat(resolver.resolve(me)).isEqualTo(b);
    }

    @Test
    void setActiveOutsideAllowedRejected() {
        bindRequest();
        UUID a = new UUID(0, 1);
        AdminUserDetails me = rpAdmin(Set.of(a));
        assertThatThrownBy(() -> resolver.setActive(me, new UUID(0, 9)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void staleSessionValueOutsideAllowedFallsBackToDefault() {
        bindRequest();
        UUID a = new UUID(0, 1), b = new UUID(0, 2);
        AdminUserDetails me = rpAdmin(Set.of(a, b));
        // 세션에 허용범위 밖 값을 강제로 심어도 resolve 가 기본값으로 복구
        RequestContextHolder.getRequestAttributes()
                .setAttribute(ActiveTenantResolver.ACTIVE_TENANT_ATTR, new UUID(0, 99),
                        ServletRequestAttributes.SCOPE_SESSION);
        assertThat(me.getAllowedTenantIds()).contains(resolver.resolve(me));
    }
}
