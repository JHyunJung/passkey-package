package com.crosscert.passkey.admin.credential

import com.crosscert.passkey.admin.audit.AuditAppendRequest
import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.auth.TenantBoundary
import com.crosscert.passkey.admin.credential.CredentialAdminDto.CredentialView
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.api.PageView
import com.crosscert.passkey.core.entity.Credential
import com.crosscert.passkey.core.mds.MdsAaguidCache
import com.crosscert.passkey.core.repository.CredentialAuthEventRepository
import com.crosscert.passkey.core.repository.CredentialRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Base64
import java.util.HexFormat
import java.util.Locale
import java.util.UUID

private val log = LoggerFactory.getLogger(CredentialAdminService::class.java)

/** Last 12 chars of a base64url id — never the full id. Short ids masked. */
private fun idTail(id: String?): String {
    if (id == null) return "null"
    if (id.length <= 12) return "***"
    return "..." + id.substring(id.length - 12)
}

@Service
class CredentialAdminService(
    private val creds: CredentialRepository,
    private val mds: MdsAaguidCache,
    private val audit: AuditLogService,
    private val tenantBoundary: TenantBoundary,
    private val authEvents: CredentialAuthEventRepository,
) {

    @Transactional(readOnly = true)
    fun list(tenantId: UUID, page: Int, size: Int, q: String?): PageView<CredentialView> {
        tenantBoundary.assertCanAccessTenant(tenantId)
        val cappedSize = Math.min(Math.max(size, 1), 200)
        // Ordering is owned by each query's own ORDER BY — findAllByTenantId (JPQL) and
        // searchByTenantId (native) both use lastUsedAt desc nulls last, id desc. Spring Data
        // does not push Pageable Sort into native SQL and would duplicate it for JPQL, so the
        // PageRequest stays unsorted.
        val pageReq = PageRequest.of(Math.max(page, 0), cappedSize)

        // Java String.isBlank 와 같은 판정(Character.isWhitespace) — Kotlin isBlank 는 NBSP 등도 공백으로 본다.
        val rows = if (q == null || q.all(Character::isWhitespace)) {
            creds.findAllByTenantId(tenantId, pageReq)
        } else {
            creds.searchByTenantId(tenantId, normalizeQ(q), pageReq)
        }

        log.debug(
            "list credentials tenant={} page={} size={} q={} totalElements={}",
            tenantId, page, cappedSize, q, rows.totalElements,
        )

        // Collect the page's distinct AAGUIDs and resolve authenticatorName
        // for all of them in a single Redis round-trip (MGET), instead of
        // toView issuing one GET per row (up to 200 sequential round-trips per
        // page). Same batching shape as ActivityService.snapshot's
        // findAllById(tenantIds) — see toView below.
        val authenticatorNames = lookupAuthenticatorNames(rows.content)
        return PageView.from(rows.map { c -> toView(c, authenticatorNames) })
    }

    /** Distinct, order-preserving AAGUIDs across the page → one multiLookup call. */
    private fun lookupAuthenticatorNames(rows: List<Credential>): Map<UUID, MdsAaguidCache.Entry> {
        val distinctByUuid = LinkedHashMap<UUID, ByteArray>()
        for (c in rows) {
            val aaguid = c.aaguid ?: continue
            distinctByUuid.putIfAbsent(MdsAaguidCache.canonicalAaguid(aaguid), aaguid)
        }
        if (distinctByUuid.isEmpty()) return java.util.Map.of()
        return mds.multiLookup(ArrayList(distinctByUuid.values))
    }

    @Transactional(readOnly = true)
    fun listAuthEvents(
        tenantId: UUID,
        credentialIdB64: String,
        page: Int,
        size: Int,
    ): PageView<CredentialAdminDto.AuthEventView> {
        tenantBoundary.assertCanAccessTenant(tenantId)
        val credId: ByteArray = try {
            Base64.getUrlDecoder().decode(credentialIdB64)
        } catch (e: IllegalArgumentException) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "credentialId 가 base64url 형식이 아님")
        }
        val c = creds.findByCredentialId(credId)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND, "credential 없음") }

        // credential 이 경로 tenantId 소속인지 확인한다. 위 assertCanAccessTenant 는 경로 tenantId 만 보고,
        // findByCredentialId 의 Hibernate @Filter 는 RP_ADMIN 의 활성 RP 로만 걸리며 PLATFORM_OPERATOR 에겐 없다 —
        // 다른 테넌트 credential 을 걸러내는 것은 이 비교다. 저장된 tenantId 가 null 이면 equals 호출에서 NPE.
        if (!c.tenantId.equals(tenantId)) {
            throw BusinessException(ErrorCode.ACCESS_DENIED, "tenant boundary 위반")
        }

        val cappedSize = Math.min(Math.max(size, 1), 200)
        val rows = authEvents.findByTenantIdAndCredentialIdOrderByCreatedAtDesc(
            tenantId, c.id, PageRequest.of(Math.max(page, 0), cappedSize),
        )
        return PageView.from(
            rows.map { e ->
                CredentialAdminDto.AuthEventView(e.result, e.failureReason, e.signCount, e.createdAt)
            },
        )
    }

    @Transactional
    fun revoke(tenantId: UUID, credentialIdB64: String, actorId: UUID?, actorEmail: String?) {
        tenantBoundary.assertCanAccessTenant(tenantId)
        val credId: ByteArray = try {
            Base64.getUrlDecoder().decode(credentialIdB64)
        } catch (e: IllegalArgumentException) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "credentialId 가 base64url 형식이 아님")
        }

        val c = creds.findByCredentialIdForUpdate(credId)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND, "credential 없음") }

        // 위 listAuthEvents 와 같은 소속 확인(경로 tenantId 만 검사하는 boundary 를 보완). 저장된 tenantId 가 null 이면 NPE.
        if (!c.tenantId.equals(tenantId)) {
            log.warn(
                "cross-tenant revoke attempt: pathTenant={} actualTenant={} credentialId={}",
                tenantId, c.tenantId, idTail(credentialIdB64),
            )
            throw BusinessException(ErrorCode.ACCESS_DENIED, "tenant boundary 위반")
        }

        val aaguid: ByteArray? = c.aaguid
        val userHandle: ByteArray? = c.userHandle
        creds.delete(c)

        val payload = HashMap<String, Any?>()
        payload["tenantId"] = tenantId.toString()
        payload["aaguidHex"] = if (aaguid == null) null else HexFormat.of().formatHex(aaguid)
        payload["userHandleB64url"] = Base64.getUrlEncoder().withoutPadding().encodeToString(userHandle)

        audit.append(
            AuditAppendRequest(
                actorId, actorEmail, "CREDENTIAL_REVOKE",
                "CREDENTIAL", credentialIdB64,
                tenantId,
                payload,
            ),
        )

        log.warn("credential revoked: id={} reason=admin tenantId={}", idTail(credentialIdB64), tenantId)
    }

    private fun toView(c: Credential, authenticatorNames: Map<UUID, MdsAaguidCache.Entry>): CredentialView {
        val aaguid: ByteArray? = c.aaguid
        val aaguidHex = if (aaguid == null) null else HexFormat.of().formatHex(aaguid)
        // Batched lookup: authenticatorNames was resolved once for the whole
        // page via MdsAaguidCache.multiLookup — no per-row Redis round-trip here.
        val entry: MdsAaguidCache.Entry? =
            if (aaguid == null) null else authenticatorNames[MdsAaguidCache.canonicalAaguid(aaguid)]
        val authName = if (entry == null || entry.statuses().isEmpty()) null else entry.statuses().joinToString(",")
        return CredentialView(
            Base64.getUrlEncoder().withoutPadding().encodeToString(c.credentialId),
            Base64.getUrlEncoder().withoutPadding().encodeToString(c.userHandle),
            c.label,
            aaguidHex, authName,
            c.attestationFmt, c.transports,
            c.signCount, c.lastUsedAt, c.createdAt,
        )
    }

    /**
     * base64url 시도 → 성공 시 hex 변환. 실패 시 q 그대로 (운영자가 hex 일부 직접 입력 가능).
     * LIKE wildcard 는 \ % _ → \\ \% \_ 로 escape (CredentialRepository 의 ESCAPE '\\' 절과 짝).
     * 소문자화는 기본 로캘 기준(Locale.getDefault()).
     */
    private fun normalizeQ(q: String): String {
        val hexOrRaw = try {
            val bytes = Base64.getUrlDecoder().decode(q)
            HexFormat.of().formatHex(bytes).lowercase(Locale.getDefault())
        } catch (e: IllegalArgumentException) {
            q.lowercase(Locale.getDefault())
        }
        return hexOrRaw.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }
}
