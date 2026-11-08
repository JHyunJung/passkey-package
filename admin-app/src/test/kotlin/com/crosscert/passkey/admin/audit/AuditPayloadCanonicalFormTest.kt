package com.crosscert.passkey.admin.audit

import com.crosscert.passkey.admin.tenant.TenantSnapshot
import com.crosscert.passkey.core.config.CoreJacksonConfig
import com.crosscert.passkey.core.config.DbSchemaProperties
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.AuditLog
import com.crosscert.passkey.core.repository.AuditLogRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.util.HexFormat
import java.util.Optional
import java.util.TreeSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 감사 payload 정규형의 null 처리 고정 (Boot 4 / Jackson 3 업그레이드 안전망 — 감사 체인 무결성).
 *
 * [AuditChainJacksonCompatTest] 픽스처에는 null 값이 없다. 운영 payload 에는 값이 null 인
 * Map 항목과 null 필드를 가진 record([TenantSnapshot.rpName] 등)가 실제로 들어가므로,
 * 그 경우의 정규 문자열과 해시를 여기서 별도로 고정한다(픽스처 파일은 건드리지 않는다).
 *
 * **기대값 출처**: 업그레이드 직전 커밋 `17754e8c`(Spring Boot 3.5 / Jackson 2) 의 별도
 * worktree 에서, 이 테스트와 같은 입력·같은 매퍼 경로(Boot Jackson 자동구성 + CoreJacksonConfig +
 * admin `application.yml`→`application-common.yml`, `AuditLogService.append`)로
 * 실행한 일회성 probe 의 출력을 그대로 옮겼다. Jackson 2 의 `default-property-inclusion: non_null`
 * 은 값·내용 포함 규칙을 둘 다 NON_NULL 로 걸어, record 의 null 필드뿐 아니라 Map 의 null 값 항목도
 * (최상위·중첩 모두) 생략했다 — Jackson 3 경로도 같아야 한다.
 */
class AuditPayloadCanonicalFormTest {

    @Test
    fun nullMapEntriesAndNullRecordFields_serializeAsUnderJackson2() {
        val ref = AtomicReference<ObjectMapper>()
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration::class.java))
            .withUserConfiguration(CoreJacksonConfig::class.java)
            .run { ctx -> ref.set(ctx.getBean(ObjectMapper::class.java)) }

        val before = TenantSnapshot(
            "Acme", "Acme RP",
            TreeSet(listOf("https://b.example", "https://a.example")),
            TreeSet(listOf("packed", "none")), true, false, "none", 60000,
        )
        val after = TenantSnapshot(
            "Acme 2", null,
            TreeSet(listOf("https://a.example")),
            TreeSet(listOf("none")), false, true, null, 120000,
        )
        val meta = LinkedHashMap<String, Any?>()
        meta["b"] = null
        meta["a"] = 1
        val payload = LinkedHashMap<String, Any?>()
        payload["reason"] = null
        payload["before"] = before
        payload["after"] = after
        payload["changedFields"] = before.diff(after)
        payload["meta"] = meta

        val repo: AuditLogRepository = mock()
        whenever(repo.findLatestForUpdate()).thenReturn(Optional.empty())
        whenever(repo.findLatestByTenant(anyOrNull(), anyOrNull())).thenReturn(listOf())
        whenever(repo.save(any<AuditLog>())).thenAnswer { inv -> inv.getArgument<AuditLog>(0) }
        val em: EntityManager = mock()
        val lockQuery: Query = Mockito.mock(Query::class.java, RETURNS_SELF)
        whenever(em.createNativeQuery(anyString())).thenReturn(lockQuery)

        val at = OffsetDateTime.parse("2026-11-06T11:22:33.445566+09:00")
        val service = AuditLogService(
            repo, em, ref.get(),
            Clock.fixed(at.toInstant(), KstTime.ZONE), DbSchemaProperties("PSK_APP_OWNER"),
        )
        val row = checkNotNull(
            service.append(
                AuditAppendRequest(
                    UUID.fromString("a0000000-0000-0000-0000-0000000000aa"), "op@example.com",
                    "TENANT_UPDATE", "TENANT", "t-1",
                    UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"), payload,
                ),
            ),
        )

        assertThat(row.payload).isEqualTo(EXPECTED_PAYLOAD)
        assertThat(row.createdAt.toString()).isEqualTo("2026-11-06T11:22:33.445566+09:00")
        assertThat(HexFormat.of().formatHex(row.hash)).isEqualTo(EXPECTED_HASH)
        assertThat(HexFormat.of().formatHex(row.tenantHash)).isEqualTo(EXPECTED_HASH)
    }

    private companion object {
        /** Jackson 2(Boot 3.5, 17754e8c) 에서 기록한 정규 payload. "reason"·"meta.b"·after.rpName·after.attestationConveyance 는 null 이라 없다. */
        const val EXPECTED_PAYLOAD =
            "{\"after\":{\"displayName\":\"Acme 2\",\"allowedOrigins\":[\"https://a.example\"]," +
                "\"acceptedFormats\":[\"none\"],\"requireUserVerification\":false,\"mdsRequired\":true," +
                "\"webauthnTimeoutMs\":120000}," +
                "\"before\":{\"displayName\":\"Acme\",\"rpName\":\"Acme RP\"," +
                "\"allowedOrigins\":[\"https://a.example\",\"https://b.example\"]," +
                "\"acceptedFormats\":[\"none\",\"packed\"],\"requireUserVerification\":true," +
                "\"mdsRequired\":false,\"attestationConveyance\":\"none\",\"webauthnTimeoutMs\":60000}," +
                "\"changedFields\":[\"displayName\",\"rpName\",\"allowedOrigins\",\"acceptedFormats\"," +
                "\"requireUserVerification\",\"mdsRequired\",\"attestationConveyance\",\"webauthnTimeoutMs\"]," +
                "\"meta\":{\"a\":1}}"

        /** 같은 probe 의 hash (genesis 행이라 global/tenant 해시가 같다). */
        const val EXPECTED_HASH =
            "694a14badac5e7ce4c5c6e211192070b74c7ce93ea81b04e47cdd0dca271462c"
    }
}
