package com.crosscert.passkey.admin.contract

import org.springframework.security.web.csrf.CsrfToken
import org.springframework.test.web.servlet.MvcResult
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.TreeSet
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * 렌더 HTML 스냅샷 — 정규화와 스냅샷 텍스트 조립.
 *
 * 정규화([normalize])는 실행마다 달라지는 값만 고정 토큰으로 바꾸고 나머지는 바이트 그대로 둔다.
 * - CSRF 토큰 값 — `name="_csrf" value="…"` 숨은 입력과 레이아웃의 `"X-XSRF-TOKEN": "…"` → [CSRF].
 *   [of] 는 요청의 실제 토큰 문자열도 본문·헤더 어디에 있든 같은 토큰으로 바꾼다.
 * - CSP nonce — `nonce="…"` 속성 값 → [NONCE].
 * - 현재 시각 — 벽시계 기준 시각은 슬라이스의 `Clock` mock 으로 이미 고정된다. 그래도 `Clock` 을 거치지 않는
 *   값이 섞이면 잡히도록, 테스트 JVM 이 시작된 날짜(UTC·KST)의 ISO 시각(`yyyy-MM-ddTHH:mm[:ss[.f]][Z|±hh:mm]`)만
 *   [NOW] 로 바꾼다 — 고정 시계의 날짜(2026-10)와 겹치지 않으므로 스텁 값은 건드리지 않는다.
 * - 랜덤 ID — 버전 4 UUID(`xxxxxxxx-xxxx-4xxx-[89ab]xxx-xxxxxxxxxxxx`) → [RANDOM_ID]. 테스트 스텁의 ID 는
 *   전부 버전 0(`00000000-0000-0000-…`) 이라 그대로 남는다.
 * - traceId — `TraceIdFilter` 가 요청마다 만드는 16 hex. 토스트 JSON 의 `"traceId":"…"` 와 응답
 *   `X-Trace-Id` 값이 나오는 모든 자리 → [TRACE_ID].
 * - 속성 순서 — 운영 코드의 `Map.of` 순회 순서가 JVM 마다 달라 `ui:attrs` 속성 순서가 바뀐다. 시작 태그의
 *   속성을 이름순으로 맞춘다([sortAttributes] — 값·공백은 그대로).
 *
 * 스냅샷 텍스트: 머리말(`@status`, `@contentType`, `HX-*`·`Location` 응답 헤더 — 이름순) + 빈 줄 + 정규화한 본문.
 *
 * (Kotlin 전환) 정규식은 원 Java 와 같은 `java.util.regex.Pattern`, 공백 판정은 `Character.isWhitespace`
 * (Kotlin `Char.isWhitespace` 는 NBSP 등 공백 문자까지 포함해 다르다).
 */
internal object HtmlSnapshot {

    const val CSRF = "{{CSRF}}"
    const val NONCE = "{{NONCE}}"
    const val NOW = "{{NOW}}"
    const val RANDOM_ID = "{{RANDOM_ID}}"
    const val TRACE_ID = "{{TRACE_ID}}"

    private val CSRF_INPUT: Pattern = Pattern.compile("(name=\"_csrf\"\\s+value=\")([^\"]*)(\")")
    private val CSRF_INPUT_REV: Pattern = Pattern.compile("(value=\")([^\"]*)(\"\\s+name=\"_csrf\")")
    private val XSRF_HEADER: Pattern =
        Pattern.compile("(X-XSRF-TOKEN(?:&quot;|\"):\\s*(?:&quot;|\"))([^&\"]*)((?:&quot;|\"))")
    private val NONCE_ATTR: Pattern = Pattern.compile("(nonce=\")([^\"]*)(\")")

    /** 요청마다 새로 만드는 traceId(TraceIdFilter: UUID 앞 16 hex) — 토스트 JSON 의 `"traceId":"…"`. */
    private val TRACE_JSON: Pattern = Pattern.compile("(\"traceId\"\\s*:\\s*\")([0-9a-f]{16})(\")")
    private val UUID_V4: Pattern = Pattern.compile(
        "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\b",
    )

