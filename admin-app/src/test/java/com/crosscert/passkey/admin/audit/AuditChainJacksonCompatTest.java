package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.config.CoreJacksonConfig;
import com.crosscert.passkey.core.config.DbSchemaProperties;
import com.crosscert.passkey.core.config.KstTime;
import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 감사 로그 해시 체인의 Jackson 직렬화 호환성 (Boot 4 / Jackson 3 업그레이드 안전망).
 *
 * <p><b>의존 여부</b>: 의존한다. {@link AuditLogService#append} 는 payload {@code Map} 을
 * {@code ORDER_MAP_ENTRIES_BY_KEYS} 를 켠 ObjectMapper 로 직렬화한 <em>문자열 바이트</em>를
 * SHA-256 입력에 넣는다({@code AuditLogService.serialize} → {@code computeHash}). 반면
 * {@link AuditChainVerifier} 는 저장된 payload 문자열을 재직렬화 없이 그대로 해시에 넣으므로
 * 기존 체인 검증 자체는 Jackson 출력과 무관하다. 따라서 업그레이드 위험은 "새 버전이 같은 입력에서
 * 다른 바이트(키 순서·숫자·이스케이프)를 만들어 체인 형식이 갈라지는 것"이다.
 *
 * <p>픽스처({@code contract/audit-chain.json})는 업그레이드 전(Jackson 2) 코드로 만든 고정 체인이다.
 * <ul>
 *   <li>{@link #verifiesRecordedChain()} — 기록된 체인을 현재 verifier 가 통과시킨다.</li>
 *   <li>{@link #appendProducesSameHash()} — 같은 입력으로 append 하면 payload 문자열·hash·
 *       tenantHash 가 픽스처와 바이트 단위로 같다.</li>
 * </ul>
 * ObjectMapper 는 운영과 같은 경로(Boot Jackson 자동구성 + CoreJacksonConfig + application.yml)로 만든다.
 *
 * <p>기록: {@code ./gradlew :admin-app:test --tests '*AuditChainJacksonCompatTest' -Pcontract.record=true}
 */
class AuditChainJacksonCompatTest {

    private static final boolean RECORD = Boolean.getBoolean("contract.record");
    private static final Path FIXTURE = Path.of("src/test/resources/contract/audit-chain.json");
    private static final HexFormat HEX = HexFormat.of();

    private static final UUID TENANT = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    /** 체인 입력(고정). 키를 일부러 비정렬 순서로 넣어 키 정렬 직렬화를 검증한다. */
    private static List<Input> inputs() {
        Map<String, Object> p1 = new LinkedHashMap<>();
        p1.put("role", "PLATFORM_OPERATOR");
        p1.put("approved", true);

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("z", 1);
        nested.put("a", "x");
        Map<String, Object> p2 = new LinkedHashMap<>();
        p2.put("revokedKeys", 3);
        p2.put("bigLong", 9_007_199_254_740_993L);
        p2.put("ratio", 1.5);
        p2.put("detail", nested);

        Map<String, Object> p3 = new LinkedHashMap<>();
        p3.put("note", "한글 \"인용\" / 슬래시 \\ 역슬래시\n개행 <tag>");
        p3.put("origins", List.of("https://b.example", "https://a.example"));
        p3.put("empty", Map.of());

        return List.of(
                new Input(UUID.fromString("10000000-0000-0000-0000-000000000001"),
                        UUID.fromString("a0000000-0000-0000-0000-000000000001"), "op@example.com",
                        "ADMIN_SIGNUP_APPROVE", "ADMIN_USER", "u-1", null, p1,
                        OffsetDateTime.parse("2026-11-06T10:00:01.123456+09:00")),
                new Input(UUID.fromString("10000000-0000-0000-0000-000000000002"),
                        UUID.fromString("a0000000-0000-0000-0000-000000000002"), "rp@example.com",
                        "API_KEY_ROTATE", "API_KEY", "k-1", TENANT, p2,
                        OffsetDateTime.parse("2026-11-06T10:00:02.000001+09:00")),
                new Input(UUID.fromString("10000000-0000-0000-0000-000000000003"),
                        null, "(scheduler)",
                        "TENANT_ORIGINS_UPDATE", null, null, TENANT, p3,
                        OffsetDateTime.parse("2026-11-06T10:00:03.654321+09:00")));
    }

    private record Input(UUID rowId, UUID actorId, String actorEmail, String action,
                         String targetType, String targetId, UUID tenantId,
                         Map<String, Object> payload, OffsetDateTime createdAt) {
        AuditAppendRequest request() {
            return new AuditAppendRequest(actorId, actorEmail, action, targetType, targetId, tenantId, payload);
        }
    }

    private ObjectMapper baseMapper;

    @BeforeEach
    void setUp() {
        AtomicReference<ObjectMapper> ref = new AtomicReference<>();
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(Jackson2AutoConfiguration.class))
                .withUserConfiguration(CoreJacksonConfig.class)
                .run(ctx -> ref.set(ctx.getBean(ObjectMapper.class)));
        baseMapper = ref.get();
    }

    @Test
    void verifiesRecordedChain() throws IOException {
        List<AuditLog> rows = recordedRows();
        AuditLogRepository repo = mock(AuditLogRepository.class);
        when(repo.findAllOrdered()).thenReturn(rows);
        when(repo.findAllByTenantOrdered(null)).thenReturn(
                rows.stream().filter(r -> r.getTenantId() == null).toList());
        when(repo.findAllByTenantOrdered(TENANT)).thenReturn(
                rows.stream().filter(r -> TENANT.equals(r.getTenantId())).toList());

        AuditChainVerifier verifier = new AuditChainVerifier(repo, baseMapper);

        assertThat(verifier.verify().ok()).isTrue();
        assertThat(verifier.verifyTenant(null).ok()).isTrue();
        assertThat(verifier.verifyTenant(TENANT).ok()).isTrue();
    }

    @Test
    void appendProducesSameHash() throws IOException {
        List<AuditLog> appended = appendAll();
        if (RECORD) {
            record(appended);
            fail("recorded fixture " + FIXTURE + " — review it, then re-run without -Pcontract.record=true");
        }
        List<AuditLog> recorded = recordedRows();
        assertThat(appended).hasSameSizeAs(recorded);
        for (int i = 0; i < recorded.size(); i++) {
            AuditLog want = recorded.get(i);
            AuditLog got = appended.get(i);
            assertThat(got.getPayload()).as("row %d payload bytes", i).isEqualTo(want.getPayload());
            assertThat(got.getCreatedAt().toString()).as("row %d createdAt", i)
                    .isEqualTo(want.getCreatedAt().toString());
            assertThat(hex(got.getPrevHash())).as("row %d prevHash", i).isEqualTo(hex(want.getPrevHash()));
            assertThat(hex(got.getHash())).as("row %d hash", i).isEqualTo(hex(want.getHash()));
            assertThat(hex(got.getTenantPrevHash())).as("row %d tenantPrevHash", i)
                    .isEqualTo(hex(want.getTenantPrevHash()));
            assertThat(hex(got.getTenantHash())).as("row %d tenantHash", i).isEqualTo(hex(want.getTenantHash()));
        }
    }

    /** 고정 입력을 순서대로 append — 직전 row 들을 체인 head 로 돌려주는 mock 저장소 위에서. */
    private List<AuditLog> appendAll() {
        List<AuditLog> chain = new ArrayList<>();
        for (Input in : inputs()) {
            AuditLogRepository repo = mock(AuditLogRepository.class);
            when(repo.findLatestForUpdate()).thenReturn(
                    chain.isEmpty() ? Optional.empty() : Optional.of(chain.get(chain.size() - 1)));
            List<AuditLog> sameTenant = chain.stream()
                    .filter(r -> Objects.equals(r.getTenantId(), in.tenantId())).toList();
            when(repo.findLatestByTenant(any(), any())).thenReturn(
                    sameTenant.isEmpty() ? List.of() : List.of(sameTenant.get(sameTenant.size() - 1)));
            when(repo.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

            EntityManager em = mock(EntityManager.class);
            Query lockQuery = mock(Query.class, RETURNS_SELF);
            when(em.createNativeQuery(anyString())).thenReturn(lockQuery);

            Clock clock = Clock.fixed(in.createdAt().toInstant(), KstTime.ZONE);
            AuditLogService service = new AuditLogService(
                    repo, em, baseMapper, clock, new DbSchemaProperties("PSK_APP_OWNER"));
            AuditLog row = service.append(in.request());
            setId(row, in.rowId());
            chain.add(row);
        }
        return chain;
    }

    // ------------------------------------------------------------
    // Fixture I/O
    // ------------------------------------------------------------

    private static final ObjectMapper FIXTURE_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private void record(List<AuditLog> rows) throws IOException {
        ArrayNode arr = FIXTURE_MAPPER.createArrayNode();
        for (AuditLog r : rows) {
            ObjectNode n = arr.addObject();
            n.put("id", r.getId().toString());
            n.put("actorId", r.getActorId() == null ? null : r.getActorId().toString());
            n.put("actorEmail", r.getActorEmail());
            n.put("action", r.getAction());
            n.put("targetType", r.getTargetType());
            n.put("targetId", r.getTargetId());
            n.put("tenantId", r.getTenantId() == null ? null : r.getTenantId().toString());
            n.put("createdAt", r.getCreatedAt().toString());
            n.put("payload", r.getPayload());
            n.put("prevHash", hex(r.getPrevHash()));
            n.put("hash", hex(r.getHash()));
            n.put("tenantPrevHash", hex(r.getTenantPrevHash()));
            n.put("tenantHash", hex(r.getTenantHash()));
        }
        Files.createDirectories(FIXTURE.getParent());
        Files.writeString(FIXTURE, FIXTURE_MAPPER.writeValueAsString(arr) + "\n", StandardCharsets.UTF_8);
    }

    private static List<AuditLog> recordedRows() throws IOException {
        JsonNode arr;
        try (InputStream in = AuditChainJacksonCompatTest.class.getResourceAsStream("/contract/audit-chain.json")) {
            if (in == null) {
                fail("missing fixture contract/audit-chain.json — record it with -Pcontract.record=true");
            }
            arr = FIXTURE_MAPPER.readTree(in);
        }
        List<AuditLog> rows = new ArrayList<>();
        for (JsonNode n : arr) {
            AuditLog row = new AuditLog(
                    unhex(n.get("prevHash")), unhex(n.get("hash")),
                    uuid(n.get("actorId")), n.get("actorEmail").asText(), n.get("action").asText(),
                    text(n.get("targetType")), text(n.get("targetId")),
                    uuid(n.get("tenantId")),
                    unhex(n.get("tenantPrevHash")), unhex(n.get("tenantHash")),
                    n.get("payload").asText(), OffsetDateTime.parse(n.get("createdAt").asText()));
            setId(row, UUID.fromString(n.get("id").asText()));
            rows.add(row);
        }
        return rows;
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    private static UUID uuid(JsonNode n) {
        return n == null || n.isNull() ? null : UUID.fromString(n.asText());
    }

    private static byte[] unhex(JsonNode n) {
        return n == null || n.isNull() ? null : HEX.parseHex(n.asText());
    }

    private static String hex(byte[] b) {
        return b == null ? null : HEX.formatHex(b);
    }

    private static void setId(AuditLog row, UUID id) {
        try {
            java.lang.reflect.Field f = AuditLog.class.getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(row, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
