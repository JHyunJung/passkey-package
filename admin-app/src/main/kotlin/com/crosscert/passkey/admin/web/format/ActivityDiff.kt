package com.crosscert.passkey.admin.web.format

import com.crosscert.passkey.admin.web.support.StandaloneJson
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.NullNode

/**
 * 활동 상세 패널 "어떻게 바뀜" 행(← pages/activityDiff.ts, 1:1).
 * 값(from/to)은 JSON 노드이고 `null` 이 JS 의 `undefined`(키 없음), [NullNode] 가 JSON null 이다.
 */
object ActivityDiff {

    data class DiffRow(val field: String?, val from: JsonNode?, val to: JsonNode?)

    data class ArrayChange(val added: List<String>, val removed: List<String>)

    /** JSON.parse 와 같이 뒤따르는 쓰레기 토큰을 거부한다. */
    private val JSON: ObjectMapper = StandaloneJson.jackson2Like()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    private fun asObject(v: JsonNode?): JsonNode? = if (v != null && v.isObject) v else null

    private fun get(obj: JsonNode, key: String): JsonNode? = if (obj.has(key)) obj.get(key) else null

    /** JSON.stringify(v) — 없으면 null(undefined). */
    private fun stringify(v: JsonNode?): String? = v?.toString()

    /** JSON.parse(raw). 실패하면 예외 없이 ok=false. */
    private class Parsed(val ok: Boolean, val node: JsonNode?)

    private fun parse(raw: String?): Parsed {
        if (raw == null) return Parsed(true, NullNode.getInstance()) // JSON.parse(null) → null
        if (raw.javaIsBlank()) return Parsed(false, null)
        return try {
            val n: JsonNode? = JSON.readTree(raw)
            if (n == null || n.isMissingNode) Parsed(false, null) else Parsed(true, n)
        } catch (e: JacksonException) {
            Parsed(false, null)
        }
    }

    /**
     * 1) 서버 ③ 의 detail.diff 가 정의되어 있으면(`diff != null`) 그대로.
     * 2) 아니면 payload 의 before/after 객체(현재 TENANT_UPDATE: { before, after, changedFields }).
     *    changedFields 가 문자열 배열이면 그 순서, 아니면 JSON 값이 다른 키만.
     */
    @JvmStatic
    fun buildDiffRows(diff: List<DiffRow>?, payload: String?): List<DiffRow> {
        if (diff != null) return diff
        val parsed = parse(payload)
        if (!parsed.ok) return java.util.List.of()
        val p = asObject(parsed.node) ?: return java.util.List.of()
        val before = asObject(get(p, "before"))
        val after = asObject(get(p, "after"))
        if (before == null || after == null) return java.util.List.of()
        val fields = ArrayList<String>()
        val cf = get(p, "changedFields")
        val cfArray = if (cf != null && cf.isArray) cf else null
        var allStrings = cfArray != null
        if (cfArray != null) {
            for (f in cfArray) {
                if (!f.isString) {
                    allStrings = false
                    break
                }
            }
        }
        if (allStrings && cfArray != null) {
            for (f in cfArray) fields.add(f.asString())
        } else {
            val keys = LinkedHashSet<String>()
            keys.addAll(before.propertyNames())
            keys.addAll(after.propertyNames())
            for (k in keys) if (stringify(get(before, k)) != stringify(get(after, k))) fields.add(k)
        }
        val rows = ArrayList<DiffRow>()
        for (field in fields) rows.add(DiffRow(field, get(before, field), get(after, field)))
        return rows
    }

    /** 두 값이 모두 배열이면 추가/제거 원소(문자열화), 아니면 null. */
    @JvmStatic
    fun arrayChange(from: JsonNode?, to: JsonNode?): ArrayChange? {
        if (from == null || to == null || !from.isArray || !to.isArray) return null
        val f = ArrayList<String>()
        for (n in from) f.add(jsString(n))
        val t = ArrayList<String>()
        for (n in to) t.add(jsString(n))
        val added = ArrayList<String>()
        for (x in t) if (!f.contains(x)) added.add(x)
        val removed = ArrayList<String>()
        for (x in f) if (!t.contains(x)) removed.add(x)
        return ArrayChange(added, removed)
    }

    /** ActivityPage 의 fmtVal: undefined·null → —, 문자열은 그대로, 그 밖은 JSON.stringify. */
    @JvmStatic
    fun fmtVal(v: JsonNode?): String {
        if (v == null || v.isNull) return "—"
        return if (v.isString) v.asString() else v.toString()
    }

    /** JS `String(x)`. */
    internal fun jsString(n: JsonNode?): String {
        if (n == null) return "undefined"
        if (n.isNull) return "null"
        if (n.isString) return n.asString()
        if (n.isBoolean) return if (n.asBoolean()) "true" else "false"
        if (n.isNumber) {
            if (n.isIntegralNumber) return n.bigIntegerValue().toString()
            val d = n.decimalValue().stripTrailingZeros()
            return if (d.scale() <= 0) d.toBigInteger().toString() else d.toPlainString()
        }
        if (n.isArray) {
            val sb = StringBuilder()
            for (i in 0 until n.size()) {
                if (i > 0) sb.append(',')
                val e = n.get(i)
                if (!e.isNull) sb.append(jsString(e)) // [null].join → ''
            }
            return sb.toString()
        }
        return "[object Object]"
    }
}
