package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.core.entity.AdminUserRecoveryCode
import com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository
import com.crosscert.passkey.core.util.CryptoUtils
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID

private const val CODE_COUNT = 10
private const val GROUP_LEN = 4 // "xxxx-xxxx"
private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 혼동 문자 제외
private val RNG = SecureRandom()

/**
 * Admin MFA 1회용 recovery code 발급/소비 (P0 잔여 A).
 *
 * 발급(generate): 기존 코드 전량 폐기 후 N 개 평문 생성 → sha-256 hash 만 저장 →
 * 평문 List 를 1회 반환(이후 평문 복구 불가). 소비(consume): 미사용 매칭 코드 1개를
 * used_at 마킹(one-shot). 평문은 Base32 4-4 형식("xxxx-xxxx")으로 입력 편의 제공.
 *
 * 매칭은 평문이 아닌 sha-256 hex 를 DB 조건(markUsed 의 WHERE)으로 비교한다 — 앱 코드에 문자열 비교 없음.
 */
@Service
class RecoveryCodeService(
    private val repo: AdminUserRecoveryCodeRepository,
    private val clock: Clock,
) {

    @Transactional
    fun generate(adminUserId: UUID?): List<String> {
        repo.deleteByAdminUserId(adminUserId)
        val codes = LinkedHashSet<String>()
        while (codes.size < CODE_COUNT) {
            codes.add(randomCode())
        }
        for (code in codes) {
            repo.save(AdminUserRecoveryCode(adminUserId, CryptoUtils.sha256Hex(code)))
        }
        return ArrayList(codes)
    }

    @Transactional
    fun consume(adminUserId: UUID?, code: String?): Boolean {
        // Java String.isBlank 와 같은 판정(Character.isWhitespace) — Kotlin isBlank 는 NBSP 등도 공백으로 본다.
        if (code == null || code.all(Character::isWhitespace)) return false
        val hash = CryptoUtils.sha256Hex(normalize(code))
        return repo.markUsed(adminUserId, hash, OffsetDateTime.now(clock)) == 1
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
