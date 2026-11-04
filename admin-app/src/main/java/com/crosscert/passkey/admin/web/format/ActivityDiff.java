package com.crosscert.passkey.admin.web.format;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 활동 상세 패널 "어떻게 바뀜" 행(← pages/activityDiff.ts, 1:1).
 * 값(from/to)은 JSON 노드이고 Java {@code null} 이 JS 의 {@code undefined}(키 없음), {@code NullNode} 가 JSON null 이다.
 */
public final class ActivityDiff {

    public record DiffRow(String field, JsonNode from, JsonNode to) {}

    public record ArrayChange(List<String> added, List<String> removed) {}

    /** JSON.parse 와 같이 뒤따르는 쓰레기 토큰을 거부한다. */
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private ActivityDiff() {}

    private static JsonNode asObject(JsonNode v) {
        return v != null && v.isObject() ? v : null;
    }

    private static JsonNode get(JsonNode obj, String key) {
        return obj.has(key) ? obj.get(key) : null;
    }

    /** JSON.stringify(v) — 없으면 null(undefined). */
    private static String stringify(JsonNode v) {
        return v == null ? null : v.toString();
    }

    /** JSON.parse(raw). 실패하면 null 대신 예외 없이 {@code ParseResult.fail}. */
    private record Parsed(boolean ok, JsonNode node) {}

    private static Parsed parse(String raw) {
        if (raw == null) return new Parsed(true, com.fasterxml.jackson.databind.node.NullNode.getInstance()); // JSON.parse(null) → null
        if (raw.isBlank()) return new Parsed(false, null);
        try {
            JsonNode n = JSON.readTree(raw);
            return n == null || n.isMissingNode() ? new Parsed(false, null) : new Parsed(true, n);
        } catch (JsonProcessingException e) {
            return new Parsed(false, null);
        }
    }

    /**
     * 1) 서버 ③ 의 detail.diff 가 정의되어 있으면({@code diff != null}) 그대로.
     * 2) 아니면 payload 의 before/after 객체(현재 TENANT_UPDATE: { before, after, changedFields }).
     *    changedFields 가 문자열 배열이면 그 순서, 아니면 JSON 값이 다른 키만.
     */
    public static List<DiffRow> buildDiffRows(List<DiffRow> diff, String payload) {
        if (diff != null) return diff;
        Parsed parsed = parse(payload);
        if (!parsed.ok()) return List.of();
        JsonNode p = asObject(parsed.node());
        if (p == null) return List.of();
        JsonNode before = asObject(get(p, "before"));
        JsonNode after = asObject(get(p, "after"));
        if (before == null || after == null) return List.of();
        List<String> fields = new ArrayList<>();
        JsonNode cf = get(p, "changedFields");
        boolean allStrings = cf != null && cf.isArray();
        if (allStrings) for (JsonNode f : cf) if (!f.isTextual()) { allStrings = false; break; }
        if (allStrings) {
            for (JsonNode f : cf) fields.add(f.asText());
        } else {
            Set<String> keys = new LinkedHashSet<>();
            before.fieldNames().forEachRemaining(keys::add);
            after.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) if (!Objects.equals(stringify(get(before, k)), stringify(get(after, k)))) fields.add(k);
        }
        List<DiffRow> rows = new ArrayList<>();
        for (String field : fields) rows.add(new DiffRow(field, get(before, field), get(after, field)));
        return rows;
    }

    /** 두 값이 모두 배열이면 추가/제거 원소(문자열화), 아니면 null. */
    public static ArrayChange arrayChange(JsonNode from, JsonNode to) {
        if (from == null || to == null || !from.isArray() || !to.isArray()) return null;
        List<String> f = new ArrayList<>();
        for (JsonNode n : from) f.add(jsString(n));
        List<String> t = new ArrayList<>();
        for (JsonNode n : to) t.add(jsString(n));
        List<String> added = new ArrayList<>();
        for (String x : t) if (!f.contains(x)) added.add(x);
        List<String> removed = new ArrayList<>();
        for (String x : f) if (!t.contains(x)) removed.add(x);
        return new ArrayChange(added, removed);
    }

    /** ActivityPage 의 fmtVal: undefined·null → —, 문자열은 그대로, 그 밖은 JSON.stringify. */
    public static String fmtVal(JsonNode v) {
        if (v == null || v.isNull()) return "—";
        return v.isTextual() ? v.asText() : v.toString();
    }

    /** JS {@code String(x)}. */
    static String jsString(JsonNode n) {
        if (n == null) return "undefined";
        if (n.isNull()) return "null";
        if (n.isTextual()) return n.asText();
        if (n.isBoolean()) return n.asBoolean() ? "true" : "false";
        if (n.isNumber()) {
            if (n.isIntegralNumber()) return n.bigIntegerValue().toString();
            BigDecimal d = n.decimalValue().stripTrailingZeros();
            return d.scale() <= 0 ? d.toBigInteger().toString() : d.toPlainString();
        }
        if (n.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n.size(); i++) {
                if (i > 0) sb.append(',');
                JsonNode e = n.get(i);
                if (!e.isNull()) sb.append(jsString(e)); // [null].join → ''
            }
            return sb.toString();
        }
        return "[object Object]";
    }
}
