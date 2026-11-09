package com.crosscert.passkey.admin.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

class AdminUserDetailsTest {

    private fun rpAdmin(tenants: Set<UUID>): AdminUserDetails =
        AdminUserDetails(
            UUID.randomUUID(), "rp@x.com", "hash",
            "RP_ADMIN", tenants, true, null, Clock.systemUTC(),
        )

    @Test
    fun rpAdminExposesAllowedTenants() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        val me = rpAdmin(setOf(t1, t2))
        assertThat(me.isRpAdmin()).isTrue()
        assertThat(me.allowedTenantIds).containsExactlyInAnyOrder(t1, t2)
    }

    @Test
    fun platformOperatorHasEmptyAllowedTenants() {
        val me = AdminUserDetails(
            UUID.randomUUID(), "ops@x.com", "hash",
            "PLATFORM_OPERATOR", setOf(), true, null, Clock.systemUTC(),
        )
        assertThat(me.isPlatformOperator()).isTrue()
        assertThat(me.allowedTenantIds).isEmpty()
    }
}
