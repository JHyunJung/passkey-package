package com.crosscert.passkey.admin.web.tenant

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.StringNode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.regex.Pattern

/**
 * 감사 로그 탭의 순수 규칙(← pages/tenant/auditHelpers.ts). 화면·REST 와 무관하게 1:1 로 옮겼다.
 *
 * 이벤트 타입을 받는 함수는 non-null 파라미터 — 유일한 호출자(AuditTabController)가 DB 값을 `action ?: ""` 로 넘긴다.
 */
object AuditHelpers {

    data class Window(val key: String, val hours: Int, val label: String, val longLabel: String)

    /** WINDOWS — 선언 순서가 세그먼트 순서. */
    val WINDOWS: List<Window> = java.util.List.of(
        Window("24h", 24, "24시간", "최근 24시간"),
        Window("7d", 168, "7일", "최근 7일"),
        Window("30d", 720, "30일", "최근 30일"),
    )

    const val DEFAULT_WINDOW = "24h"

    fun window(key: String?): Window {
        for (w in WINDOWS) if (w.key == key) return w
        return WINDOWS[0]
    }

    data class Range(val from: String, val to: String)

    /** windowRange: now 기준 hours 시간 전 ~ now. JS toISOString 과 같은 밀리초 ISO. */
    fun windowRange(hours: Int, now: Instant): Range {
        val n = now.truncatedTo(ChronoUnit.MILLIS)
        return Range(iso(n.minus(hours.toLong(), ChronoUnit.HOURS)), iso(n))
    }

    /** `2026-10-10T03:00:00.000Z` */
    fun iso(t: Instant): String {
        val s = t.truncatedTo(ChronoUnit.MILLIS).toString()
        // Instant.toString 은 밀리초가 0 이면 소수부를 생략한다.
        if (s.length == 20) return s.substring(0, 19) + ".000Z"
        if (s.length == 22) return s.substring(0, 21) + "00Z"
        if (s.length == 23) return s.substring(0, 22) + "0Z"
        return s
    }

    // ── 실패·톤 ─────────────────────────────────────────────

    /** ActivityPage 의 FAILURE_TYPES 와 같은 기준. */
    val FAILURE_TYPES: Set<String> = java.util.Set.of("SIGNATURE_COUNTER_REGRESSION", "ATTESTATION_TRUST_FAILED", "ADMIN_LOGIN_FAILED")
    private val FAILURE_NAME: Pattern = Pattern.compile("_(FAILED|FAIL|REGRESSION)$")
    private val WARN_NAME: Pattern = Pattern.compile("_(UPDATED|ROTATED|REVOKED|ISSUED|SUSPENDED|DELETED)$")
    private val INFO_NAME: Pattern = Pattern.compile("(SUCCESS|AUTHENTICATED|REGISTERED|LOGIN)$")

    /** result 가 있으면 그 값(FAILURE), 없으면 액션 이름 기준. REST 에는 result 가 없어 보통 null. */
    fun isFailure(eventType: String, result: String?): Boolean {
        if (result != null) return "FAILURE" == result
        return FAILURE_TYPES.contains(eventType) || FAILURE_NAME.matcher(eventType).find()
    }

    /** bad | warn | info | neutral */
    fun eventTone(eventType: String, result: String?): String {
        if (isFailure(eventType, result)) return "bad"
        if (WARN_NAME.matcher(eventType).find()) return "warn"
        if (INFO_NAME.matcher(eventType).find()) return "info"
        return "neutral"
    }

    // ── 이벤트 종류 그룹 ─────────────────────────────────────

    data class EventGroup(val id: String, val label: String, val codes: String)

    val EVENT_GROUPS: List<EventGroup> = java.util.List.of(
        EventGroup("auth", "인증", "AUTH_* · AUTHENTICATION_* · CREDENTIAL_AUTHENTICATED · SIGNATURE_*"),
        EventGroup("registration", "등록", "REGISTRATION_* · CREDENTIAL_REGISTERED · ATTESTATION_TRUST_*"),
        EventGroup("config", "설정 변경", "WEBAUTHN_CONFIG_* · AAGUID_* · ATTESTATION_POLICY_* · TENANT_*"),
        EventGroup("keys", "키 관리", "API_KEY_* · CREDENTIAL_REVOKE*"),
        EventGroup("other", "기타", "위 네 그룹에 속하지 않는 이벤트"),
    )

