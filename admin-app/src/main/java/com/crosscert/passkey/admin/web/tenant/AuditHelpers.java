package com.crosscert.passkey.admin.web.tenant;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 감사 로그 탭의 순수 규칙(← pages/tenant/auditHelpers.ts). 화면·REST 와 무관하게 1:1 로 옮겼다.
 */
public final class AuditHelpers {

    private AuditHelpers() {}

    public record Window(String key, int hours, String label, String longLabel) {}

    /** WINDOWS — 선언 순서가 세그먼트 순서. */
    public static final List<Window> WINDOWS = List.of(
            new Window("24h", 24, "24시간", "최근 24시간"),
            new Window("7d", 168, "7일", "최근 7일"),
            new Window("30d", 720, "30일", "최근 30일"));

    public static final String DEFAULT_WINDOW = "24h";

    public static Window window(String key) {
        for (Window w : WINDOWS) if (w.key().equals(key)) return w;
        return WINDOWS.get(0);
    }

    public record Range(String from, String to) {}

    /** windowRange: now 기준 hours 시간 전 ~ now. JS toISOString 과 같은 밀리초 ISO. */
    public static Range windowRange(int hours, Instant now) {
        Instant n = now.truncatedTo(ChronoUnit.MILLIS);
        return new Range(iso(n.minus(hours, ChronoUnit.HOURS)), iso(n));
    }

    /** {@code 2026-10-10T03:00:00.000Z} */
    public static String iso(Instant t) {
        String s = t.truncatedTo(ChronoUnit.MILLIS).toString();
        // Instant.toString 은 밀리초가 0 이면 소수부를 생략한다.
        if (s.length() == 20) return s.substring(0, 19) + ".000Z";
        if (s.length() == 22) return s.substring(0, 21) + "00Z";
        if (s.length() == 23) return s.substring(0, 22) + "0Z";
        return s;
    }

    // ── 실패·톤 ─────────────────────────────────────────────

    /** ActivityPage 의 FAILURE_TYPES 와 같은 기준. */
    public static final Set<String> FAILURE_TYPES = Set.of("SIGNATURE_COUNTER_REGRESSION", "ATTESTATION_TRUST_FAILED", "ADMIN_LOGIN_FAILED");
    private static final Pattern FAILURE_NAME = Pattern.compile("_(FAILED|FAIL|REGRESSION)$");
    private static final Pattern WARN_NAME = Pattern.compile("_(UPDATED|ROTATED|REVOKED|ISSUED|SUSPENDED|DELETED)$");
    private static final Pattern INFO_NAME = Pattern.compile("(SUCCESS|AUTHENTICATED|REGISTERED|LOGIN)$");

    /** result 가 있으면 그 값(FAILURE), 없으면 액션 이름 기준. REST 에는 result 가 없어 보통 null. */
    public static boolean isFailure(String eventType, String result) {
        if (result != null) return "FAILURE".equals(result);
        return FAILURE_TYPES.contains(eventType) || FAILURE_NAME.matcher(eventType).find();
    }

    /** bad | warn | info | neutral */
    public static String eventTone(String eventType, String result) {
        if (isFailure(eventType, result)) return "bad";
        if (WARN_NAME.matcher(eventType).find()) return "warn";
        if (INFO_NAME.matcher(eventType).find()) return "info";
        return "neutral";
    }

    // ── 이벤트 종류 그룹 ─────────────────────────────────────

    public record EventGroup(String id, String label, String codes) {}

    public static final List<EventGroup> EVENT_GROUPS = List.of(
            new EventGroup("auth", "인증", "AUTH_* · AUTHENTICATION_* · CREDENTIAL_AUTHENTICATED · SIGNATURE_*"),
            new EventGroup("registration", "등록", "REGISTRATION_* · CREDENTIAL_REGISTERED · ATTESTATION_TRUST_*"),
            new EventGroup("config", "설정 변경", "WEBAUTHN_CONFIG_* · AAGUID_* · ATTESTATION_POLICY_* · TENANT_*"),
            new EventGroup("keys", "키 관리", "API_KEY_* · CREDENTIAL_REVOKE*"),
            new EventGroup("other", "기타", "위 네 그룹에 속하지 않는 이벤트"));

