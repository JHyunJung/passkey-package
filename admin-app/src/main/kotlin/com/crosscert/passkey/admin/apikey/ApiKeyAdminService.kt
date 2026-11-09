package com.crosscert.passkey.admin.apikey

import com.crosscert.passkey.admin.audit.AuditAppendRequest
import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.auth.TenantBoundary
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.entity.ApiKey
import com.crosscert.passkey.core.repository.ApiKeyRepository
import com.crosscert.passkey.core.repository.TenantRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64
import java.util.Locale
import java.util.UUID

private val log = LoggerFactory.getLogger(ApiKeyAdminService::class.java)

private const val PREFIX_HEADER = "pk_"
private const val PREFIX_RANDOM_BYTES = 6 // 6 bytes → 8 b64url chars → 11-char prefix
private const val SECRET_RANDOM_BYTES = 32 // 32 bytes → 43 b64url chars

/** api_key_scope V21 CHECK 제약과 동일한 허용 scope 집합. */
private val ALLOWED_SCOPES: Set<String> = java.util.Set.of("registration", "authentication", "admin")

/** scope 를 소문자 정규화 + whitelist 검증. 알 수 없는 값이면 BusinessException(400). */
private fun normalizeScope(raw: String?): String {
    // trim { it <= ' ' }: 원 Java String.trim 과 같은 판정(Kotlin trim() 은 유니코드 공백까지 자른다).
    val s = if (raw == null) "" else raw.trim { it <= ' ' }.lowercase(Locale.ROOT)
    if (!ALLOWED_SCOPES.contains(s)) {
        throw BusinessException(ErrorCode.INVALID_INPUT, "unknown api key scope: $raw")
    }
    return s
}

