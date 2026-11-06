package com.crosscert.passkey.app.fido2.challenge

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.Optional

private const val REG_PREFIX = "challenge:reg:"
private const val AUTH_PREFIX = "challenge:auth:"

/**
 * Spring's auto-configured ObjectMapper already has JavaTimeModule
 * registered by CoreJacksonConfig. We trust the caller's mapper;
 * tests inject one configured the same way.
 *
 * `take*` 는 의도적으로 `T?` 가 아닌 [Optional] 을 돌려준다: 키 부재는 `Optional.empty()`(→ 호출부 400),
 * 저장값이 JSON 리터럴 `null` 이면 `Optional.of(null)` 의 NPE(→ 500)로 두 경우를 구분한다.
 */
@Component
class ChallengeStore(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
) {

    fun putRegistration(token: String, value: RegistrationChallenge) {
        redis.opsForValue().set(REG_PREFIX + token, serialize(value), TTL)
    }

    // Atomic GETDEL ensures one-shot semantics under concurrent
    // /finish calls: even if two requests arrive with the same
    // token, only one observes the value; the other sees null.
    fun takeRegistration(token: String): Optional<RegistrationChallenge> {
        val json = redis.opsForValue().getAndDelete(REG_PREFIX + token) ?: return Optional.empty()
        return Optional.of(deserialize(json))
    }

    fun putAuthentication(token: String, value: AuthenticationChallenge) {
        redis.opsForValue().set(AUTH_PREFIX + token, serialize(value), TTL)
    }

    fun takeAuthentication(token: String): Optional<AuthenticationChallenge> {
        val json = redis.opsForValue().getAndDelete(AUTH_PREFIX + token) ?: return Optional.empty()
        return Optional.of(deserialize(json))
    }

    private fun serialize(o: Any): String =
        try {
            mapper.writeValueAsString(o)
        } catch (e: Exception) {
            throw IllegalStateException("ChallengeStore JSON serialize failed", e)
        }

    // 결과는 nullable: JSON 리터럴 `null` 이면 null 을 돌려주고, 호출부 Optional.of 가 try 밖에서
    // NPE 로 거부한다(원 Java 와 같은 예외 흐름 — IllegalStateException 으로 감싸지 않는다).
    private inline fun <reified T : Any> deserialize(json: String): T? =
        try {
            mapper.readValue(json, T::class.java)
        } catch (e: Exception) {
            throw IllegalStateException("ChallengeStore JSON deserialize failed", e)
        }

    companion object {
        /**
         * Challenge 의 Redis 만료 기간. /finish 경로의 앱-레벨 freshness 가드
         * (F19)도 이 값을 기준으로 issuedAt 을 검증하므로 두 방어가 동일한
         * 만료 경계를 공유하도록 public 으로 노출한다.
         */
        val TTL: Duration = Duration.ofMinutes(5)
    }
}