    private static final Pattern G_AUTH = Pattern.compile("^AUTH(ENTICATION)?_");
    private static final Pattern G_SIG = Pattern.compile("^SIGNATURE_");
    private static final Pattern G_REG = Pattern.compile("^REGISTRATION_");
    private static final Pattern G_TRUST = Pattern.compile("^ATTESTATION_TRUST_");
    private static final Pattern G_CONFIG = Pattern.compile("^(WEBAUTHN_CONFIG|AAGUID|ATTESTATION_POLICY|TENANT)_");
    private static final Pattern G_KEY = Pattern.compile("^API_KEY_");
    private static final Pattern G_REVOKE = Pattern.compile("^CREDENTIAL_REVOKE");

    public static boolean isGroupId(String id) {
        for (EventGroup g : EVENT_GROUPS) if (g.id().equals(id)) return true;
        return false;
    }

    /** 이벤트 타입 → 그룹. 어느 그룹에도 맞지 않으면 'other'. */
    public static String eventGroup(String type) {
        if (G_AUTH.matcher(type).find() || "CREDENTIAL_AUTHENTICATED".equals(type) || G_SIG.matcher(type).find()) return "auth";
        if (G_REG.matcher(type).find() || "CREDENTIAL_REGISTERED".equals(type) || G_TRUST.matcher(type).find()) return "registration";
        if (G_CONFIG.matcher(type).find()) return "config";
        if (G_KEY.matcher(type).find() || G_REVOKE.matcher(type).find()) return "keys";
        return "other";
    }

    // ── payload diff ─────────────────────────────────────────

    /** from/to: 값 없음(undefined)이면 null. */
    public record DiffEntry(String field, JsonNode from, JsonNode to) {}

    private static boolean isObj(JsonNode n) {
        return n != null && n.isObject();
    }

    private static JsonNode get(JsonNode obj, String key) {
        return obj.has(key) ? obj.get(key) : null;
    }

    /** JSON.stringify 같은 비교용 문자열(없으면 null). */
    private static String str(JsonNode n) {
        return n == null ? null : n.toString();
    }

    public static List<DiffEntry> payloadDiff(JsonNode payload) {
        List<DiffEntry> out = new ArrayList<>();
        if (payload == null || payload.isNull() || !payload.isObject()) return out;
        JsonNode before = get(payload, "before");
        JsonNode after = get(payload, "after");
        if (isObj(before) && isObj(after)) {
            Set<String> keys = new LinkedHashSet<>();
            before.fieldNames().forEachRemaining(keys::add);
            after.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) {
                JsonNode b = get(before, k);
                JsonNode a = get(after, k);
                if (!java.util.Objects.equals(str(b), str(a))) out.add(new DiffEntry(k, b, a));
            }
            return out;
        }
        Iterator<Map.Entry<String, JsonNode>> it = payload.properties().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            if (isObj(v) && v.has("from") && v.has("to")) out.add(new DiffEntry(e.getKey(), v.get("from"), v.get("to")));
        }
        return out;
    }

    /** undefined → —, 문자열은 그대로, 그 밖은 JSON. */
    public static String diffValue(JsonNode v) {
        if (v == null) return "—";
        return v.isTextual() ? v.asText() : v.toString();
    }

    // ── JSON.stringify(v, null, 2) ───────────────────────────

    /** 들여쓰기 2칸, 빈 객체·배열은 {} / [] — JS 의 JSON.stringify(payload ?? {}, null, 2) 와 같은 모양. */
    public static String pretty(JsonNode n) {
        if (n == null) return "{}";
        StringBuilder sb = new StringBuilder();
        write(sb, n, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, JsonNode n, int depth) {
        if (n.isObject()) {
            if (n.size() == 0) { sb.append("{}"); return; }
            sb.append("{\n");
            Iterator<Map.Entry<String, JsonNode>> it = n.properties().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                indent(sb, depth + 1);
                sb.append(new com.fasterxml.jackson.databind.node.TextNode(e.getKey()).toString()).append(": ");
                write(sb, e.getValue(), depth + 1);
                if (it.hasNext()) sb.append(',');
                sb.append('\n');
            }
            indent(sb, depth);
            sb.append('}');
        } else if (n.isArray()) {
            if (n.size() == 0) { sb.append("[]"); return; }
            sb.append("[\n");
            for (int i = 0; i < n.size(); i++) {
                indent(sb, depth + 1);
                write(sb, n.get(i), depth + 1);
                if (i < n.size() - 1) sb.append(',');
                sb.append('\n');
            }
            indent(sb, depth);
            sb.append(']');
        } else {
            sb.append(n.toString());
        }
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) sb.append("  ");
    }

}
