package com.crosscert.passkey.admin.credential

import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.auth.TenantBoundary
import com.crosscert.passkey.core.entity.Credential
import com.crosscert.passkey.core.mds.MdsAaguidCache
import com.crosscert.passkey.core.repository.CredentialAuthEventRepository
import com.crosscert.passkey.core.repository.CredentialRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.nio.ByteBuffer
import java.time.OffsetDateTime
import java.util.Random
import java.util.UUID

/**
 * G15-credN1 — CredentialAdminService.list() must resolve authenticatorName
 * for a page of rows via ONE batched Redis round-trip (MdsAaguidCache.multiLookup),
 * not one MdsAaguidCache.lookup() GET per row. A 200-row page previously
 * issued up to 200 sequential Redis round-trips before returning.
 */
class CredentialAdminServiceListTest {

    private val creds: CredentialRepository = mock()
    private val mds: MdsAaguidCache = mock()
    private val audit: AuditLogService = mock()
    private val tenantBoundary: TenantBoundary = mock()
    private val authEvents: CredentialAuthEventRepository = mock()

    private val svc = CredentialAdminService(creds, mds, audit, tenantBoundary, authEvents)

    @Test
    fun listResolvesAuthenticatorNamesWithSingleBatchedLookup() {
        val tenant = UUID.randomUUID()
        val aaguid1 = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val aaguid2 = UUID.fromString("22222222-2222-3333-4444-555555555555")

        // 3 rows: two distinct AAGUIDs (one repeated) + one credential with no AAGUID.
        val rows = listOf(
            credential(tenant, aaguid1),
            credential(tenant, aaguid2),
            credential(tenant, aaguid1),
            credentialWithoutAaguid(tenant),
        )

        whenever(creds.findAllByTenantId(eq(tenant), anyOrNull()))
            .thenReturn(PageImpl(rows, PageRequest.of(0, 200), rows.size.toLong()))

        whenever(mds.multiLookup(anyList())).thenReturn(
            mapOf(
                aaguid1 to MdsAaguidCache.Entry(listOf("FIDO_CERTIFIED_L1")),
                aaguid2 to MdsAaguidCache.Entry(listOf("FIDO_CERTIFIED_L1", "REVOKED")),
            ),
        )

        val page = svc.list(tenant, 0, 200, null)

        assertThat(page.content()).hasSize(4)
        assertThat(page.content()[0].authenticatorName).isEqualTo("FIDO_CERTIFIED_L1")
        assertThat(page.content()[1].authenticatorName).isEqualTo("FIDO_CERTIFIED_L1,REVOKED")
        assertThat(page.content()[2].authenticatorName).isEqualTo("FIDO_CERTIFIED_L1")
        assertThat(page.content()[3].authenticatorName).isNull()

        // The whole point of G15: exactly one batched call regardless of row count,
        // and the old per-row lookup() must never be invoked.
        verify(mds, times(1)).multiLookup(anyList())
        verify(mds, never()).lookup(anyOrNull())
    }

    @Test
    fun listWithNoAaguidRowsSkipsMdsCallEntirely() {
        val tenant = UUID.randomUUID()
        val rows = listOf(credentialWithoutAaguid(tenant), credentialWithoutAaguid(tenant))

        whenever(creds.findAllByTenantId(eq(tenant), anyOrNull()))
            .thenReturn(PageImpl(rows, PageRequest.of(0, 200), rows.size.toLong()))

        val page = svc.list(tenant, 0, 200, null)

        assertThat(page.content()).hasSize(2)
        assertThat(page.content()).allMatch { v -> v.authenticatorName == null }
        verify(mds, never()).multiLookup(anyList())
        verify(mds, never()).lookup(anyOrNull())
    }

    private companion object {
        fun credential(tenant: UUID, aaguid: UUID): Credential {
            val c: Credential = mock()
            whenever(c.tenantId).thenReturn(tenant)
            whenever(c.credentialId).thenReturn(randomBytes(16))
            whenever(c.userHandle).thenReturn(randomBytes(16))
            whenever(c.aaguid).thenReturn(uuidBytes(aaguid))
            whenever(c.label).thenReturn(null)
            whenever(c.attestationFmt).thenReturn("packed")
            whenever(c.transports).thenReturn("usb")
            whenever(c.signCount).thenReturn(1L)
            whenever(c.lastUsedAt).thenReturn(null)
            whenever(c.createdAt).thenReturn(OffsetDateTime.now())
            return c
        }

        fun credentialWithoutAaguid(tenant: UUID): Credential {
            val c: Credential = mock()
            whenever(c.tenantId).thenReturn(tenant)
            whenever(c.credentialId).thenReturn(randomBytes(16))
            whenever(c.userHandle).thenReturn(randomBytes(16))
            whenever(c.aaguid).thenReturn(null)
            whenever(c.label).thenReturn(null)
            whenever(c.attestationFmt).thenReturn("none")
            whenever(c.transports).thenReturn(null)
            whenever(c.signCount).thenReturn(0L)
            whenever(c.lastUsedAt).thenReturn(null)
            whenever(c.createdAt).thenReturn(OffsetDateTime.now())
            return c
        }

        fun randomBytes(n: Int): ByteArray {
            val b = ByteArray(n)
            Random().nextBytes(b)
            return b
        }

        fun uuidBytes(u: UUID): ByteArray {
            val bb = ByteBuffer.allocate(16)
            bb.putLong(u.mostSignificantBits)
            bb.putLong(u.leastSignificantBits)
            return bb.array()
        }
    }
}
