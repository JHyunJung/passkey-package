package com.crosscert.passkey.app.fido2.credential

import com.crosscert.passkey.app.api.v1.rp.dto.CredentialView
import com.crosscert.passkey.app.fido2.support.idTail
import com.crosscert.passkey.app.fido2.support.toBase64Url
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.entity.Credential
import com.crosscert.passkey.core.repository.CredentialRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.HexFormat

private val log = LoggerFactory.getLogger(CredentialSelfService::class.java)

/**
 * P0-4: end-user self-service credential 관리. tenant 격리는 앱 레벨 @Filter 가 담당.
 */
@Service
class CredentialSelfService(private val creds: CredentialRepository) {

    @Transactional(readOnly = true)
    fun list(userHandle: ByteArray): List<CredentialView> =
        creds.findByUserHandle(userHandle).map(::toView)

    @Transactional
    fun rename(userHandle: ByteArray, credentialId: ByteArray, label: String?) {
        val c = findOwnedForUpdate(userHandle, credentialId)
        c.label = label
    }

    @Transactional
    fun delete(userHandle: ByteArray, credentialId: ByteArray) {
        val c = findOwnedForUpdate(userHandle, credentialId)
        creds.delete(c)
        // Self-service delete is destructive. passkey-app has no audit-chain
        // infra (AuditLogService lives in admin-app — out of scope), so a
        // masked log line is the trace we keep. idTail mirrors
        // CredentialAdminService.revoke — never log the full credentialId.
        log.warn(
            "self-service credential deleted: id={} reason=rp-self-service",
            credentialId.toBase64Url().idTail(),
        )
    }

    private fun findOwnedForUpdate(userHandle: ByteArray, credentialId: ByteArray): Credential =
        creds.findOwnedForUpdate(credentialId, userHandle)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND, "credential not found or not owned") }

    private fun toView(c: Credential): CredentialView =
        CredentialView(
            c.credentialId.toBase64Url(),
            c.label,
            c.aaguid?.let { HexFormat.of().formatHex(it) },
            c.lastUsedAt,
        )
}