    /** 테스트 JVM 시작 날짜(UTC·KST 하루 차이 포함)의 ISO 시각. */
    private val REAL_NOW: Pattern = realNowPattern(Instant.now())

    fun realNowPattern(now: Instant): Pattern {
        val days = TreeSet<String>()
        for (d in -1..1) {
            days.add(now.plus(Duration.ofDays(d.toLong())).atZone(ZoneOffset.UTC).toLocalDate().toString())
        }
        return Pattern.compile(
            "(?:" + days.joinToString("|") + ")T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,9})?)?(?:Z|[+-]\\d{2}:\\d{2})?",
        )
    }

    /** 실행마다 달라지는 값만 고정 토큰으로 바꾼다. */
    fun normalize(s: String?): String = normalize(s, REAL_NOW)

    fun normalize(s: String?, realNow: Pattern): String {
        if (s == null || s.isEmpty()) return s ?: ""
        var out = replaceGroup(s, CSRF_INPUT, CSRF)
        out = replaceGroup(out, CSRF_INPUT_REV, CSRF)
        out = replaceGroup(out, XSRF_HEADER, CSRF)
        out = replaceGroup(out, NONCE_ATTR, NONCE)
        out = replaceGroup(out, TRACE_JSON, TRACE_ID)
        out = UUID_V4.matcher(out).replaceAll(Matcher.quoteReplacement(RANDOM_ID))
        out = realNow.matcher(out).replaceAll(Matcher.quoteReplacement(NOW))
        return sortAttributes(out)
    }

