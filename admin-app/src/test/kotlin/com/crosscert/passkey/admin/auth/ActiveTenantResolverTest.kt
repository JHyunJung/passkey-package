package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.core.api.BusinessException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Clock
import java.util.TreeSet
import java.util.UUID

class ActiveTenantResolverTest {

    private val resolver = ActiveTenantResolver()

    private fun bindRequest() {
        val req = MockHttpServletRequest()
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(req))
    }

    @AfterEach
    fun clear() {
        RequestContextHolder.resetRequestAttributes()
    }

    private fun rpAdmin(tenants: Set<UUID>): AdminUserDetails =
        AdminUserDetails(
            UUID.randomUUID(), "rp@x.com", "hash",
            "RP_ADMIN", tenants, true, null, Clock.systemUTC(),
        )

    @Test
    fun defaultsToFirstSortedTenantWhenSessionEmpty() {
        bindRequest()
        val a = UUID(0, 1)
        val b = UUID(0, 2)
        val me = rpAdmin(setOf(b, a))
        val expectedFirst = TreeSet(setOf(a, b)).first()
        assertThat(resolver.resolve(me)).isEqualTo(expectedFirst)
    }

    @Test
    fun setActiveWithinAllowedPersists() {
        bindRequest()
        val a = UUID(0, 1)
        val b = UUID(0, 2)
        val me = rpAdmin(setOf(a, b))
        resolver.setActive(me, b)
        assertThat(resolver.resolve(me)).isEqualTo(b)
    }

    @Test
    fun setActiveOutsideAllowedRejected() {
        bindRequest()
        val a = UUID(0, 1)
        val me = rpAdmin(setOf(a))
        assertThatThrownBy { resolver.setActive(me, UUID(0, 9)) }
            .isInstanceOf(BusinessException::class.java)
    }

    @Test
    fun staleSessionValueOutsideAllowedFallsBackToDefault() {
        bindRequest()
        val a = UUID(0, 1)
        val b = UUID(0, 2)
        val me = rpAdmin(setOf(a, b))
        // 세션에 허용범위 밖 값을 강제로 심어도 resolve 가 기본값으로 복구
        // (bindRequest 직후라 non-null — Java 원본도 null 이면 NPE 로 실패했을 지점)
        requireNotNull(RequestContextHolder.getRequestAttributes())
            .setAttribute(ActiveTenantResolver.ACTIVE_TENANT_ATTR, UUID(0, 99), RequestAttributes.SCOPE_SESSION)
        assertThat(me.allowedTenantIds).contains(resolver.resolve(me))
    }
}
