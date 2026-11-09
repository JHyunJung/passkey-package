package com.crosscert.passkey.admin.policy

import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.SecurityPolicy
import com.crosscert.passkey.core.repository.SecurityPolicyRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.JacksonException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

private val log = LoggerFactory.getLogger(SecurityPolicyService::class.java)

private const val SINGLETON_ID = 1L

@Service
class SecurityPolicyService(
    private val repo: SecurityPolicyRepository,
    private val objectMapper: ObjectMapper,
) {

    @Transactional(readOnly = true)
    fun get(): SecurityPolicyDto.View {
        val p = repo.findById(SINGLETON_ID)
            .orElseThrow { IllegalStateException("security_policy singleton row missing — V31 not applied?") }
        return toView(p)
    }

    @Transactional
    fun update(req: SecurityPolicyDto.UpdateRequest, updatedBy: String?): SecurityPolicyDto.View {
        val p = repo.findById(SINGLETON_ID)
            .orElseThrow { IllegalStateException("security_policy singleton row missing — V31 not applied?") }
        p.sessionIdleTimeoutMinutes = req.sessionIdleTimeoutMinutes
        p.isMfaRequired = req.mfaRequired == true
        p.corsAllowlistJson = serialize(req.corsAllowlist)
        p.updatedAt = OffsetDateTime.now(KstTime.ZONE)
        p.updatedBy = updatedBy
        repo.save(p)

        val cors = req.corsAllowlist
        val allowlistSize = if (cors == null) 0 else cors.size
        log.info(
            "security policy updated: sessionIdle={} mfa={} corsAllowlistSize={}",
            req.sessionIdleTimeoutMinutes,
            req.mfaRequired == true,
            allowlistSize,
        )

        return toView(p)
    }

    private fun toView(p: SecurityPolicy): SecurityPolicyDto.View =
        SecurityPolicyDto.View(
            p.sessionIdleTimeoutMinutes,
            p.isMfaRequired,
            deserialize(p.corsAllowlistJson),
            p.updatedAt,
            p.updatedBy,
        )

    private fun serialize(list: List<String>?): String {
        try {
            return objectMapper.writeValueAsString(list ?: java.util.List.of<String>())
        } catch (e: JacksonException) {
            throw IllegalStateException("serialize cors_allowlist failed", e)
        }
    }

    /** 반환형 nullable: 저장값이 JSON `null` 이면 readValue 가 null — View 가 원 Java 처럼 빈 목록으로 정규화한다. */
    private fun deserialize(json: String?): List<String>? {
        // Java String.isBlank 와 같은 판정(Character.isWhitespace) — Kotlin isBlank 는 NBSP 등도 공백으로 본다.
        if (json == null || json.all(Character::isWhitespace)) return java.util.List.of()
        try {
            return objectMapper.readValue(json, object : TypeReference<List<String>>() {})
        } catch (e: JacksonException) {
            throw IllegalStateException("deserialize cors_allowlist failed (corrupt JSON: $json)", e)
        }
    }
}
