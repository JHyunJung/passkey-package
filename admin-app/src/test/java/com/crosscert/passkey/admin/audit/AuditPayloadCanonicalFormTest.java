package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.admin.tenant.TenantSnapshot;
import com.crosscert.passkey.core.config.CoreJacksonConfig;
import com.crosscert.passkey.core.config.DbSchemaProperties;
import com.crosscert.passkey.core.config.KstTime;
import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 감사 payload 정규형의 null 처리 고정 (Boot 4 / Jackson 3 업그레이드 안전망 — 감사 체인 무결성).
 *
 * <p>{@link AuditChainJacksonCompatTest} 픽스처에는 null 값이 없다. 운영 payload 에는 값이 null 인
 * Map 항목과 null 필드를 가진 record({@link TenantSnapshot#rpName()} 등)가 실제로 들어가므로,
 * 그 경우의 정규 문자열과 해시를 여기서 별도로 고정한다(픽스처 파일은 건드리지 않는다).
 *
 * <p><b>기대값 출처</b>: 업그레이드 직전 커밋 {@code 17754e8c}(Spring Boot 3.5 / Jackson 2) 의 별도
 * worktree 에서, 이 테스트와 같은 입력·같은 매퍼 경로(Boot Jackson 자동구성 + CoreJacksonConfig +
 * admin {@code application.yml}→{@code application-common.yml}, {@code AuditLogService.append})로
 * 실행한 일회성 probe 의 출력을 그대로 옮겼다. Jackson 2 의 {@code default-property-inclusion: non_null}
 * 은 값·내용 포함 규칙을 둘 다 NON_NULL 로 걸어, record 의 null 필드뿐 아니라 Map 의 null 값 항목도
 * (최상위·중첩 모두) 생략했다 — Jackson 3 경로도 같아야 한다.
 */
class AuditPayloadCanonicalFormTest {

    /** Jackson 2(Boot 3.5, 17754e8c) 에서 기록한 정규 payload. "reason"·"meta.b"·after.rpName·after.attestationConveyance 는 null 이라 없다. */
    private static final String EXPECTED_PAYLOAD =
            "{\"after\":{\"displayName\":\"Acme 2\",\"allowedOrigins\":[\"https://a.example\"],"
            + "\"acceptedFormats\":[\"none\"],\"requireUserVerification\":false,\"mdsRequired\":true,"
            + "\"webauthnTimeoutMs\":120000},"
            + "\"before\":{\"displayName\":\"Acme\",\"rpName\":\"Acme RP\","
            + "\"allowedOrigins\":[\"https://a.example\",\"https://b.example\"],"
            + "\"acceptedFormats\":[\"none\",\"packed\"],\"requireUserVerification\":true,"
            + "\"mdsRequired\":false,\"attestationConveyance\":\"none\",\"webauthnTimeoutMs\":60000},"
            + "\"changedFields\":[\"displayName\",\"rpName\",\"allowedOrigins\",\"acceptedFormats\","
            + "\"requireUserVerification\",\"mdsRequired\",\"attestationConveyance\",\"webauthnTimeoutMs\"],"
            + "\"meta\":{\"a\":1}}";

    /** 같은 probe 의 hash (genesis 행이라 global/tenant 해시가 같다). */
    private static final String EXPECTED_HASH =
            "694a14badac5e7ce4c5c6e211192070b74c7ce93ea81b04e47cdd0dca271462c";

    @Test
    void nullMapEntriesAndNullRecordFields_serializeAsUnderJackson2() {
        AtomicReference<ObjectMapper> ref = new AtomicReference<>();
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(CoreJacksonConfig.class)
                .run(ctx -> ref.set(ctx.getBean(ObjectMapper.class)));

        TenantSnapshot before = new TenantSnapshot("Acme", "Acme RP",
                new TreeSet<>(List.of("https://b.example", "https://a.example")),
                new TreeSet<>(List.of("packed", "none")), true, false, "none", 60000);
        TenantSnapshot after = new TenantSnapshot("Acme 2", null,
                new TreeSet<>(List.of("https://a.example")),
                new TreeSet<>(List.of("none")), false, true, null, 120000);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("b", null);
        meta.put("a", 1);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", null);
        payload.put("before", before);
        payload.put("after", after);
        payload.put("changedFields", before.diff(after));
        payload.put("meta", meta);

        AuditLogRepository repo = mock(AuditLogRepository.class);
        when(repo.findLatestForUpdate()).thenReturn(Optional.empty());
        when(repo.findLatestByTenant(any(), any())).thenReturn(List.of());
        when(repo.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        EntityManager em = mock(EntityManager.class);
        Query lockQuery = mock(Query.class, RETURNS_SELF);
        when(em.createNativeQuery(anyString())).thenReturn(lockQuery);

        OffsetDateTime at = OffsetDateTime.parse("2026-11-06T11:22:33.445566+09:00");
        AuditLogService service = new AuditLogService(repo, em, ref.get(),
                Clock.fixed(at.toInstant(), KstTime.ZONE), new DbSchemaProperties("PSK_APP_OWNER"));
        AuditLog row = service.append(new AuditAppendRequest(
                UUID.fromString("a0000000-0000-0000-0000-0000000000aa"), "op@example.com",
                "TENANT_UPDATE", "TENANT", "t-1",
                UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"), payload));

        assertThat(row.getPayload()).isEqualTo(EXPECTED_PAYLOAD);
        assertThat(row.getCreatedAt().toString()).isEqualTo("2026-11-06T11:22:33.445566+09:00");
        assertThat(HexFormat.of().formatHex(row.getHash())).isEqualTo(EXPECTED_HASH);
        assertThat(HexFormat.of().formatHex(row.getTenantHash())).isEqualTo(EXPECTED_HASH);
    }
}
