package com.crosscert.passkey.admin.credential

import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.auth.TenantBoundary
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.ceremony.CredentialAuthResult
import com.crosscert.passkey.core.entity.Credential
import com.crosscert.passkey.core.entity.CredentialAuthEvent
import com.crosscert.passkey.core.mds.MdsAaguidCache
import com.crosscert.passkey.core.repository.CredentialAuthEventRepository
import com.crosscert.passkey.core.repository.CredentialRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageImpl
import java.util.Optional
import java.util.UUID

class CredentialAdminServiceAuthEventsTest {

    private val creds: CredentialRepository = mock()
    private val mds: MdsAaguidCache = mock()
    private val audit: AuditLogService = mock()
    private val tenantBoundary: TenantBoundary = mock()
    private val authEvents: CredentialAuthEventRepository = mock()

    // 실제 CredentialAdminService 생성자: (creds, mds, audit, tenantBoundary, authEvents)
    private val svc = CredentialAdminService(creds, mds, audit, tenantBoundary, authEvents)

    @Test
    fun listAuthEventsMapsToView() {
        val tenant = UUID.randomUUID()
        val credPk = UUID.randomUUID()
        val credB64 = "AAAA" // base64url decodable

        val c: Credential = mock()
        whenever(c.tenantId).thenReturn(tenant)
        whenever(c.id).thenReturn(credPk)
        whenever(creds.findByCredentialId(anyOrNull())).thenReturn(Optional.of(c))

        whenever(authEvents.findByTenantIdAndCredentialIdOrderByCreatedAtDesc(eq(tenant), eq(credPk), anyOrNull()))
            .thenReturn(
                PageImpl(
                    listOf(CredentialAuthEvent(credPk, tenant, CredentialAuthResult.SUCCESS, null, 3)),
                ),
            )

        val page = svc.listAuthEvents(tenant, credB64, 0, 50)

        assertThat(page.content()).hasSize(1)
        assertThat(page.content()[0].result).isEqualTo(CredentialAuthResult.SUCCESS)
        assertThat(page.content()[0].signCount).isEqualTo(3L)
        verify(tenantBoundary).assertCanAccessTenant(tenant)
        // defense-in-depth: 조회 술어에 tenant_id 가 포함됐는지 검증(오염 행 cross-tenant 누출 방지).
        verify(authEvents).findByTenantIdAndCredentialIdOrderByCreatedAtDesc(eq(tenant), eq(credPk), anyOrNull())
    }

    @Test
    fun listAuthEventsRejectsCrossTenant() {
        val tenant = UUID.randomUUID()
        val otherTenant = UUID.randomUUID()
        UUID.randomUUID() // credPk (원 Java 에서도 미사용 지역 변수)

        val c: Credential = mock()
        whenever(c.tenantId).thenReturn(otherTenant)
        whenever(creds.findByCredentialId(anyOrNull())).thenReturn(Optional.of(c))

        assertThatThrownBy { svc.listAuthEvents(tenant, "AAAA", 0, 50) }
            .isInstanceOf(BusinessException::class.java)
    }
}
