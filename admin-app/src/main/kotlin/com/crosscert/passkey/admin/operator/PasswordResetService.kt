package com.crosscert.passkey.admin.operator

import com.crosscert.passkey.core.entity.AdminPasswordResetToken
import com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository
import com.crosscert.passkey.core.repository.AdminUserRepository
import com.crosscert.passkey.core.util.CryptoUtils
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime

private val log = LoggerFactory.getLogger(PasswordResetService::class.java)

private val TOKEN_TTL: Duration = Duration.ofHours(1)
private const val URL_PREFIX = "/reset-password?token="

/**
 * Admin self-service password reset (P1-6).
 *
 * sha-256 token_hash, MailSender, 1회용 토큰.
 * request 는 enumeration 방지를 위해 사용자 존재 여부와 무관하게 동일하게 동작
 * (없으면 조용히 no-op). 메일은 [AfterCommitMailDispatcher] 로 커밋 이후 전용
 * 스레드에서 보내 응답 시간이 SMTP 왕복에 좌우되지 않게 한다(S9). confirm 은 lockout 리셋.
 */
@Service
class PasswordResetService(
    private val tokens: AdminPasswordResetTokenRepository,
    private val users: AdminUserRepository,
    private val mail: AfterCommitMailDispatcher,
    private val encoder: PasswordEncoder,
    private val clock: Clock,
    private val env: Environment,
) {

    /** 원 Java 필드처럼 주입 전(new 로 만든 단위 테스트)에는 null — 링크는 "null/reset-password?..." 가 된다. */
    @field:Value("\${admin.invite.base-url:http://localhost:5173}")
    private var baseUrl: String? = null

    /**
     * G11 fail-fast — see [BaseUrlValidation]. SignupRequestService 도 같은
     * admin.invite.base-url 속성으로 승인 메일의 로그인 링크를 만든다.
     * 원 Java 는 package-private — 호출자는 Spring(@PostConstruct)뿐이라 internal.
     */
    @PostConstruct
    internal fun validateBaseUrlForProd() {
        BaseUrlValidation.assertNotLocalhostFallbackInProd(env, baseUrl, "admin.invite.base-url")
    }

    @Transactional
    fun request(email: String) {
        val userOpt = users.findByEmail(email)
        if (userOpt.isEmpty) {
            log.info("password reset requested for unknown email: {}", CryptoUtils.maskEmail(email))
            return
        }
        val user = userOpt.get()
        val plaintext = CryptoUtils.randomToken("rst_")
        val prefix = plaintext.substring(0, 8)
        val now = OffsetDateTime.now(clock)
        val tok = AdminPasswordResetToken(
            user.id, CryptoUtils.sha256Hex(plaintext), prefix, now.plus(TOKEN_TTL), now,
        )
        tokens.save(tok)

        val resetUrl = baseUrl + URL_PREFIX + plaintext
        // S9: 커밋 이후 전용 스레드에서 발송 — 메일 실패해도 토큰은 발급됨(dispatcher 가 삼킴).
        mail.sendAfterCommit(
            email, "비밀번호 재설정 — Passkey2",
            String.format(
                "재설정 URL: <a href=\"%s\">%s</a><br>만료: %s",
                resetUrl, resetUrl, tok.expiresAt,
            ),
        )
        log.info("password reset token issued: email={} tokenPrefix={}", CryptoUtils.maskEmail(email), prefix)
    }

    /** plaintext/newPassword nullable: 컨트롤러가 JSON 본문 값(누락 시 null)을 그대로 넘긴다 — 원 흐름 유지. */
    @Transactional
    fun confirm(plaintext: String?, newPassword: String?) {
        val hash = CryptoUtils.sha256Hex(plaintext)
        val tok = tokens.findByTokenHash(hash)
            .orElseThrow { IllegalArgumentException("invalid token") }
        val now = OffsetDateTime.now(clock)
        if (tok.isConsumed) throw IllegalArgumentException("token already used")
        if (tok.isExpired(now)) throw IllegalArgumentException("token expired")

        val user = users.findById(tok.adminUserId)
            .orElseThrow { IllegalArgumentException("invalid token") }
        user.bcryptHash = encoder.encode(newPassword)
        user.recordSuccessfulLogin()
        // 동시 confirm race 는 의도적으로 용인: 토큰은 설계상 1회용이고 operator 가
        // 직접 시작하며, double-spend 시 최악이 last-writer-wins 비밀번호 재설정(가치 누출 없음).
        // recovery code(consume)와 달리 conditional update 를 쓰지 않는 이유.
        tok.consume(now)
        log.info(
            "password reset confirmed: email={} tokenPrefix={}",
            CryptoUtils.maskEmail(user.email), tok.tokenPrefix,
        )
    }

    /** 원 Java 는 package-private(같은 패키지 테스트 전용) — 같은 모듈 테스트만 부르므로 internal. */
    internal fun hashForTest(plaintext: String?): String = CryptoUtils.sha256Hex(plaintext)
}
