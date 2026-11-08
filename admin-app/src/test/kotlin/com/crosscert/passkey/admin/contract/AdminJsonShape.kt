package com.crosscert.passkey.admin.contract

import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.util.TreeMap
import java.util.TreeSet
import java.util.regex.Pattern

/**
 * 응답 JSON 을 "형태(shape)" 로 정규화한다 — 값은 버리고 경로별 타입 토큰만 남긴다.
 * passkey-app 의 `contract/JsonShape.kt`(1단계) 와 같은 토큰 규칙이다.
 *
 * 출력은 `키 → 토큰` 맵을 키 정렬한 pretty JSON 이다. 경로 표기: 루트 `$`,
 * 객체 필드 `$.a.b`, 배열 원소 `$.a[]`(모든 원소의 경로를 합집합으로 합친다).
 *
 * 토큰 규칙(위에서부터 처음 일치하는 것):
 * - 객체 → `object`, null → `null`, boolean → `boolean`, 숫자 → `number`
 * - 배열 → `array<원소토큰들>` — 원소 토큰의 정렬된 합집합을 `|` 로 잇는다. 빈 배열은 `array<>`.
 * - 문자열: `jwt` → `uuid` → `iso-offset(<오프셋>)` → `b64url`(20자 이상, 영문자 포함) → `string`
 *
 * **키 순서**: `ordered=true` 로 부르면 객체 경로마다 `"<경로>{}": "k1,k2,…"` 항목을 더해
 * 필드 직렬화 순서까지 고정한다(record → data class 전환에서 Jackson 프로퍼티 순서가 바뀌는지 판정).
 * `Map.of(...)` 로 만든 응답은 JVM 실행마다 순회 순서가 달라지므로(ImmutableCollections SALT) 순서를 고정할 수 없다 —
 * 그런 응답은 `ordered=false` 로 부른다. 한 원소짜리 Map 은 순서가 결정적이다.
 *
 * 상태 코드 등 응답 메타데이터는 `@` 로 시작하는 키로 같은 맵에 싣는다(경로 키 `$…` 와 겹치지 않는다).
 */
internal object AdminJsonShape {

    const val B64URL_MIN_LEN = 20

    private val MAPPER: ObjectMapper = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build()

    private val JWT: Pattern = Pattern.compile("^[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]+\$")
    private val UUID_RE: Pattern =
        Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\$")
    private val ISO_OFFSET: Pattern =
        Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})\$")
    private val B64URL: Pattern = Pattern.compile("^[A-Za-z0-9_-]{$B64URL_MIN_LEN,}\$")
    private val HAS_LETTER: Pattern = Pattern.compile("[A-Za-z]")

    /** JSON 문자열 → 형태 pretty JSON. `meta` 는 `@키 → 값` 으로 그대로 싣는다. */
    fun of(json: String, ordered: Boolean, meta: Map<String, String>): String {
        val root: JsonNode = try {
            MAPPER.readTree(json)
        } catch (e: JacksonException) {
            throw IllegalArgumentException("not JSON: " + e.message + " — body=" + json, e)
        }
        val paths = TreeMap<String, TreeSet<String>>()
        val orders = TreeMap<String, TreeSet<String>>()
        walk("$", root, paths, orders)
        val out = TreeMap<String, String>()
        meta.forEach { (k, v) ->
            if (!k.startsWith("@")) throw IllegalArgumentException("meta key must start with @: $k")
            out[k] = v
        }
        paths.forEach { (p, tokens) -> out[p] = tokens.joinToString("|") }
        if (ordered) orders.forEach { (p, seqs) -> out["$p{}"] = seqs.joinToString(" | ") }
        val node = MAPPER.createObjectNode()
        out.forEach { (k, v) -> node.put(k, v) }
        try {
            return MAPPER.writeValueAsString(node) + "\n"
        } catch (e: JacksonException) {
            throw IllegalStateException(e)
        }
    }

    private fun walk(
        path: String,
        node: JsonNode,
        paths: MutableMap<String, TreeSet<String>>,
        orders: MutableMap<String, TreeSet<String>>,
    ) {
        paths.computeIfAbsent(path) { TreeSet() }.add(token(node))
        if (node.isObject) {
            val keys = ArrayList<String>()
            for (e in node.properties()) {
                keys.add(e.key)
                walk(path + "." + e.key, e.value, paths, orders)
            }
            orders.computeIfAbsent(path) { TreeSet() }.add(keys.joinToString(","))
        } else if (node.isArray) {
            for (element in node) walk("$path[]", element, paths, orders)
        }
    }

    fun token(node: JsonNode): String {
        if (node.isObject) return "object"
        if (node.isNull) return "null"
        if (node.isBoolean) return "boolean"
        if (node.isNumber) return "number"
        if (node.isArray) {
            val inner = TreeSet<String>()
            for (e in node) inner.add(token(e))
            return "array<" + inner.joinToString("|") + ">"
        }
        if (node.isString) return stringToken(node.asString())
        throw IllegalArgumentException("unsupported JSON node type: " + node.nodeType)
    }

    fun stringToken(s: String): String {
        if (JWT.matcher(s).matches()) return "jwt"
        if (UUID_RE.matcher(s).matches()) return "uuid"
        val m = ISO_OFFSET.matcher(s)
        if (m.matches()) return "iso-offset(" + m.group(1) + ")"
        if (B64URL.matcher(s).matches() && HAS_LETTER.matcher(s).find()) return "b64url"
        return "string"
    }
}
