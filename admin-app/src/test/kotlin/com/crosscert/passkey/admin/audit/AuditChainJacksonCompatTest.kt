package com.crosscert.passkey.admin.audit

import com.crosscert.passkey.core.config.CoreJacksonConfig
import com.crosscert.passkey.core.config.DbSchemaProperties
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.AuditLog
import com.crosscert.passkey.core.repository.AuditLogRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.OffsetDateTime
import java.util.HexFormat
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 감사 로그 해시 체인의 Jackson 직렬화 호환성 (Boot 4 / Jackson 3 업그레이드 안전망).
 *
 * **의존 여부**: 의존한다. [AuditLogService.append] 는 payload `Map` 을
 * `ORDER_MAP_ENTRIES_BY_KEYS` 를 켠 ObjectMapper 로 직렬화한 *문자열 바이트*를
 * SHA-256 입력에 넣는다(`AuditLogService.serialize` → `computeHash`). 반면
 * [AuditChainVerifier] 는 저장된 payload 문자열을 재직렬화 없이 그대로 해시에 넣으므로
 * 기존 체인 검증 자체는 Jackson 출력과 무관하다. 따라서 업그레이드 위험은 "새 버전이 같은 입력에서
 * 다른 바이트(키 순서·숫자·이스케이프)를 만들어 체인 형식이 갈라지는 것"이다.
 *
 * 픽스처(`contract/audit-chain.json`)는 업그레이드 전(Jackson 2) 코드로 만든 고정 체인이다.
 *  - [verifiesRecordedChain] — 기록된 체인을 현재 verifier 가 통과시킨다.
 *  - [appendProducesSameHash] — 같은 입력으로 append 하면 payload 문자열·hash·
 *    tenantHash 가 픽스처와 바이트 단위로 같다.
 *
 * ObjectMapper 는 운영과 같은 경로(Boot Jackson 자동구성 + CoreJacksonConfig + application.yml)로 만든다.
 *
 * 기록: `./gradlew :admin-app:test --tests '*AuditChainJacksonCompatTest' -Pcontract.record=true`
 */
class AuditChainJacksonCompatTest {

    private data class Input(
        val rowId: UUID,
        val actorId: UUID?,
        val actorEmail: String?,
        val action: String?,
        val targetType: String?,
        val targetId: String?,
        val tenantId: UUID?,
        val payload: Map<String, Any?>,
        val createdAt: OffsetDateTime,
    ) {
        fun request(): AuditAppendRequest =
            AuditAppendRequest(actorId, actorEmail, action, targetType, targetId, tenantId, payload)
    }

