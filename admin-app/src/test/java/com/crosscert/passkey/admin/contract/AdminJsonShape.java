package com.crosscert.passkey.admin.contract;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 응답 JSON 을 "형태(shape)" 로 정규화한다 — 값은 버리고 경로별 타입 토큰만 남긴다.
 * passkey-app 의 {@code contract/JsonShape.kt}(1단계) 와 같은 토큰 규칙의 Java 판이다.
 *
 * <p>출력은 {@code 키 → 토큰} 맵을 키 정렬한 pretty JSON 이다. 경로 표기: 루트 {@code $},
 * 객체 필드 {@code $.a.b}, 배열 원소 {@code $.a[]}(모든 원소의 경로를 합집합으로 합친다).
 *
 * <p>토큰 규칙(위에서부터 처음 일치하는 것):
 * <ul>
 *   <li>객체 → {@code object}, null → {@code null}, boolean → {@code boolean}, 숫자 → {@code number}</li>
 *   <li>배열 → {@code array<원소토큰들>} — 원소 토큰의 정렬된 합집합을 {@code |} 로 잇는다. 빈 배열은 {@code array<>}.</li>
 *   <li>문자열: {@code jwt} → {@code uuid} → {@code iso-offset(<오프셋>)} → {@code b64url}(20자 이상, 영문자 포함) → {@code string}</li>
 * </ul>
 *
 * <p><b>키 순서</b>: {@code ordered=true} 로 부르면 객체 경로마다 {@code "<경로>{}": "k1,k2,…"} 항목을 더해
 * 필드 직렬화 순서까지 고정한다(record → data class 전환에서 Jackson 프로퍼티 순서가 바뀌는지 판정).
 * {@code Map.of(...)} 로 만든 응답은 JVM 실행마다 순회 순서가 달라지므로(ImmutableCollections SALT) 순서를 고정할 수 없다 —
 * 그런 응답은 {@code ordered=false} 로 부른다. 한 원소짜리 Map 은 순서가 결정적이다.
 *
 * <p>상태 코드 등 응답 메타데이터는 {@code @} 로 시작하는 키로 같은 맵에 싣는다(경로 키 {@code $…} 와 겹치지 않는다).
 */
final class AdminJsonShape {

    static final int B64URL_MIN_LEN = 20;

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private static final Pattern JWT = Pattern.compile("^[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]+$");
    private static final Pattern UUID_RE = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern ISO_OFFSET = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})$");
    private static final Pattern B64URL = Pattern.compile("^[A-Za-z0-9_-]{" + B64URL_MIN_LEN + ",}$");
    private static final Pattern HAS_LETTER = Pattern.compile("[A-Za-z]");

    private AdminJsonShape() {}

    /** JSON 문자열 → 형태 pretty JSON. {@code meta} 는 {@code @키 → 값} 으로 그대로 싣는다. */
    static String of(String json, boolean ordered, Map<String, String> meta) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("not JSON: " + e.getMessage() + " — body=" + json, e);
        }
        TreeMap<String, TreeSet<String>> paths = new TreeMap<>();
        TreeMap<String, TreeSet<String>> orders = new TreeMap<>();
        walk("$", root, paths, orders);
        TreeMap<String, String> out = new TreeMap<>();
        meta.forEach((k, v) -> {
            if (!k.startsWith("@")) throw new IllegalArgumentException("meta key must start with @: " + k);
            out.put(k, v);
        });
        paths.forEach((p, tokens) -> out.put(p, String.join("|", tokens)));
        if (ordered) orders.forEach((p, seqs) -> out.put(p + "{}", String.join(" | ", seqs)));
        ObjectNode node = MAPPER.createObjectNode();
        out.forEach(node::put);
        try {
            return MAPPER.writeValueAsString(node) + "\n";
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void walk(String path, JsonNode node, Map<String, TreeSet<String>> paths,
                             Map<String, TreeSet<String>> orders) {
        paths.computeIfAbsent(path, k -> new TreeSet<>()).add(token(node));
        if (node.isObject()) {
            List<String> keys = new ArrayList<>();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                keys.add(e.getKey());
                walk(path + "." + e.getKey(), e.getValue(), paths, orders);
            }
            orders.computeIfAbsent(path, k -> new TreeSet<>()).add(String.join(",", keys));
        } else if (node.isArray()) {
            for (JsonNode element : node) walk(path + "[]", element, paths, orders);
        }
    }

    static String token(JsonNode node) {
        if (node.isObject()) return "object";
        if (node.isNull()) return "null";
        if (node.isBoolean()) return "boolean";
        if (node.isNumber()) return "number";
        if (node.isArray()) {
            TreeSet<String> inner = new TreeSet<>();
            for (JsonNode e : node) inner.add(token(e));
            return "array<" + String.join("|", inner) + ">";
        }
        if (node.isString()) return stringToken(node.asString());
        throw new IllegalArgumentException("unsupported JSON node type: " + node.getNodeType());
    }

    static String stringToken(String s) {
        if (JWT.matcher(s).matches()) return "jwt";
        if (UUID_RE.matcher(s).matches()) return "uuid";
        Matcher m = ISO_OFFSET.matcher(s);
        if (m.matches()) return "iso-offset(" + m.group(1) + ")";
        if (B64URL.matcher(s).matches() && HAS_LETTER.matcher(s).find()) return "b64url";
        return "string";
    }
}