    /**
     * 시작 태그 안의 속성 순서를 이름순으로 맞춘다. 운영 코드가 `ui:attrs` 에 `Map.of(...)`(순회 순서가 JVM
     * 실행마다 다름)를 넘기는 곳이 있어 같은 코드도 실행마다 속성 순서가 바뀐다 — HTML 의미에는 영향이 없다.
     * 속성 사이 공백(줄바꿈 포함)은 제자리에 두고 속성 텍스트만 정렬된 순서로 다시 놓으므로, 이미 이름순인 태그는 바이트
     * 그대로다. 주석·`<!DOCTYPE>`·`<script>`/`<style>` 본문은 건드리지 않는다. 값은 큰따옴표·작은따옴표·
     * 따옴표 없음을 모두 읽는다(따옴표 안의 `>` 포함).
     */
    fun sortAttributes(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c != '<') {
                out.append(c)
                i++
                continue
            }
            if (s.startsWith("<!--", i)) {
                var end = s.indexOf("-->", i + 4)
                end = if (end < 0) n else end + 3
                out.append(s, i, end)
                i = end
                continue
            }
            if (i + 1 >= n || !Character.isLetter(s[i + 1])) {
                out.append(c)
                i++
                continue
            }
            var j = i + 1
            while (j < n && !Character.isWhitespace(s[j]) && s[j] != '>' && s[j] != '/') j++
            val tag = s.substring(i + 1, j)
            val seps = ArrayList<String>()
            val attrs = ArrayList<String>()
            var k = j
            var ok = true
            while (true) {
                val ws = k
                while (k < n && Character.isWhitespace(s[k])) k++
                if (k >= n) {
                    ok = false
                    break
                }
                val d = s[k]
                if (d == '>' || (d == '/' && k + 1 < n && s[k + 1] == '>')) {
                    seps.add(s.substring(ws, k))
                    break
                }
                if (k == ws) { // 속성 앞에 공백이 없다 — 손대지 않는다
                    ok = false
                    break
                }
                seps.add(s.substring(ws, k))
                val a = k
                while (k < n && !Character.isWhitespace(s[k]) && s[k] != '=' && s[k] != '>' &&
                    !(s[k] == '/' && k + 1 < n && s[k + 1] == '>')
                ) {
                    k++
                }
                if (k < n && s[k] == '=') {
                    k++
                    if (k < n && (s[k] == '"' || s[k] == '\'')) {
                        val q = s[k]
                        val close = s.indexOf(q, k + 1)
                        if (close < 0) {
                            ok = false
                            break
                        }
                        k = close + 1
                    } else {
                        while (k < n && !Character.isWhitespace(s[k]) && s[k] != '>') k++
                    }
                }
                if (k == a) {
                    ok = false
                    break
                }
                attrs.add(s.substring(a, k))
            }
            if (!ok) {
                out.append(c)
                i++
                continue
            }
            val close = if (s[k] == '>') k + 1 else k + 2
            val sorted = ArrayList(attrs)
            sorted.sortWith(Comparator.comparing { attr: String -> attrName(attr) })
            out.append('<').append(tag)
            for (x in sorted.indices) out.append(seps[x]).append(sorted[x])
            out.append(seps[seps.size - 1]).append(s, k, close)
            i = close
            val lower = tag.lowercase(Locale.ROOT)
            if (lower == "script" || lower == "style") {
                var end = s.lowercase(Locale.ROOT).indexOf("</$lower", i)
                end = if (end < 0) n else end
                out.append(s, i, end)
                i = end
            }
        }
        return out.toString()
    }

    private fun attrName(attr: String): String {
        val eq = attr.indexOf('=')
        return if (eq < 0) attr else attr.substring(0, eq)
    }

    private fun replaceGroup(s: String, p: Pattern, token: String): String {
        val m = p.matcher(s)
        val sb = StringBuilder()
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + token + m.group(3)))
        }
        m.appendTail(sb)
        return sb.toString()
    }

    /** 응답 → 스냅샷 텍스트(머리말 + 정규화 본문). */
    fun of(r: MvcResult): String {
        val res = r.response
        val secrets = ArrayList<String>()
        var attr: Any? = r.request.getAttribute(CsrfToken::class.java.name)
        if (attr == null) attr = r.request.getAttribute("_csrf")
        if (attr is CsrfToken) {
            val token: String? = attr.token
            if (token != null && token.isNotEmpty()) secrets.add(token)
        }

        val head = StringBuilder()
        head.append("@status: ").append(res.status).append('\n')
        head.append("@contentType: ").append(res.contentType).append('\n')
        if (res.redirectedUrl != null && res.getHeader("Location") == null) {
            head.append("@redirectedUrl: ").append(res.redirectedUrl).append('\n')
        }
        if (res.forwardedUrl != null) head.append("@forwardedUrl: ").append(res.forwardedUrl).append('\n')
        for (name in TreeSet(res.headerNames)) {
            if (!name.regionMatches(0, "HX-", 0, 3, ignoreCase = true) && !name.equals("Location", ignoreCase = true)) continue
            for (v in res.getHeaders(name)) head.append("@header ").append(name).append(": ").append(v).append('\n')
        }
        val body = res.getContentAsString(StandardCharsets.UTF_8)
        var text = head.toString() + "\n" + body
        for (secret in secrets) text = text.replace(secret, CSRF)
        val trace: String? = res.getHeader("X-Trace-Id")
        if (trace != null && trace.isNotEmpty()) text = text.replace(trace, TRACE_ID)
        return normalize(text)
    }

    /** 응답을 `contract/html/<path>` 스냅샷과 비교(기록 모드에서는 없는 파일만 기록). */
    fun assertMatches(path: String, r: MvcResult) {
        val actual = of(r)
        val expected = Path.of("src/test/resources/contract/html").resolve(path)
        if (Files.exists(expected) && Files.readString(expected) != actual) {
            // 진단용: 실제 렌더 결과를 build/contract-actual/html/** 에 남긴다(diff -r 로 비교). 스냅샷은 건드리지 않는다.
            val out = Path.of("build/contract-actual/html").resolve(path)
            Files.createDirectories(out.parent)
            Files.writeString(out, actual)
        }
        ContractSnapshot.assertMatches("html/$path", actual)
    }
}
