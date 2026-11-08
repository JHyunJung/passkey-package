package com.crosscert.passkey.admin.tenant

import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.ApiKey
import com.crosscert.passkey.core.entity.Tenant
import com.crosscert.passkey.core.repository.ApiKeyRepository
import com.crosscert.passkey.core.repository.TenantRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID

class TenantSuspendTest {

    private val tenants: TenantRepository = mock()
    private val apiKeys: ApiKeyRepository = mock()
    private val audit: AuditLogService = mock()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-05-29T00:00:00Z"), KstTime.ZONE)

    @Test
    fun suspend_setsStatusAndRevokesActiveKeys() {
        val tenantId = UUID.randomUUID()
        val t = Tenant("acme", "Acme", "acme.example.com", "Acme")
        whenever(tenants.findById(tenantId)).thenReturn(Optional.of(t))
        val k1 = mock<ApiKey>()
        val k2 = mock<ApiKey>()
        whenever(apiKeys.findActiveByTenantId(eq(tenantId), anyOrNull())).thenReturn(listOf(k1, k2))

        val svc = TenantLifecycleService(tenants, apiKeys, audit, clock)
        svc.suspend(tenantId, UUID.randomUUID(), "alice@crosscert.com")

        assertThat(t.status).isEqualTo("suspended")
        verify(k1).revoke(anyOrNull())
        verify(k2).revoke(anyOrNull())
        verify(tenants).save(t)
    }

    @Test
    fun activate_setsStatusActive() {
        val tenantId = UUID.randomUUID()
        val t = Tenant("acme", "Acme", "acme.example.com", "Acme")
        t.suspend()
        whenever(tenants.findById(tenantId)).thenReturn(Optional.of(t))

        val svc = TenantLifecycleService(tenants, apiKeys, audit, clock)
        svc.activate(tenantId, UUID.randomUUID(), "alice@crosscert.com")
        assertThat(t.status).isEqualTo("active")
    }
}
