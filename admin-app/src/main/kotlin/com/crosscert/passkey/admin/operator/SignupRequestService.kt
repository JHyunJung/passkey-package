package com.crosscert.passkey.admin.operator

import com.crosscert.passkey.admin.audit.AuditAppendRequest
import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.core.entity.AdminSignupRequest
import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.entity.AdminUserTenant
import com.crosscert.passkey.core.mail.MailSender
import com.crosscert.passkey.core.repository.AdminSignupRequestRepository
import com.crosscert.passkey.core.repository.AdminUserRepository
import com.crosscert.passkey.core.repository.AdminUserTenantRepository
import com.crosscert.passkey.core.util.CryptoUtils
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.OffsetDateTime
import java.util.Locale
import java.util.Objects
import java.util.UUID

private val log = LoggerFactory.getLogger(SignupRequestService::class.java)

// trim { it <= ' ' }: 원 Java String.trim 과 같은 판정(Kotlin trim() 은 유니코드 공백까지 자른다).
private fun normalize(email: String): String = email.trim { it <= ' ' }.lowercase(Locale.ROOT)

/**
 * 어드민 가입 요청·승인 흐름.
 *
 * request 는 공개 엔드포인트에서 호출된다. 계정 열거를 막기 위해 이메일 존재
 * 여부·대기 상한 도달 여부와 무관하게 예외 없이 반환하고, 호출측은 항상 같은
 * 응답을 준다. 저장하지 않는 경우는 마스킹 이메일로 WARN 만 남긴다.
 *
 * approve/reject 는 PLATFORM_OPERATOR 전용. 요청 행 삭제를 건수 반환 쿼리로
 * 먼저 실행해 두 관리자의 동시 처리를 DB 에서 직렬화한다(0건이면 409).
 */
