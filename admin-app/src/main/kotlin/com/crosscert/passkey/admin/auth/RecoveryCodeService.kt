package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.core.entity.AdminUserRecoveryCode
import com.crosscert.passkey.core.jwt.KeyEnvelope
import com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository
import com.crosscert.passkey.core.util.CryptoUtils
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val CODE_COUNT = 10
private const val GROUP_LEN = 4 // "xxxx-xxxx"
private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 혼동 문자 제외
private val RNG = SecureRandom()

/** master key 에서 recovery code 용 HMAC 키를 파생하는 용도 문자열. 바꾸면 기존 HMAC 코드가 모두 무효가 된다. */
private const val PEPPER_PURPOSE = "passkey/admin-recovery-code/v1"

/**
 * Admin MFA 1회용 recovery code 발급/소비 (P0 잔여 A).
 *
 * 발급(generate): 기존 코드 전량 폐기 후 N 개 평문 생성 → HMAC-SHA256 hex 만 저장 →
 * 평문 List 를 1회 반환(이후 평문 복구 불가). 소비(consume): 미사용 매칭 코드 1개를
 * used_at 마킹(one-shot). 평문은 Base32 4-4 형식("xxxx-xxxx")으로 입력 편의 제공.
 *
 * 매칭은 평문이 아닌 HMAC hex(전환 전 코드는 sha-256 hex)를 DB 조건(markUsed 의 WHERE)으로 비교한다 — 앱 코드에 문자열 비교 없음.
 */
@Service
class RecoveryCodeService(
    private val repo: AdminUserRecoveryCodeRepository,
    private val clock: Clock,
    envelope: KeyEnvelope,
) {

    /**
     * 저장 해시의 키(pepper). 코드는 32자 알파벳 8자(40비트)라 솔트 없는 SHA-256 은 DB 만 유출돼도 수 분 내 전수 대입된다.
     * DB 밖 비밀(passkey.key-envelope.master-key)에서 파생한 키로 HMAC 하면 DB 만으로는 대입할 수 없다.
     * 별도 환경변수 없이 기존 master key 를 쓰므로 master key 를 바꾸면(봉인된 서명 키·MFA secret 과 마찬가지로) 코드도 재발급해야 한다.
     */
    private val pepper = SecretKeySpec(envelope.deriveSubkey(PEPPER_PURPOSE), "HmacSHA256")

    private fun hmacHex(code: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(pepper)
        return CryptoUtils.hex(mac.doFinal(code.toByteArray(StandardCharsets.UTF_8)))
    }

    @Transactional
    fun generate(adminUserId: UUID?): List<String> {
        repo.deleteByAdminUserId(adminUserId)
        val codes = LinkedHashSet<String>()
        while (codes.size < CODE_COUNT) {
            codes.add(randomCode())
        }
        for (code in codes) {
            repo.save(AdminUserRecoveryCode(adminUserId, hmacHex(code)))
        }
        return ArrayList(codes)
    }

    @Transactional
    fun consume(adminUserId: UUID?, code: String?): Boolean {
        // Java String.isBlank 와 같은 판정(Character.isWhitespace) — Kotlin isBlank 는 NBSP 등도 공백으로 본다.
        if (code == null || code.all(Character::isWhitespace)) return false
        val normalized = normalize(code)
        val now = OffsetDateTime.now(clock)
        if (repo.markUsed(adminUserId, hmacHex(normalized), now) == 1) return true
        // HMAC 전환(2026-11-12) 전에 발급된 코드는 SHA-256 으로 저장돼 있다 — 재발급 전까지 계속 받는다.
        return repo.markUsed(adminUserId, CryptoUtils.sha256Hex(normalized), now) == 1
    }

    @Transactional(readOnly = true)
    fun remaining(adminUserId: UUID?): Long = repo.countByAdminUserIdAndUsedAtIsNull(adminUserId)
}

// trim { it <= ' ' }: 원 Java String.trim 과 같은 판정(Kotlin trim() 은 유니코드 공백까지 자른다).
private fun normalize(code: String): String =
    code.trim { it <= ' ' }.uppercase(Locale.ROOT).replace(" ", "")

private fun randomCode(): String {
    val sb = StringBuilder(GROUP_LEN * 2 + 1)
    for (i in 0 until GROUP_LEN * 2) {
        if (i == GROUP_LEN) sb.append('-')
        sb.append(ALPHABET[RNG.nextInt(ALPHABET.length)])
    }
    return sb.toString()
}
