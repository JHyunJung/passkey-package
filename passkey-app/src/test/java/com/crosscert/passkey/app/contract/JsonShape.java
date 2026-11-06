package com.crosscert.passkey.app.contract;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 응답 JSON 을 "형태(shape)" 로 정규화한다 — 값은 버리고 경로별 타입 토큰만 남긴다.
 *
 * <p>출력은 {@code JSON 경로 → 토큰} 맵을 키 정렬한 pretty JSON 이다. 키를 정렬하므로
 * 필드 순서는 비교 대상이 아니다. 경로 표기: 루트 {@code $}, 객체 필드 {@code $.a.b},
 * 배열 원소 {@code $.a[]} (모든 원소의 경로를 합집합으로 합친다).
 *
 * <p>토큰 규칙(위에서부터 처음 일치하는 것):
 * <ul>
 *   <li>객체 → {@code object}, null → {@code null}, boolean → {@code boolean},
 *       숫자(정수·실수 구분 없음) → {@code number}</li>
 *   <li>배열 → {@code array<원소토큰들>} — 원소 토큰의 정렬된 합집합을 {@code |} 로 잇는다.
 *       빈 배열은 {@code array<>}.</li>
 *   <li>문자열은 아래 패턴 순서로 판정, 어느 것도 아니면 {@code string}:
 *     <ol>
 *       <li>{@code jwt} — 점으로 구분된 base64url 세그먼트 3개(header·payload 각 10자 이상 —
 *           {@code a.b.c} 같은 일반 문자열 오탐 방지)</li>
 *       <li>{@code uuid} — 8-4-4-4-12 16진수(하이픈 포함)</li>
 *       <li>{@code iso-offset(<offset>)} — {@code yyyy-MM-ddTHH:mm[:ss[.f+]]} 뒤에 오프셋
 *           ({@code Z} 또는 {@code ±HH:MM}). 오프셋 문자열을 그대로 토큰에 남긴다
 *           (예: {@code iso-offset(+09:00)}, {@code Instant} 는 {@code iso-offset(Z)}).
 *           소수 초 자릿수는 무시한다(값마다 달라질 수 있으므로).</li>
 *       <li>{@code b64url} — {@code [A-Za-z0-9_-]} 로만 이뤄진 {@value #B64URL_MIN_LEN}자 이상이면서
 *           영문자를 하나 이상 포함. 최소 길이는 결정성을 위한 보수적 경계다: 랜덤 16자 16진수
 *           traceId 처럼 짧은 휘발 값이 "우연히 숫자만" 나와 토큰이 흔들리는 일을 막고,
 *           16바이트 userHandle(22자)·32바이트 challenge(43자)·키 thumbprint(43자)는 확실히
 *           잡는다. 숫자만으로 된 문자열은 길이와 무관하게 {@code string} 이다.</li>
 *     </ol>
 *   </li>
 * </ul>
 *
 * <p>휘발 값(challenge·token·id·시각)은 이 규칙으로 값 대신 토큰으로만 남는다. 고정 입력
 * (예: 테스트가 보낸 displayName)은 매 실행 같은 토큰이 되므로 결정적이다.
 */
public final class JsonShape {

    static final int B64URL_MIN_LEN = 20;

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private static final Pattern JWT = Pattern.compile(
            "^[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]+$");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern ISO_OFFSET = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})$");
    private static final Pattern B64URL = Pattern.compile("^[A-Za-z0-9_-]{" + B64URL_MIN_LEN + ",}$");
    private static final Pattern HAS_LETTER = Pattern.compile("[A-Za-z]");

    private JsonShape() {}

    /** JSON 문자열 → 형태 pretty JSON. */
    public static String of(String json) {
        try {
            return of(MAPPER.readTree(json));
        } catch (JacksonException e) {
            throw new IllegalArgumentException("not JSON: " + e.getMessage(), e);
        }
    }

    /** 파싱된 JSON 트리 → 형태 pretty JSON. */
    public static String of(JsonNode root) {
        Map<String, TreeSet<String>> paths = new TreeMap<>();
        walk("$", root, paths);
        ObjectNode out = MAPPER.createObjectNode();
        paths.forEach((path, tokens) -> out.put(path, String.join("|", tokens)));
        try {
            return MAPPER.writeValueAsString(out) + "\n";
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void walk(String path, JsonNode node, Map<String, TreeSet<String>> paths) {
        paths.computeIfAbsent(path, k -> new TreeSet<>()).add(token(node));
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.properties().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                walk(path + "." + e.getKey(), e.getValue(), paths);
            }
        } else if (node.isArray()) {
            for (JsonNode el : node) {
                walk(path + "[]", el, paths);
            }
        }
    }

    static String token(JsonNode node) {
        if (node.isObject()) return "object";
        if (node.isNull()) return "null";
        if (node.isBoolean()) return "boolean";
        if (node.isNumber()) return "number";
        if (node.isArray()) {
            TreeSet<String> elements = new TreeSet<>();
            for (JsonNode el : node) elements.add(token(el));
            return "array<" + String.join("|", elements) + ">";
        }
        if (node.isString()) return stringToken(node.asString());
        throw new IllegalArgumentException("unsupported JSON node type: " + node.getNodeType());
    }

    static String stringToken(String s) {
        if (JWT.matcher(s).matches()) return "jwt";
        if (UUID.matcher(s).matches()) return "uuid";
        Matcher iso = ISO_OFFSET.matcher(s);
        if (iso.matches()) return "iso-offset(" + iso.group(1) + ")";
        if (B64URL.matcher(s).matches() && HAS_LETTER.matcher(s).find()) return "b64url";
        return "string";
    }
}
