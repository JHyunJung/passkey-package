package com.crosscert.passkey.app.fido2.credential

import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.entity.Credential
import com.crosscert.passkey.core.repository.CredentialRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Optional

class CredentialSelfServiceTest {

    private val creds: CredentialRepository = mock()

    @Test
    fun list_returnsCredentialsForUserHandle() {
        val uh = byteArrayOf(1, 2)
        val c: Credential = mock()
        whenever(c.credentialId).thenReturn(byteArrayOf(9))
        whenever(c.label).thenReturn("MacBook")
        whenever(creds.findByUserHandle(uh)).thenReturn(listOf(c))

        val svc = CredentialSelfService(creds)
        val views = svc.list(uh)

        assertThat(views).hasSize(1)
        assertThat(views[0].label).isEqualTo("MacBook")
    }

    @Test
    fun rename_setsLabel() {
        val uh = byteArrayOf(1, 2)
        val cid = byteArrayOf(9)
        val c: Credential = mock()
        whenever(creds.findOwnedForUpdate(cid, uh)).thenReturn(Optional.of(c))

        val svc = CredentialSelfService(creds)
        svc.rename(uh, cid, "iPhone")

        verify(c).label = "iPhone"
    }

    @Test
    fun delete_removesOwnedCredential() {
        val uh = byteArrayOf(1, 2)
        val cid = byteArrayOf(9)
        val c: Credential = mock()
        whenever(creds.findOwnedForUpdate(cid, uh)).thenReturn(Optional.of(c))

        val svc = CredentialSelfService(creds)
        svc.delete(uh, cid)

        verify(creds).delete(c)
    }

    @Test
    fun delete_throwsWhenNotOwned() {
        whenever(creds.findOwnedForUpdate(anyOrNull(), anyOrNull())).thenReturn(Optional.empty())
        val svc = CredentialSelfService(creds)
        assertThatThrownBy { svc.delete(byteArrayOf(1), byteArrayOf(2)) }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.ENTITY_NOT_FOUND)
    }

    @Test
    fun rename_throwsWhenNotOwned() {
        whenever(creds.findOwnedForUpdate(anyOrNull(), anyOrNull())).thenReturn(Optional.empty())
        val svc = CredentialSelfService(creds)
        assertThatThrownBy { svc.rename(byteArrayOf(1), byteArrayOf(2), "iPhone") }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.ENTITY_NOT_FOUND)
    }
}
