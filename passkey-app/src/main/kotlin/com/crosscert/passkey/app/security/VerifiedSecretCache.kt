package com.crosscert.passkey.app.security

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * P1: API 키 secret 의 BCrypt 검증 *성공*만 짧게 기억해, 같은 키의 반복
 * 요청에서 BCrypt-12 를 생략한다.
 *
 * 항목은 apiKeyId 별로 (검증 당시 keyHash, SHA-256(secret), verifiedAt) 이다.
 * 적중 조건은 같은 apiKeyId·같은 keyHash·같은 secret 다이제스트(상수 시간 비교)·
 * `verifiedAt <= now < verifiedAt + ttl` 모두이다. 따라서 적중은 "이 keyHash 로
 * BCrypt 를 이미 통과한 바로 그 secret" 일 때만 일어나며, BCrypt 가 거부할 secret 을
 * 수락하는 일은 없다. 그 밖의 모든 경우 호출자는 BCrypt 로 검증한다.
 *
 * DB 조회와 active·만료 검사는 이 캐시와 무관하게 호출자가 매 요청 수행한다.
 * 크기는 `maxEntries` 로 제한한다 — 가득 차면 만료 항목을 비우고, 그래도
 * 가득 차면 새 항목을 저장하지 않는다(그 키는 계속 BCrypt 로 검증될 뿐 판정은 같다).
 */
internal class VerifiedSecretCache(private val ttl: Duration, private val maxEntries: Int) {

    // 일반 class: ByteArray 필드가 있으므로 data class 의 구조적 equals 를 쓰지 않는다.
    // remove(key, value) 는 참조 동일성(같은 Entry 인스턴스)으로 비교한다 — 원본과 동일.
    private class Entry(val keyHash: String?, val secretDigest: ByteArray, val verifiedAt: Instant)

    private val entries = ConcurrentHashMap<UUID, Entry>()

    init {
        require(!ttl.isNegative && !ttl.isZero) { "ttl must be positive" }
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    /** 이 apiKeyId·keyHash 로 TTL 안에 BCrypt 검증을 통과한 secret 과 정확히 같으면 true. */
    fun isVerified(apiKeyId: UUID, keyHash: String?, secret: String, now: Instant): Boolean {
        // 항목 유무와 무관하게 다이제스트를 먼저 계산해 미스 경로의 비용을 일정하게 둔다.
        val candidate = digest(secret)
        val e = entries[apiKeyId] ?: return false
        if (!isFresh(e, now) || e.keyHash != keyHash) {
            entries.remove(apiKeyId, e)
            return false
        }
        return MessageDigest.isEqual(e.secretDigest, candidate)
    }

    /** BCrypt 검증 성공 직후에만 호출한다. */
    fun remember(apiKeyId: UUID, keyHash: String?, secret: String, now: Instant) {
        if (entries.size >= maxEntries && !entries.containsKey(apiKeyId)) {
            entries.values.removeIf { !isFresh(it, now) }
            if (entries.size >= maxEntries) {
                return
            }
        }
        entries[apiKeyId] = Entry(keyHash, digest(secret), now)
    }

    fun invalidate(apiKeyId: UUID) {
        entries.remove(apiKeyId)
    }

    fun size(): Int = entries.size

    private fun isFresh(e: Entry, now: Instant): Boolean =
        // 시계가 뒤로 간 경우(now < verifiedAt)도 신선하지 않은 것으로 본다.
        !now.isBefore(e.verifiedAt) && now.isBefore(e.verifiedAt.plus(ttl))

    private companion object {
        /**
         * SHA-256 over the String's UTF-16 code units. Injective on String (unlike
         * toByteArray(UTF_8), which maps lone surrogates to '?'), so two different
         * secrets never share an input.
         */
        fun digest(secret: String): ByteArray {
            val buf = ByteBuffer.allocate(secret.length * 2)
            for (i in secret.indices) {
                buf.putChar(secret[i])
            }
            return MessageDigest.getInstance("SHA-256").digest(buf.array())
        }
    }
}