@Service
class SignupRequestService(
    private val requests: AdminSignupRequestRepository,
    private val users: AdminUserRepository,
    private val mappings: AdminUserTenantRepository,
    private val audit: AuditLogService,
    private val mail: MailSender,
    private val encoder: PasswordEncoder,
    private val clock: Clock,
    private val env: Environment,
) {

    /**
     * 승인 메일의 로그인 URL 앞부분. PasswordResetService 와 같은 속성을 읽는다.
     * 원 Java 필드처럼 주입 전(new 로 만든 단위 테스트)에는 null — 링크는 "null/admin" 이 된다.
     */
    @field:Value("\${admin.invite.base-url:http://localhost:5173}")
    private var baseUrl: String? = null

    /**
     * QA 전용. true 면 PLATFORM_OPERATOR 로 가입을 요청한 경우 승인 없이 바로 계정을 만든다.
     * admin 주소에 닿는 누구나 최고 관리자가 될 수 있으므로 운영에서는 켜지 않는다.
     * (필드 주입 유지 — 테스트가 이 필드명을 리플렉션으로 바꾼다)
     */
    @field:Value("\${admin.signup.auto-approve-platform-operator:false}")
    private var autoApprovePlatformOperator: Boolean = false

    /**
     * G11 fail-fast — prod 에서 localhost 기본값으로 뜨면 승인 메일 링크가 깨진다.
     * 원 Java 는 package-private — Kotlin 에 대응 가시성이 없어 public 으로 둔다.
     */
    @PostConstruct
    fun validateBaseUrlForProd() {
        BaseUrlValidation.assertNotLocalhostFallbackInProd(env, baseUrl, "admin.invite.base-url")
        if (autoApprovePlatformOperator) {
            log.warn("admin.signup.auto-approve-platform-operator=true — PLATFORM_OPERATOR 가입 요청을 승인 없이 활성화한다(QA 전용)")
        }
    }

    /**
     * 의도적으로 @Transactional 을 붙이지 않는다 — save() 의 UNIQUE 위반(동시 요청)을
     * 여기서 잡아 삼키려면, 예외가 트랜잭션을 rollback-only 로 만들지 않아야 한다.
     * 각 리포지토리 호출은 자체 트랜잭션으로 돈다.
     *
     * @return 승인 없이 계정이 바로 만들어졌으면 true(자동 승인), 그 외(대기 등록·스킵)는 false
     */
    fun request(req: SignupRequestDto.Create): Boolean {
        // 원 Java 의 email.trim() 과 같은 지점의 NPE(@NotBlank 검증을 거친 컨트롤러 경로에선 불가).
        val email = normalize(req.email!!)
        val masked = CryptoUtils.maskEmail(email)

        // 타이밍 이퀄라이제이션(열거 저항) — bcrypt(strength 12, 수백 ms)는 아래
        // 세 확인(스킵 경로 포함)을 지배하는 비용이다. 존재/대기/상한 확인보다
        // 먼저, 스킵 여부와 무관하게 항상 해싱해야 공개 엔드포인트 응답시간으로
        // 이메일 존재 여부를 추정할 수 없다(나머지 DB 왕복 차이는 수 ms로 무해).
        val bcryptHash: String? = encoder.encode(req.password)

        if (users.findByEmail(email).isPresent) {
            log.warn("signup request skipped: email={} reason=already-admin", masked)
            return false
        }
        if (autoApprovePlatformOperator && "PLATFORM_OPERATOR" == req.role) {
            return activatePlatformOperator(email, bcryptHash, masked)
        }
        if (requests.existsByEmail(email)) {
            log.warn("signup request skipped: email={} reason=already-pending", masked)
            return false
        }
        if (requests.count() >= MAX_PENDING) {
            log.warn("signup request skipped: email={} reason=pending-cap max={}", masked, MAX_PENDING)
            return false
        }

        val rawReason = req.reason
        // Java String.isBlank / trim 과 같은 판정(Character.isWhitespace / <= ' ') — Kotlin 기본판은 유니코드 공백까지 본다.
        val reason = if (rawReason == null || rawReason.all(Character::isWhitespace)) null else rawReason.trim { it <= ' ' }
        val row = AdminSignupRequest(email, bcryptHash, reason, OffsetDateTime.now(clock))
        try {
            requests.save(row)
        } catch (e: DataIntegrityViolationException) {
            // 같은 이메일의 동시 요청 — 먼저 들어간 쪽이 남는다. 응답은 동일.
            log.warn("signup request skipped: email={} reason=concurrent-duplicate", masked)
            return false
        }
        log.info("signup request accepted: email={}", masked)
        return false
    }

    /** 자동 승인 — approve() 의 계정 생성과 같은 상태(ACTIVE + enabled)로 만들고, 행위자는 본인이다. */
    private fun activatePlatformOperator(email: String, bcryptHash: String?, masked: String?): Boolean {
        val user = AdminUser.create()
        user.email = email
        user.bcryptHash = bcryptHash
        user.role = "PLATFORM_OPERATOR"
        user.status = "ACTIVE"
        user.isEnabled = true
        user.createdBy = "self-signup"
        val saved = try {
            users.saveAndFlush(user)
        } catch (e: DataIntegrityViolationException) {
            log.warn("signup auto-approve skipped: email={} reason=concurrent-duplicate", masked)
            return false
        }
        audit.append(
            AuditAppendRequest(
                saved.id, email, "ADMIN_SIGNUP_SELF_APPROVE",
                "ADMIN_USER", saved.id.toString(), null, java.util.Map.of<String, Any?>("role", "PLATFORM_OPERATOR"),
            ),
        )
        log.warn("signup auto-approved: email={} role=PLATFORM_OPERATOR", masked)
        return true
    }

    @Transactional(readOnly = true)
    fun list(): List<SignupRequestDto.View> =
        requests.findAllByOrderByRequestedAtAsc()
            .map { r -> SignupRequestDto.View(r.id, r.email, r.reason, r.requestedAt) }

    @Transactional
    fun approve(
        requestId: UUID,
        body: SignupRequestDto.Approve,
        actorId: UUID?,
        actorEmail: String?,
    ): AdminUserDto.View {
        // 1. 역할 규칙 — 초대 흐름의 검증을 그대로 옮김.
        if ("PLATFORM_OPERATOR" != body.role && "RP_ADMIN" != body.role) {
            throw IllegalArgumentException("Invalid role: " + body.role)
        }
        val tenantIds: List<UUID> = if (body.tenantIds == null) java.util.List.of() else body.tenantIds
        // 원 Java 와 같이 Objects::isNull 로 판정(Java 호출자·JSON 이 null 원소를 넣을 수 있다).
        if (tenantIds.stream().anyMatch(Objects::isNull)) {
            throw IllegalArgumentException("tenantIds must not contain null")
        }
        if ("RP_ADMIN" == body.role && tenantIds.isEmpty()) {
            throw IllegalArgumentException("RP_ADMIN requires at least one tenant")
        }
        if ("PLATFORM_OPERATOR" == body.role && !tenantIds.isEmpty()) {
            throw IllegalArgumentException("PLATFORM_OPERATOR must not have tenant")
        }

        // 2. 요청 조회
        val req = requests.findById(requestId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "signup request not found") }
        val email = req.email
        val bcryptHash = req.bcryptHash

        // 3. 그 사이 같은 이메일 계정이 생겼으면 409
        if (users.findByEmail(email).isPresent) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Email already exists")
        }

        // 4. 경합 판정 — 반드시 admin_user INSERT 보다 먼저. deleteIfPresent 는
        //    clearAutomatically 라 영속성 컨텍스트를 비운다; 뒤에 만드는 엔티티는
        //    무관하지만 앞에 save() 만 해둔 엔티티는 유실된다.
        if (requests.deleteIfPresent(requestId) == 0) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "signup request already handled")
        }

        // 5. 계정 생성 — 승인 즉시 로그인 가능(ACTIVE + enabled). 해시는 요청의 것을 복사.
        val user = AdminUser.create()
        user.email = email
        user.bcryptHash = bcryptHash
        user.role = body.role
        user.status = "ACTIVE"
        user.isEnabled = true
        user.createdBy = actorEmail
        // saveAndFlush (not save) — a plain save() defers the INSERT (and its
        // UNIQUE(email) violation) to commit, which happens *outside* this
        // method after the exception-translation below has already been
        // skipped, surfacing as an uncaught 500. Flushing here forces the
        // violation to throw inside the try, where it can still be mapped
        // to 409; the exception propagating out of @Transactional still
        // rolls the whole approval back.
        val saved = try {
            users.saveAndFlush(user)
        } catch (e: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Email already exists")
        }

        val distinctTenants: List<UUID> = java.util.List.copyOf(LinkedHashSet(tenantIds))
        for (tid in distinctTenants) {
            mappings.save(AdminUserTenant.of(saved.id, tid, actorEmail))
        }

        // 6. 감사
        audit.append(
            AuditAppendRequest(
                actorId, actorEmail, "ADMIN_SIGNUP_APPROVE",
                "ADMIN_USER", saved.id.toString(), null,
                java.util.Map.of<String, Any?>("role", body.role, "tenantCount", distinctTenants.size),
            ),
        )
        log.info(
            "signup request approved: email={} role={} tenantCount={} by={}",
            CryptoUtils.maskEmail(email), body.role, distinctTenants.size, CryptoUtils.maskEmail(actorEmail),
        )

        // 7. 결과 메일 — 실패해도 승인은 성공
        val loginUrl = baseUrl + "/admin"
        sendQuietly(
            email, "관리자 계정 승인 — Passkey2",
            String.format("가입 요청이 승인되었습니다.<br>로그인: <a href=\"%s\">%s</a>", loginUrl, loginUrl),
        )

        return AdminUserDto.View(
            saved.id, saved.email, saved.role,
            if (saved.status != null) saved.status else "ACTIVE",
            distinctTenants,
            saved.createdAt, saved.lastLoginAt,
            saved.suspendedAt, saved.createdBy, saved.isMfaEnabled,
        )
    }

    @Transactional
    fun reject(requestId: UUID, actorId: UUID?, actorEmail: String?) {
        val req = requests.findById(requestId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "signup request not found") }
        val email = req.email

        if (requests.deleteIfPresent(requestId) == 0) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "signup request already handled")
        }

        audit.append(
            AuditAppendRequest(
                actorId, actorEmail, "ADMIN_SIGNUP_REJECT",
                "ADMIN_SIGNUP_REQUEST", requestId.toString(), null, java.util.Map.of<String, Any?>(),
            ),
        )
        log.info(
            "signup request rejected: email={} by={}",
            CryptoUtils.maskEmail(email), CryptoUtils.maskEmail(actorEmail),
        )

        sendQuietly(
            email, "관리자 계정 요청 거절 — Passkey2",
            "가입 요청이 거절되었습니다. 문의는 플랫폼 운영자에게 하세요.",
        )
    }

    private fun sendQuietly(to: String?, subject: String, body: String) {
        try {
            mail.send(to, subject, body)
        } catch (e: Exception) {
            log.warn("signup result mail failed: email={} cause={}", CryptoUtils.maskEmail(to), e.toString())
        }
    }

    companion object {
        /** 승인 대기 상한. 도달하면 새 요청을 조용히 받지 않는다(폭주 방지). 원 Java 는 package-private static final. */
        internal const val MAX_PENDING = 100
    }
}
