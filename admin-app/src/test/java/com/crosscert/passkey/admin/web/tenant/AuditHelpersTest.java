package com.crosscert.passkey.admin.web.tenant;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.crosscert.passkey.admin.web.format.KstFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 원본: pages/tenant/auditHelpers.test.ts 의 모든 케이스. */
class AuditHelpersTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode j(String s) throws Exception {
        return JSON.readTree(s);
    }

    @Test
    @DisplayName("windowRange: now 기준 hours 시간 전 ~ now (밀리초 ISO)")
    void windowRange() {
        Instant now = Instant.parse("2026-10-11T03:00:00Z");
        assertThat(AuditHelpers.windowRange(AuditHelpers.window("24h").getHours(), now))
                .isEqualTo(new AuditHelpers.Range("2026-10-10T03:00:00.000Z", "2026-10-11T03:00:00.000Z"));
        assertThat(AuditHelpers.window("7d").getHours()).isEqualTo(168);
        assertThat(AuditHelpers.window("30d").getHours()).isEqualTo(720);
        assertThat(AuditHelpers.window("nope").getKey()).isEqualTo("24h");
        assertThat(AuditHelpers.iso(Instant.parse("2026-10-11T03:00:00.120Z"))).isEqualTo("2026-10-11T03:00:00.120Z");
        assertThat(AuditHelpers.iso(Instant.parse("2026-10-11T03:00:00.100Z"))).isEqualTo("2026-10-11T03:00:00.100Z");
    }

    @Test
    @DisplayName("isFailure: result 가 있으면 우선, 없으면 실패 목록·이름 규칙")
    void isFailure() {
        assertThat(AuditHelpers.isFailure("ATTESTATION_TRUST_FAILED", null)).isTrue();
        assertThat(AuditHelpers.isFailure("SIGNATURE_COUNTER_REGRESSION", null)).isTrue();
        assertThat(AuditHelpers.isFailure("AUTH_FAIL", null)).isTrue();
        assertThat(AuditHelpers.isFailure("WEBAUTHN_CONFIG_UPDATED", null)).isFalse();
        assertThat(AuditHelpers.isFailure("CREDENTIAL_REVOKED", null)).isFalse();
        assertThat(AuditHelpers.isFailure("CUSTOM_CHECK", "FAILURE")).isTrue();
        assertThat(AuditHelpers.isFailure("RETRY_FAILED", "SUCCESS")).isFalse();
    }

    @Test
    void eventTone() {
        assertThat(AuditHelpers.eventTone("AUTH_FAIL", null)).isEqualTo("bad");
        assertThat(AuditHelpers.eventTone("WEBAUTHN_CONFIG_UPDATED", null)).isEqualTo("warn");
        assertThat(AuditHelpers.eventTone("API_KEY_ROTATED", null)).isEqualTo("warn");
        assertThat(AuditHelpers.eventTone("AUTH_SUCCESS", null)).isEqualTo("info");
        assertThat(AuditHelpers.eventTone("MDS_SYNC", null)).isEqualTo("neutral");
    }

    @Test
    @DisplayName("payloadDiff: {field:{from,to}} 와 {before,after} 만 diff 로 본다")
    void payloadDiff() throws Exception {
        List<AuditHelpers.DiffEntry> a = AuditHelpers.payloadDiff(j("{\"timeoutMs\":{\"from\":60000,\"to\":120000},\"note\":\"x\"}"));
        assertThat(a).hasSize(1);
        assertThat(a.get(0).getField()).isEqualTo("timeoutMs");
        assertThat(a.get(0).getFrom().asInt()).isEqualTo(60000);
        assertThat(a.get(0).getTo().asInt()).isEqualTo(120000);

        List<AuditHelpers.DiffEntry> b = AuditHelpers.payloadDiff(j("{\"before\":{\"a\":1,\"b\":2},\"after\":{\"a\":1,\"b\":3,\"c\":true}}"));
        assertThat(b).extracting(AuditHelpers.DiffEntry::getField).containsExactly("b", "c");
        assertThat(b.get(0).getFrom().asInt()).isEqualTo(2);
        assertThat(b.get(0).getTo().asInt()).isEqualTo(3);
        assertThat(b.get(1).getFrom()).isNull(); // undefined
        assertThat(b.get(1).getTo().asBoolean()).isTrue();

        assertThat(AuditHelpers.payloadDiff(j("{\"reason\":\"x\"}"))).isEmpty();
        assertThat(AuditHelpers.payloadDiff(null)).isEmpty();
    }

    @Test
    void diffValue() throws Exception {
        assertThat(AuditHelpers.diffValue(j("\"a\""))).isEqualTo("a");
        assertThat(AuditHelpers.diffValue(j("60000"))).isEqualTo("60000");
        assertThat(AuditHelpers.diffValue(j("[\"x\"]"))).isEqualTo("[\"x\"]");
        assertThat(AuditHelpers.diffValue(null)).isEqualTo("—");
    }

    @Test
    @DisplayName("dayLabel: KST 기준 오늘/어제/날짜 (공용 KstFormat)")
    void dayLabel() {
        Instant now = Instant.parse("2026-10-11T03:00:00Z"); // KST 12:00
        assertThat(KstFormat.dayLabel("2026-10-11T00:30:00Z", now)).isEqualTo("오늘");
        assertThat(KstFormat.dayLabel("2026-10-10T14:59:00Z", now)).isEqualTo("어제");
        assertThat(KstFormat.dayLabel("2026-10-10T15:00:00Z", now)).isEqualTo("오늘");
        assertThat(KstFormat.dayLabel("2026-10-08T05:00:00Z", now)).isEqualTo("2026-10-08");
    }

    @Test
    @DisplayName("eventGroup: 인증 · 등록 · 설정 변경 · 키 관리, 그 밖은 기타")
    void eventGroup() {
        assertThat(AuditHelpers.eventGroup("AUTH_SUCCESS")).isEqualTo("auth");
        assertThat(AuditHelpers.eventGroup("AUTH_FAIL")).isEqualTo("auth");
        assertThat(AuditHelpers.eventGroup("AUTHENTICATION_FINISH_OK")).isEqualTo("auth");
        assertThat(AuditHelpers.eventGroup("CREDENTIAL_AUTHENTICATED")).isEqualTo("auth");
        assertThat(AuditHelpers.eventGroup("SIGNATURE_COUNTER_REGRESSION")).isEqualTo("auth");
        assertThat(AuditHelpers.eventGroup("REGISTRATION_FINISH_OK")).isEqualTo("registration");
        assertThat(AuditHelpers.eventGroup("CREDENTIAL_REGISTERED")).isEqualTo("registration");
        assertThat(AuditHelpers.eventGroup("ATTESTATION_TRUST_FAILED")).isEqualTo("registration");
        assertThat(AuditHelpers.eventGroup("WEBAUTHN_CONFIG_UPDATED")).isEqualTo("config");
        assertThat(AuditHelpers.eventGroup("AAGUID_POLICY_UPDATED")).isEqualTo("config");
        assertThat(AuditHelpers.eventGroup("TENANT_SUSPEND")).isEqualTo("config");
        assertThat(AuditHelpers.eventGroup("API_KEY_ROTATED")).isEqualTo("keys");
        assertThat(AuditHelpers.eventGroup("CREDENTIAL_REVOKE")).isEqualTo("keys");
        assertThat(AuditHelpers.eventGroup("CREDENTIAL_REVOKED")).isEqualTo("keys");
        assertThat(AuditHelpers.eventGroup("MDS_BLOB_SYNC")).isEqualTo("other");
        assertThat(AuditHelpers.EVENT_GROUPS).extracting(AuditHelpers.EventGroup::getLabel).containsExactly("인증", "등록", "설정 변경", "키 관리", "기타");
    }

    @Test
    @DisplayName("pretty: JSON.stringify(v, null, 2) 모양 — 2칸 들여쓰기, 'key: value', 빈 객체·배열은 {} []")
    void pretty() throws Exception {
        assertThat(AuditHelpers.pretty(j("{}"))).isEqualTo("{}");
        assertThat(AuditHelpers.pretty(j("{\"a\":1,\"b\":[1,2],\"c\":{\"d\":\"x\"},\"e\":[],\"f\":{}}")))
                .isEqualTo("{\n  \"a\": 1,\n  \"b\": [\n    1,\n    2\n  ],\n  \"c\": {\n    \"d\": \"x\"\n  },\n  \"e\": [],\n  \"f\": {}\n}");
        assertThat(AuditHelpers.pretty(j("{\"s\":\"a\\\"b\\n\"}"))).isEqualTo("{\n  \"s\": \"a\\\"b\\n\"\n}");
    }
}