@Service
class ApiKeyAdminService(
    private val repo: ApiKeyRepository,
    private val audit: AuditLogService,
    private val encoder: PasswordEncoder,
    private val random: SecureRandom,
    private val clock: Clock,
    private val tenantBoundary: TenantBoundary,
    private val tenants: TenantRepository,
) {

    /** P1-5: 구 키가 살아있는 grace 창. 기본 24h. (필드 주입 유지 — 테스트가 이 필드명을 리플렉션으로 바꾼다) */
    @field:Value("\${passkey.api-key.rotation.grace:PT24H}")
    private var rotationGrace: Duration = Duration.ofHours(24)

    @Transactional(readOnly = true)
    fun list(tenantId: String?): List<ApiKeyAdminDto.ApiKeyView> {
        val scopeTid: UUID? = tenantBoundary.currentTenantScope()
        return repo.findAll()
            .filter { k ->
                val kid: UUID? = k.tenantId
                if (scopeTid != null && scopeTid != kid) return@filter false
                if (tenantId != null && tenantId != (if (kid == null) null else kid.toString())) return@filter false
                true
            }
            .map { ApiKeyAdminDto.ApiKeyView.from(it) }
    }

    @Transactional
    fun issue(
        req: ApiKeyAdminDto.ApiKeyCreateRequest,
        actorId: UUID?,
        actorEmail: String?,
    ): ApiKeyAdminDto.ApiKeyCreateResponse {
        tenantBoundary.assertCanAccessTenant(req.tenantId)

        // P0-2 seam: suspended 테넌트엔 어떤 경로로도 새 키가 살아남으면 안 됨.
        // suspend() 가 기존 키를 일괄 revoke 하고 ceremony start 가 거부하더라도,
        // 운영자가 정지 상태에서 새 키를 발급하면 그 키로 finish/self-service 가 동작한다.
        // Spring Data findById 는 non-null id 계약(JSpecify). req.tenantId 는 @NotNull 검증 대상 DTO 필드라 컨트롤러 경로에선
        // null 이 오지 않는다. null 이면 리포지토리 호출 전에 SimpleJpaRepository 와 같은 문구의 IllegalArgumentException —
        // 원 Java 는 findById(null) 안의 IAE 라 실제 Spring 컨텍스트에선 InvalidDataAccessApiUsageException 으로 번역됐다(도달 불가 경로).
        val tenant = tenants.findById(req.tenantId ?: throw IllegalArgumentException("The given id must not be null"))
            .orElseThrow { BusinessException(ErrorCode.TENANT_NOT_FOUND) }
        if (tenant.isSuspended) {
            throw BusinessException(
                ErrorCode.TENANT_SUSPENDED,
                "cannot issue api key for suspended tenant: " + req.tenantId,
            )
        }

        val prefix = generateUniquePrefix()
        val secret = b64url(SECRET_RANDOM_BYTES)
        val hash = encoder.encode(secret)

        // P1-5 enforcement seam: scope 를 whitelist 검증 + 소문자 정규화한다.
        // 필터가 exact match("registration") 로 enforce 하므로 "Registration"·오타가
        // verbatim 저장되면 200 받지만 모든 RP 호출이 403 인 silent dead key 가 된다.
        val normalized = LinkedHashSet<String>()
        // 원 Java for-each 와 동일: scopes 가 null 이면 NPE(@NotEmpty 검증을 거친 컨트롤러 경로에선 불가).
        for (scope in req.scopes!!) {
            normalized.add(normalizeScope(scope))
        }

        val key = ApiKey(req.tenantId, prefix, hash, req.name)
        for (scope in normalized) {
            key.addScope(scope)
        }
        // 월 단위 만료: now + N개월 (KST). null 이면 무기한(expires_at=NULL, 기존 키와 동일).
        var expiresAt: OffsetDateTime? = null
        val months = req.expiresInMonths
        if (months != null) {
            expiresAt = OffsetDateTime.now(clock).plusMonths(months.toLong())
            key.expireAt(expiresAt)
        }
        val saved = repo.saveAndFlush(key)

        val payload = LinkedHashMap<String, Any?>()
        payload["id"] = saved.id.toString()
        payload["prefix"] = prefix
        payload["tenantId"] = req.tenantId.toString()
        payload["name"] = req.name
        payload["scopes"] = normalized
        payload["expiresAt"] = if (expiresAt == null) null else expiresAt.toString()
        audit.append(
            AuditAppendRequest(
                actorId, actorEmail, "API_KEY_ISSUE",
                "API_KEY", saved.id.toString(),
                req.tenantId,
                payload,
            ),
        )

        log.info(
            "api-key issued: prefix={} name={} tenantId={} scopes={}",
            prefix, req.name, req.tenantId, normalized,
        )

        return ApiKeyAdminDto.ApiKeyCreateResponse(saved.id, prefix + secret, prefix, normalized, expiresAt)
    }

    @Transactional
    fun revoke(id: UUID, actorId: UUID?, actorEmail: String?) {
        val k = repo.findById(id)
            .orElseThrow { BusinessException(ErrorCode.API_KEY_NOT_FOUND) }
        tenantBoundary.assertCanAccessTenant(k.tenantId)
        k.revoke(OffsetDateTime.now(clock))
        repo.save(k)

        val payload = LinkedHashMap<String, Any?>()
        payload["prefix"] = k.keyPrefix
        payload["tenantId"] = k.tenantId.toString()
        audit.append(
            AuditAppendRequest(
                actorId, actorEmail, "API_KEY_REVOKE",
                "API_KEY", id.toString(),
                k.tenantId,
                payload,
            ),
        )

        log.warn("api-key revoked: prefix={} reason=admin tenantId={}", k.keyPrefix, k.tenantId)
    }

    /**
     * P1-5 무중단 로테이션: 구 키의 tenant/name/scopes 를 복사한 새 키를 발급하고,
     * 구 키의 expiresAt 을 now+grace 로 설정한다. grace 창 동안 두 키 모두 동작하다가
     * 구 키가 자동 만료된다. 신규 키 plaintext 는 이 응답에서 단 한 번만 노출된다.
     */
    @Transactional
    fun rotate(oldKeyId: UUID, actorId: UUID?, actorEmail: String?): ApiKeyAdminDto.ApiKeyRotateResponse {
        val old = repo.findById(oldKeyId)
            .orElseThrow { BusinessException(ErrorCode.API_KEY_NOT_FOUND) }
        tenantBoundary.assertCanAccessTenant(old.tenantId)

        val now = OffsetDateTime.now(clock)
        if (!old.isActive(now)) {
            throw BusinessException(
                ErrorCode.API_KEY_NOT_FOUND,
                "cannot rotate inactive (revoked/expired) key: $oldKeyId",
            )
        }

        val tenant = tenants.findById(old.tenantId)
            .orElseThrow { BusinessException(ErrorCode.TENANT_NOT_FOUND) }
        if (tenant.isSuspended) {
            throw BusinessException(
                ErrorCode.TENANT_SUSPENDED,
                "cannot rotate api key for suspended tenant: " + old.tenantId,
            )
        }

        val prefix = generateUniquePrefix()
        val secret = b64url(SECRET_RANDOM_BYTES)
        val hash = encoder.encode(secret)

        val fresh = ApiKey(old.tenantId, prefix, hash, old.name)
        for (scope in old.scopeValues) {
            fresh.addScope(scope)
        }
        val savedNew = repo.saveAndFlush(fresh)

        val oldExpiry = now.plus(rotationGrace)
        old.expireAt(oldExpiry)
        repo.save(old)

        val payload = LinkedHashMap<String, Any?>()
        payload["oldPrefix"] = old.keyPrefix
        payload["newPrefix"] = prefix
        payload["tenantId"] = old.tenantId.toString()
        payload["oldKeyExpiresAt"] = oldExpiry.toString()
        audit.append(
            AuditAppendRequest(
                actorId, actorEmail, "API_KEY_ROTATED",
                "API_KEY", savedNew.id.toString(), old.tenantId, payload,
            ),
        )

        log.warn(
            "api-key rotated: oldPrefix={} newPrefix={} tenantId={} oldExpiresAt={}",
            old.keyPrefix, prefix, old.tenantId, oldExpiry,
        )

        return ApiKeyAdminDto.ApiKeyRotateResponse(
            savedNew.id, prefix + secret, prefix, fresh.scopeValues, oldExpiry,
        )
    }

    private fun generateUniquePrefix(): String {
        for (attempt in 0 until 10) {
            val prefix = PREFIX_HEADER + b64url(PREFIX_RANDOM_BYTES)
            if (repo.findByKeyPrefix(prefix).isEmpty) return prefix
        }
        throw IllegalStateException("API key prefix exhausted — 10 collisions")
    }

    private fun b64url(bytes: Int): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }
}