    private val G_AUTH: Pattern = Pattern.compile("^AUTH(ENTICATION)?_")
    private val G_SIG: Pattern = Pattern.compile("^SIGNATURE_")
    private val G_REG: Pattern = Pattern.compile("^REGISTRATION_")
    private val G_TRUST: Pattern = Pattern.compile("^ATTESTATION_TRUST_")
    private val G_CONFIG: Pattern = Pattern.compile("^(WEBAUTHN_CONFIG|AAGUID|ATTESTATION_POLICY|TENANT)_")
    private val G_KEY: Pattern = Pattern.compile("^API_KEY_")
    private val G_REVOKE: Pattern = Pattern.compile("^CREDENTIAL_REVOKE")

    fun isGroupId(id: String?): Boolean {
        for (g in EVENT_GROUPS) if (g.id == id) return true
        return false
    }

    /** 이벤트 타입 → 그룹. 어느 그룹에도 맞지 않으면 'other'. */
    fun eventGroup(t: String): String {
        if (G_AUTH.matcher(t).find() || "CREDENTIAL_AUTHENTICATED" == t || G_SIG.matcher(t).find()) return "auth"
        if (G_REG.matcher(t).find() || "CREDENTIAL_REGISTERED" == t || G_TRUST.matcher(t).find()) return "registration"
        if (G_CONFIG.matcher(t).find()) return "config"
        if (G_KEY.matcher(t).find() || G_REVOKE.matcher(t).find()) return "keys"
        return "other"
    }

    // ── payload diff ─────────────────────────────────────────

    /** from/to: 값 없음(undefined)이면 null. */
    data class DiffEntry(val field: String, val from: JsonNode?, val to: JsonNode?)

    private fun isObj(n: JsonNode?): Boolean = n != null && n.isObject

    private fun get(obj: JsonNode, key: String): JsonNode? = if (obj.has(key)) obj.get(key) else null

    /** JSON.stringify 같은 비교용 문자열(없으면 null). */
    private fun str(n: JsonNode?): String? = n?.toString()

    fun payloadDiff(payload: JsonNode?): List<DiffEntry> {
        val out = ArrayList<DiffEntry>()
        if (payload == null || payload.isNull || !payload.isObject) return out
        val before = get(payload, "before")
        val after = get(payload, "after")
        if (before != null && isObj(before) && after != null && isObj(after)) {
            val keys = LinkedHashSet<String>()
            keys.addAll(before.propertyNames())
            keys.addAll(after.propertyNames())
            for (k in keys) {
                val b = get(before, k)
                val a = get(after, k)
                if (str(b) != str(a)) out.add(DiffEntry(k, b, a))
            }
            return out
        }
        for (e in payload.properties()) {
            val v = e.value
            if (isObj(v) && v.has("from") && v.has("to")) out.add(DiffEntry(e.key, v.get("from"), v.get("to")))
        }
        return out
    }

    /** undefined → —, 문자열은 그대로, 그 밖은 JSON. */
    fun diffValue(v: JsonNode?): String {
        if (v == null) return "—"
        return if (v.isString) v.asString() else v.toString()
    }

    // ── JSON.stringify(v, null, 2) ───────────────────────────

    /** 들여쓰기 2칸, 빈 객체·배열은 {} / [] — JS 의 JSON.stringify(payload ?? {}, null, 2) 와 같은 모양. */
    fun pretty(n: JsonNode?): String {
        if (n == null) return "{}"
        val sb = StringBuilder()
        write(sb, n, 0)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, n: JsonNode, depth: Int) {
        if (n.isObject) {
            if (n.size() == 0) {
                sb.append("{}")
                return
            }
            sb.append("{\n")
            val it = n.properties().iterator()
            while (it.hasNext()) {
                val e = it.next()
                indent(sb, depth + 1)
                sb.append(StringNode(e.key).toString()).append(": ")
                write(sb, e.value, depth + 1)
                if (it.hasNext()) sb.append(',')
                sb.append('\n')
            }
            indent(sb, depth)
            sb.append('}')
        } else if (n.isArray) {
            if (n.size() == 0) {
                sb.append("[]")
                return
            }
            sb.append("[\n")
            for (i in 0 until n.size()) {
                indent(sb, depth + 1)
                write(sb, n.get(i), depth + 1)
                if (i < n.size() - 1) sb.append(',')
                sb.append('\n')
            }
            indent(sb, depth)
            sb.append(']')
        } else {
            sb.append(n.toString())
        }
    }

    private fun indent(sb: StringBuilder, depth: Int) {
        for (i in 0 until depth) sb.append("  ")
    }
}
