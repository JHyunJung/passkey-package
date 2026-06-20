package com.crosscert.passkey.admin.audit;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CRITICAL #1 — Audit HMAC 체인 타임스탬프가 OffsetDateTime(+09:00) 으로
 * 결정적으로 해시 입력에 포함되는지 검증한다.
 *
 * <p>{@code AuditAppendRequest} 실제 시그니처 순서:
 * (actorId, actorEmail, action, targetType, targetId, tenantId, payload).
 * {@code computeHash} 는 package-private static 이라 동일 패키지 테스트에서 호출 가능.
 */
class AuditHashKstTest {

    @Test
    void hashIncludesPlus9OffsetTimestampDeterministically() {
        OffsetDateTime now =
                OffsetDateTime.of(2026, 6, 20, 18, 0, 0, 0, ZoneOffset.ofHours(9));
        AuditAppendRequest req = new AuditAppendRequest(
                UUID.randomUUID(),      // actorId
                "a@x",                  // actorEmail
                "TENANT_CREATE",        // action
                "TENANT",               // targetType
                "t1",                   // targetId
                UUID.randomUUID(),      // tenantId
                java.util.Map.of("k", "v")); // payload

        byte[] h1 = AuditLogService.computeHash(null, req, "{\"k\":\"v\"}", now);
        byte[] h2 = AuditLogService.computeHash(null, req, "{\"k\":\"v\"}", now);

        assertThat(h1).isEqualTo(h2);                   // 결정적
        assertThat(now.toString()).contains("+09:00");  // KST 형식 확인
    }
}
