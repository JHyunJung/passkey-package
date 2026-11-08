package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.core.api.BusinessException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Clock
import java.util.Optional
import java.util.UUID

class TenantBoundaryTest {

    private val publisher: ApplicationEventPublisher = mock()
    private val activeTenant: ActiveTenantResolver = mock()
    private val boundary = TenantBoundary(publisher, activeTenant)

    private fun login(me: AdminUserDetails) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(me, "x", me.authorities)
    }

    @AfterEach
    fun clear() {
        SecurityContextHolder.clearContext()
    }

    private fun rpAdmin(tenants: Set<UUID>): AdminUserDetails =
        AdminUserDetails(
            UUID.randomUUID(), "rp@x.com", "hash",
            "RP_ADMIN", tenants, true, null, Clock.systemUTC(),
        )

    @Test
    fun rpAdminCanAccessAnyAllowedTenant() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        login(rpAdmin(setOf(a, b)))
        boundary.assertCanAccessTenant(a) // no throw
        boundary.assertCanAccessTenant(b) // no throw
    }

    @Test
    fun rpAdminCannotAccessTenantOutsideAllowed() {
        val a = UUID.randomUUID()
        login(rpAdmin(setOf(a)))
        assertThatThrownBy { boundary.assertCanAccessTenant(UUID.randomUUID()) }
            .isInstanceOf(BusinessException::class.java)
    }

    @Test
    fun scopeReturnsActiveTenantForRpAdmin() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val me = rpAdmin(setOf(a, b))
        login(me)
        whenever(activeTenant.resolve(me)).thenReturn(b)
        assertThat(boundary.currentTenantScope()).isEqualTo(Optional.of(b))
    }

    @Test
    fun scopeFailsClosedWhenRpAdminHasNoActiveTenant() {
        // 비정상 상태(매핑 0개) — fail-open(전체 노출) 대신 ACCESS_DENIED 여야 한다.
        val a = UUID.randomUUID()
        val me = rpAdmin(setOf(a))
        login(me)
        whenever(activeTenant.resolve(me)).thenReturn(null)
        assertThatThrownBy { boundary.currentTenantScope() }
            .isInstanceOf(BusinessException::class.java)
    }

    @Test
    fun scopeEmptyForPlatformOperator() {
        val me = AdminUserDetails(
            UUID.randomUUID(), "ops@x.com", "hash",
            "PLATFORM_OPERATOR", setOf(), true, null, Clock.systemUTC(),
        )
        login(me)
        assertThat(boundary.currentTenantScope()).isEmpty()
    }
}
