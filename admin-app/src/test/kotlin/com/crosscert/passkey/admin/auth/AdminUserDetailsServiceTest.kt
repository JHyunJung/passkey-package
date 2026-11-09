package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.repository.AdminUserRepository
import com.crosscert.passkey.core.repository.AdminUserTenantRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Clock
import java.util.Optional
import java.util.UUID

/**
 * S16 전제 고정 — 로그인마다 새 AdminUserDetails 를 만든다. 그래서 인증 후
 * ProviderManager 가 principal 의 해시를 지워도 다음 로그인의 BCrypt 비교에는
 * 영향이 없다.
 */
class AdminUserDetailsServiceTest {

    private val repo: AdminUserRepository = mock()
    private val mappingRepo: AdminUserTenantRepository = mock()

    @Test
    fun loadUserByUsername_returnsFreshInstanceEachCall() {
        val id = UUID.randomUUID()
        val tenant = UUID.randomUUID()
        val u: AdminUser = mock()
        whenever(u.id).thenReturn(id)
        whenever(u.email).thenReturn("rp@example.com")
        whenever(u.bcryptHash).thenReturn("\$2a\$12\$storedhash")
        whenever(u.role).thenReturn("RP_ADMIN")
        whenever(u.isEnabled).thenReturn(true)
        whenever(u.lockedUntil).thenReturn(null)
        whenever(repo.findByEmail("rp@example.com")).thenReturn(Optional.of(u))
        whenever(mappingRepo.findTenantIdsByAdminUserId(id)).thenReturn(listOf(tenant))

        val svc = AdminUserDetailsService(repo, mappingRepo, Clock.systemUTC())
        val first = svc.loadUserByUsername("rp@example.com")
        val second = svc.loadUserByUsername("rp@example.com")

        assertThat(second).isNotSameAs(first)
        assertThat(first.password).isEqualTo("\$2a\$12\$storedhash")
        assertThat(second.password).isEqualTo("\$2a\$12\$storedhash")
        assertThat(second.id).isEqualTo(id)
        assertThat(second.allowedTenantIds).containsExactly(tenant)
    }
}
