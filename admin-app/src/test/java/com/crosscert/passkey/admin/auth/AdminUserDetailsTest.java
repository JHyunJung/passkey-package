package com.crosscert.passkey.admin.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminUserDetailsTest {

    private AdminUserDetails rpAdmin(Set<UUID> tenants) {
        return new AdminUserDetails(UUID.randomUUID(), "rp@x.com", "hash",
                "RP_ADMIN", tenants, true, null, Clock.systemUTC());
    }

    @Test
    void rpAdminExposesAllowedTenants() {
        UUID t1 = UUID.randomUUID(), t2 = UUID.randomUUID();
        AdminUserDetails me = rpAdmin(Set.of(t1, t2));
        assertThat(me.isRpAdmin()).isTrue();
        assertThat(me.getAllowedTenantIds()).containsExactlyInAnyOrder(t1, t2);
    }

    @Test
    void platformOperatorHasEmptyAllowedTenants() {
        AdminUserDetails me = new AdminUserDetails(UUID.randomUUID(), "ops@x.com", "hash",
                "PLATFORM_OPERATOR", Set.of(), true, null, Clock.systemUTC());
        assertThat(me.isPlatformOperator()).isTrue();
        assertThat(me.getAllowedTenantIds()).isEmpty();
    }
}