    private lateinit var baseMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        val ref = AtomicReference<ObjectMapper>()
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration::class.java))
            .withUserConfiguration(CoreJacksonConfig::class.java)
            .run { ctx -> ref.set(ctx.getBean(ObjectMapper::class.java)) }
        baseMapper = ref.get()
    }

    @Test
    fun verifiesRecordedChain() {
        val rows = recordedRows()
        val repo: AuditLogRepository = mock()
        whenever(repo.findAllOrdered()).thenReturn(rows)
        whenever(repo.findAllByTenantOrdered(null)).thenReturn(rows.filter { r -> r.tenantId == null })
        whenever(repo.findAllByTenantOrdered(TENANT)).thenReturn(rows.filter { r -> TENANT == r.tenantId })

        val verifier = AuditChainVerifier(repo, baseMapper)

        assertThat(verifier.verify().ok).isTrue()
        assertThat(verifier.verifyTenant(null).ok).isTrue()
        assertThat(verifier.verifyTenant(TENANT).ok).isTrue()
    }

    @Test
    fun appendProducesSameHash() {
        val appended = appendAll()
        if (RECORD) {
            record(appended)
            fail<Any>("recorded fixture $FIXTURE — review it, then re-run without -Pcontract.record=true")
        }
        val recorded = recordedRows()
        assertThat(appended).hasSameSizeAs(recorded)
        for (i in recorded.indices) {
            val want = recorded[i]
            val got = appended[i]
            assertThat(got.payload).`as`("row %d payload bytes", i).isEqualTo(want.payload)
            assertThat(got.createdAt.toString()).`as`("row %d createdAt", i)
                .isEqualTo(want.createdAt.toString())
            assertThat(hex(got.prevHash)).`as`("row %d prevHash", i).isEqualTo(hex(want.prevHash))
            assertThat(hex(got.hash)).`as`("row %d hash", i).isEqualTo(hex(want.hash))
            assertThat(hex(got.tenantPrevHash)).`as`("row %d tenantPrevHash", i)
                .isEqualTo(hex(want.tenantPrevHash))
            assertThat(hex(got.tenantHash)).`as`("row %d tenantHash", i).isEqualTo(hex(want.tenantHash))
        }
    }

    /** 고정 입력을 순서대로 append — 직전 row 들을 체인 head 로 돌려주는 mock 저장소 위에서. */
    private fun appendAll(): List<AuditLog> {
        val chain = ArrayList<AuditLog>()
        for (input in inputs()) {
            val repo: AuditLogRepository = mock()
            whenever(repo.findLatestForUpdate()).thenReturn(
                if (chain.isEmpty()) Optional.empty() else Optional.of(chain[chain.size - 1]),
            )
            val sameTenant = chain.filter { r -> r.tenantId == input.tenantId }
            whenever(repo.findLatestByTenant(anyOrNull(), anyOrNull())).thenReturn(
                if (sameTenant.isEmpty()) listOf() else listOf(sameTenant[sameTenant.size - 1]),
            )
            whenever(repo.save(any<AuditLog>())).thenAnswer { inv -> inv.getArgument<AuditLog>(0) }

            val em: EntityManager = mock()
            val lockQuery: Query = Mockito.mock(Query::class.java, RETURNS_SELF)
            whenever(em.createNativeQuery(anyString())).thenReturn(lockQuery)

            val clock = Clock.fixed(input.createdAt.toInstant(), KstTime.ZONE)
            val service = AuditLogService(
                repo, em, baseMapper, clock, DbSchemaProperties("PSK_APP_OWNER"),
            )
            // append 는 mock save 가 인자를 그대로 돌려주므로 non-null. 원 Java 는 null 이면 바로 다음
            // setId 의 리플렉션 set 에서 NPE 로 실패했다 — 여기서는 checkNotNull(ISE)로 실패시킨다.
            val row = checkNotNull(service.append(input.request()))
            setId(row, input.rowId)
            chain.add(row)
        }
        return chain
    }

    // ------------------------------------------------------------
    // Fixture I/O
    // ------------------------------------------------------------

    private fun record(rows: List<AuditLog>) {
        val arr = FIXTURE_MAPPER.createArrayNode()
        for (r in rows) {
            val n = arr.addObject()
            n.put("id", r.id.toString())
            n.put("actorId", if (r.actorId == null) null else r.actorId.toString())
            n.put("actorEmail", r.actorEmail)
            n.put("action", r.action)
            n.put("targetType", r.targetType)
            n.put("targetId", r.targetId)
            n.put("tenantId", if (r.tenantId == null) null else r.tenantId.toString())
            n.put("createdAt", r.createdAt.toString())
            n.put("payload", r.payload)
            n.put("prevHash", hex(r.prevHash))
            n.put("hash", hex(r.hash))
            n.put("tenantPrevHash", hex(r.tenantPrevHash))
            n.put("tenantHash", hex(r.tenantHash))
        }
        Files.createDirectories(FIXTURE.parent)
        Files.writeString(FIXTURE, FIXTURE_MAPPER.writeValueAsString(arr) + "\n", StandardCharsets.UTF_8)
    }

    private companion object {
        val RECORD: Boolean = java.lang.Boolean.getBoolean("contract.record")
        val FIXTURE: Path = Path.of("src/test/resources/contract/audit-chain.json")
        val HEX: HexFormat = HexFormat.of()

        val TENANT: UUID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")

        val FIXTURE_MAPPER: ObjectMapper = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build()

        /** 체인 입력(고정). 키를 일부러 비정렬 순서로 넣어 키 정렬 직렬화를 검증한다. */
        fun inputs(): List<Input> {
            val p1 = LinkedHashMap<String, Any?>()
            p1["role"] = "PLATFORM_OPERATOR"
            p1["approved"] = true

            val nested = LinkedHashMap<String, Any?>()
            nested["z"] = 1
            nested["a"] = "x"
            val p2 = LinkedHashMap<String, Any?>()
            p2["revokedKeys"] = 3
            p2["bigLong"] = 9_007_199_254_740_993L
            p2["ratio"] = 1.5
            p2["detail"] = nested

            val p3 = LinkedHashMap<String, Any?>()
            p3["note"] = "한글 \"인용\" / 슬래시 \\ 역슬래시\n개행 <tag>"
            p3["origins"] = listOf("https://b.example", "https://a.example")
            p3["empty"] = mapOf<String, Any>()

            return listOf(
                Input(
                    UUID.fromString("10000000-0000-0000-0000-000000000001"),
                    UUID.fromString("a0000000-0000-0000-0000-000000000001"), "op@example.com",
                    "ADMIN_SIGNUP_APPROVE", "ADMIN_USER", "u-1", null, p1,
                    OffsetDateTime.parse("2026-11-06T10:00:01.123456+09:00"),
                ),
                Input(
                    UUID.fromString("10000000-0000-0000-0000-000000000002"),
                    UUID.fromString("a0000000-0000-0000-0000-000000000002"), "rp@example.com",
                    "API_KEY_ROTATE", "API_KEY", "k-1", TENANT, p2,
                    OffsetDateTime.parse("2026-11-06T10:00:02.000001+09:00"),
                ),
                Input(
                    UUID.fromString("10000000-0000-0000-0000-000000000003"),
                    null, "(scheduler)",
                    "TENANT_ORIGINS_UPDATE", null, null, TENANT, p3,
                    OffsetDateTime.parse("2026-11-06T10:00:03.654321+09:00"),
                ),
            )
        }

        fun recordedRows(): List<AuditLog> {
            val arr: JsonNode =
                (
                    AuditChainJacksonCompatTest::class.java.getResourceAsStream("/contract/audit-chain.json")
                        ?: fail<InputStream>("missing fixture contract/audit-chain.json — record it with -Pcontract.record=true")
                    ).use { input -> FIXTURE_MAPPER.readTree(input) }
            val rows = ArrayList<AuditLog>()
            for (n in arr) {
                val row = AuditLog(
                    unhex(n.get("prevHash")), unhex(n.get("hash")),
                    uuid(n.get("actorId")), n.get("actorEmail").asString(), n.get("action").asString(),
                    text(n.get("targetType")), text(n.get("targetId")),
                    uuid(n.get("tenantId")),
                    unhex(n.get("tenantPrevHash")), unhex(n.get("tenantHash")),
                    n.get("payload").asString(), OffsetDateTime.parse(n.get("createdAt").asString()),
                )
                setId(row, UUID.fromString(n.get("id").asString()))
                rows.add(row)
            }
            return rows
        }

        fun text(n: JsonNode?): String? = if (n == null || n.isNull) null else n.asString()

        fun uuid(n: JsonNode?): UUID? = if (n == null || n.isNull) null else UUID.fromString(n.asString())

        fun unhex(n: JsonNode?): ByteArray? = if (n == null || n.isNull) null else HEX.parseHex(n.asString())

        fun hex(b: ByteArray?): String? = if (b == null) null else HEX.formatHex(b)

        fun setId(row: AuditLog, id: UUID) {
            try {
                val f = AuditLog::class.java.superclass.getDeclaredField("id")
                f.isAccessible = true
                f.set(row, id)
            } catch (e: ReflectiveOperationException) {
                throw IllegalStateException(e)
            }
        }
    }
}
