package com.crosscert.passkey.app.contract

import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.util.TreeMap
import java.util.TreeSet

/**
 * 응답 JSON 을 "형태(shape)" 로 정규화한다 — 값은 버리고 경로별 타입 토큰만 남긴다.
 *
 * 출력은 `JSON 경로 → 토큰` 맵을 키 정렬한 pretty JSON 이다. 키를 정렬하므로
 * 필드 순서는 비교 대상이 아니다. 경로 표기: 루트 `$`, 객체 필드 `$.a.b`,
 * 배열 원소 `$.a[]` (모든 원소의 경로를 합집합으로 합친다).
 *
 * 토큰 규칙(위에서부터 처음 일치하는 것):
 * - 객체 → `object`, null → `null`, boolean → `boolean`, 숫자(정수·실수 구분 없음) → `number`
 * - 배열 → `array<원소토큰들>` — 원소 토큰의 정렬된 합집합을 `|` 로 잇는다. 빈 배열은 `array<>`.
 * - 문자열은 아래 패턴 순서로 판정, 어느 것도 아니면 `string`:
 *   1. `jwt` — 점으로 구분된 base64url 세그먼트 3개(header·payload 각 10자 이상 —
 *      `a.b.c` 같은 일반 문자열 오탐 방지)
 *   2. `uuid` — 8-4-4-4-12 16진수(하이픈 포함)
 *   3. `iso-offset(<offset>)` — `yyyy-MM-ddTHH:mm[:ss[.f+]]` 뒤에 오프셋
 *      (`Z` 또는 `±HH:MM`). 오프셋 문자열을 그대로 토큰에 남긴다
 *      (예: `iso-offset(+09:00)`, `Instant` 는 `iso-offset(Z)`).
 *      소수 초 자릿수는 무시한다(값마다 달라질 수 있으므로).
 *   4. `b64url` — `[A-Za-z0-9_-]` 로만 이뤄진 [B64URL_MIN_LEN]자 이상이면서
 *      영문자를 하나 이상 포함. 최소 길이는 결정성을 위한 보수적 경계다: 랜덤 16자 16진수
 *      traceId 처럼 짧은 휘발 값이 "우연히 숫자만" 나와 토큰이 흔들리는 일을 막고,
 *      16바이트 userHandle(22자)·32바이트 challenge(43자)·키 thumbprint(43자)는 확실히
 *      잡는다. 숫자만으로 된 문자열은 길이와 무관하게 `string` 이다.
 *
 * 휘발 값(challenge·token·id·시각)은 이 규칙으로 값 대신 토큰으로만 남는다. 고정 입력
 * (예: 테스트가 보낸 displayName)은 매 실행 같은 토큰이 되므로 결정적이다.
 */
object JsonShape {

    const val B64URL_MIN_LEN = 20

    private val MAPPER: ObjectMapper = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build()

    private val JWT = Regex("^[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]+$")
    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val ISO_OFFSET = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})$")
    private val B64URL = Regex("^[A-Za-z0-9_-]{$B64URL_MIN_LEN,}$")
    private val HAS_LETTER = Regex("[A-Za-z]")

    /** JSON 문자열 → 형태 pretty JSON. */
    fun of(json: String): String {
        val root = try {
            MAPPER.readTree(json)
        } catch (e: JacksonException) {
            throw IllegalArgumentException("not JSON: ${e.message}", e)
        }
        return of(root)
    }

    /** 파싱된 JSON 트리 → 형태 pretty JSON. */
    fun of(root: JsonNode): String {
        val paths = TreeMap<String, TreeSet<String>>()
        walk("$", root, paths)
        val out = MAPPER.createObjectNode()
        paths.forEach { (path, tokens) -> out.put(path, tokens.joinToString("|")) }
        return try {
            MAPPER.writeValueAsString(out) + "\n"
        } catch (e: JacksonException) {
            throw IllegalStateException(e)
        }
    }

    private fun walk(path: String, node: JsonNode, paths: MutableMap<String, TreeSet<String>>) {
        paths.getOrPut(path) { TreeSet() }.add(token(node))
        if (node.isObject) {
            for ((key, value) in node.properties()) walk("$path.$key", value, paths)
        } else if (node.isArray) {
            for (element in node) walk("$path[]", element, paths)
        }
    }

    internal fun token(node: JsonNode): String = when {
        node.isObject -> "object"
        node.isNull -> "null"
        node.isBoolean -> "boolean"
        node.isNumber -> "number"
        node.isArray -> node.mapTo(TreeSet()) { token(it) }.joinToString("|", prefix = "array<", postfix = ">")
        node.isString -> stringToken(node.asString())
        else -> throw IllegalArgumentException("unsupported JSON node type: ${node.nodeType}")
    }

    internal fun stringToken(s: String): String {
        if (JWT.matches(s)) return "jwt"
        if (UUID.matches(s)) return "uuid"
        ISO_OFFSET.matchEntire(s)?.let { return "iso-offset(${it.groupValues[1]})" }
        if (B64URL.matches(s) && HAS_LETTER.containsMatchIn(s)) return "b64url"
        return "string"
    }
}
