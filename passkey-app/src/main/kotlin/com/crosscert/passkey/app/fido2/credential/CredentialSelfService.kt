package com.crosscert.passkey.app.fido2.credential

import com.crosscert.passkey.app.api.v1.rp.dto.CredentialView
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.entity.Credential
import com.crosscert.passkey.core.repository.CredentialRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Base64
import java.util.HexFormat

private val log = LoggerFactory.getLogger(CredentialSelfService::class.java)

/**
 * P0-4: end-user self-service credential 관리. tenant 격리는 앱 레벨 @Filter 가 담당.
 *
 * 파라미터는 원 Java 와 같은 입력을 받도록 nullable: null 은 그대로 리포지토리 쿼리 바인딩으로
 * 넘어가 매칭 0건(→ 빈 목록 / ENTITY_NOT_FOUND)이 된다. 컨트롤러는 항상 디코드된 값을 넘긴다.
 */
@Service
class CredentialSelfService(private val creds: CredentialRepository) {

    @Transactional(readOnly = true)
    fun list(userHandle: ByteArray?): List<CredentialView> =
        creds.findByUserHandle(userHandle).map(::toView)

    @Transactional
    fun rename(userHandle: ByteArray?, credentialId: ByteArray?, label: String?) {
        val c = findOwnedForUpdate(userHandle, credentialId)
        c.label = label
    }

    @Transactional
    fun delete(userHandle: ByteArray?, credentialId: ByteArray?) {
        val c = findOwnedForUpdate(userHandle, credentialId)
        creds.delete(c)
        // Self-service delete is destructive. passkey-app has no audit-chain
        // infra (AuditLogService lives in admin-app — out of scope), so a
        // masked log line is the trace we keep. idTail mirrors
        // CredentialAdminService.revoke — never log the full credentialId.
        log.warn(
            "self-service credential deleted: id={} reason=rp-self-service",
            idTail(Base64.getUrlEncoder().withoutPadding().encodeToString(credentialId)),
        )
    }

    private fun findOwnedForUpdate(userHandle: ByteArray?, credentialId: ByteArray?): Credential =
        creds.findOwnedForUpdate(credentialId, userHandle)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND, "credential not found or not owned") }

    private fun toView(c: Credential): CredentialView =
        CredentialView(
            Base64.getUrlEncoder().withoutPadding().encodeToString(c.credentialId),
            c.label,
            c.aaguid?.let { HexFormat.of().formatHex(it) },
            c.lastUsedAt,
        )
}

/** Last 12 chars of a base64url id — never the full id. Short ids masked. */
private fun idTail(id: String): String = if (id.length <= 12) "***" else "..." + id.takeLast(12)
