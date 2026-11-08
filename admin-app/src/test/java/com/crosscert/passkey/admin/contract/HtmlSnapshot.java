package com.crosscert.passkey.admin.contract;

import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 렌더 HTML 스냅샷 — 정규화와 스냅샷 텍스트 조립.
 *
 * <p>정규화({@link #normalize(String)})는 실행마다 달라지는 값만 고정 토큰으로 바꾸고 나머지는 바이트 그대로 둔다.
 * <ul>
 *   <li>CSRF 토큰 값 — {@code name="_csrf" value="…"} 숨은 입력과 레이아웃의 {@code "X-XSRF-TOKEN": "…"} → {@value #CSRF}.
 *       {@link #of(MvcResult)} 는 요청의 실제 토큰 문자열도 본문·헤더 어디에 있든 같은 토큰으로 바꾼다.</li>
 *   <li>CSP nonce — {@code nonce="…"} 속성 값 → {@value #NONCE}.</li>
 *   <li>현재 시각 — 벽시계 기준 시각은 슬라이스의 {@code Clock} mock 으로 이미 고정된다. 그래도 {@code Clock} 을 거치지 않는
 *       값이 섞이면 잡히도록, 테스트 JVM 이 시작된 날짜(UTC·KST)의 ISO 시각({@code yyyy-MM-ddTHH:mm[:ss[.f]][Z|±hh:mm]})만
 *       {@value #NOW} 로 바꾼다 — 고정 시계의 날짜(2026-10)와 겹치지 않으므로 스텁 값은 건드리지 않는다.</li>
 *   <li>랜덤 ID — 버전 4 UUID({@code xxxxxxxx-xxxx-4xxx-[89ab]xxx-xxxxxxxxxxxx}) → {@value #RANDOM_ID}. 테스트 스텁의 ID 는
 *       전부 버전 0({@code 00000000-0000-0000-…}) 이라 그대로 남는다.</li>
 *   <li>traceId — {@code TraceIdFilter} 가 요청마다 만드는 16 hex. 토스트 JSON 의 {@code "traceId":"…"} 와 응답
 *       {@code X-Trace-Id} 값이 나오는 모든 자리 → {@value #TRACE_ID}.</li>
 *   <li>속성 순서 — 운영 코드의 {@code Map.of} 순회 순서가 JVM 마다 달라 {@code ui:attrs} 속성 순서가 바뀐다. 시작 태그의
 *       속성을 이름순으로 맞춘다({@link #sortAttributes(String)} — 값·공백은 그대로).</li>
 * </ul>
 *
 * <p>스냅샷 텍스트: 머리말({@code @status}, {@code @contentType}, {@code HX-*}·{@code Location} 응답 헤더 — 이름순) + 빈 줄 + 정규화한 본문.
 */
final class HtmlSnapshot {

    static final String CSRF = "{{CSRF}}";
    static final String NONCE = "{{NONCE}}";
    static final String NOW = "{{NOW}}";
    static final String RANDOM_ID = "{{RANDOM_ID}}";
    static final String TRACE_ID = "{{TRACE_ID}}";

    private static final Pattern CSRF_INPUT = Pattern.compile("(name=\"_csrf\"\\s+value=\")([^\"]*)(\")");
    private static final Pattern CSRF_INPUT_REV = Pattern.compile("(value=\")([^\"]*)(\"\\s+name=\"_csrf\")");
    private static final Pattern XSRF_HEADER = Pattern.compile("(X-XSRF-TOKEN(?:&quot;|\"):\\s*(?:&quot;|\"))([^&\"]*)((?:&quot;|\"))");
    private static final Pattern NONCE_ATTR = Pattern.compile("(nonce=\")([^\"]*)(\")");
    /** 요청마다 새로 만드는 traceId(TraceIdFilter: UUID 앞 16 hex) — 토스트 JSON 의 {@code "traceId":"…"}. */
    private static final Pattern TRACE_JSON = Pattern.compile("(\"traceId\"\\s*:\\s*\")([0-9a-f]{16})(\")");
    private static final Pattern UUID_V4 = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\b");
    /** 테스트 JVM 시작 날짜(UTC·KST 하루 차이 포함)의 ISO 시각. */
    private static final Pattern REAL_NOW = realNowPattern(java.time.Instant.now());

    private HtmlSnapshot() {}

    static Pattern realNowPattern(java.time.Instant now) {
        TreeSet<String> days = new TreeSet<>();
        for (int d = -1; d <= 1; d++) {
            days.add(now.plus(java.time.Duration.ofDays(d)).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString());
        }
        return Pattern.compile("(?:" + String.join("|", days) + ")T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,9})?)?(?:Z|[+-]\\d{2}:\\d{2})?");
    }

    /** 실행마다 달라지는 값만 고정 토큰으로 바꾼다. */
    static String normalize(String s) {
        return normalize(s, REAL_NOW);
    }

    static String normalize(String s, Pattern realNow) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        String out = replaceGroup(s, CSRF_INPUT, CSRF);
        out = replaceGroup(out, CSRF_INPUT_REV, CSRF);
        out = replaceGroup(out, XSRF_HEADER, CSRF);
        out = replaceGroup(out, NONCE_ATTR, NONCE);
        out = replaceGroup(out, TRACE_JSON, TRACE_ID);
        out = UUID_V4.matcher(out).replaceAll(Matcher.quoteReplacement(RANDOM_ID));
        out = realNow.matcher(out).replaceAll(Matcher.quoteReplacement(NOW));
        return sortAttributes(out);
    }

    /**
     * 시작 태그 안의 속성 순서를 이름순으로 맞춘다. 운영 코드가 {@code ui:attrs} 에 {@code Map.of(...)}(순회 순서가 JVM
     * 실행마다 다름)를 넘기는 곳이 있어 같은 코드도 실행마다 속성 순서가 바뀐다 — HTML 의미에는 영향이 없다.
     * 속성 사이 공백(줄바꿈 포함)은 제자리에 두고 속성 텍스트만 정렬된 순서로 다시 놓으므로, 이미 이름순인 태그는 바이트
     * 그대로다. 주석·{@code <!DOCTYPE>}·{@code <script>}/{@code <style>} 본문은 건드리지 않는다. 값은 큰따옴표·작은따옴표·
     * 따옴표 없음을 모두 읽는다(따옴표 안의 {@code >} 포함).
     */
    static String sortAttributes(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c != '<') {
                out.append(c);
                i++;
                continue;
            }
            if (s.startsWith("<!--", i)) {
                int end = s.indexOf("-->", i + 4);
                end = end < 0 ? n : end + 3;
                out.append(s, i, end);
                i = end;
                continue;
            }
            if (i + 1 >= n || !Character.isLetter(s.charAt(i + 1))) {
                out.append(c);
                i++;
                continue;
            }
            int j = i + 1;
            while (j < n && !Character.isWhitespace(s.charAt(j)) && s.charAt(j) != '>' && s.charAt(j) != '/') j++;
            String tag = s.substring(i + 1, j);
            List<String> seps = new ArrayList<>();
            List<String> attrs = new ArrayList<>();
            int k = j;
            boolean ok = true;
            while (true) {
                int ws = k;
                while (k < n && Character.isWhitespace(s.charAt(k))) k++;
                if (k >= n) { ok = false; break; }
                char d = s.charAt(k);
                if (d == '>' || (d == '/' && k + 1 < n && s.charAt(k + 1) == '>')) {
                    seps.add(s.substring(ws, k));
                    break;
                }
                if (k == ws) { ok = false; break; } // 속성 앞에 공백이 없다 — 손대지 않는다
                seps.add(s.substring(ws, k));
                int a = k;
                while (k < n && !Character.isWhitespace(s.charAt(k)) && s.charAt(k) != '=' && s.charAt(k) != '>'
                        && !(s.charAt(k) == '/' && k + 1 < n && s.charAt(k + 1) == '>')) k++;
                if (k < n && s.charAt(k) == '=') {
                    k++;
                    if (k < n && (s.charAt(k) == '"' || s.charAt(k) == '\'')) {
                        char q = s.charAt(k);
                        int close = s.indexOf(q, k + 1);
                        if (close < 0) { ok = false; break; }
                        k = close + 1;
                    } else {
                        while (k < n && !Character.isWhitespace(s.charAt(k)) && s.charAt(k) != '>') k++;
                    }
                }
                if (k == a) { ok = false; break; }
                attrs.add(s.substring(a, k));
            }
            if (!ok) {
                out.append(c);
                i++;
                continue;
            }
            int close = s.charAt(k) == '>' ? k + 1 : k + 2;
            List<String> sorted = new ArrayList<>(attrs);
            sorted.sort(java.util.Comparator.comparing(HtmlSnapshot::attrName));
            out.append('<').append(tag);
            for (int x = 0; x < sorted.size(); x++) out.append(seps.get(x)).append(sorted.get(x));
            out.append(seps.get(seps.size() - 1)).append(s, k, close);
            i = close;
            String lower = tag.toLowerCase(java.util.Locale.ROOT);
            if (lower.equals("script") || lower.equals("style")) {
                int end = s.toLowerCase(java.util.Locale.ROOT).indexOf("</" + lower, i);
                end = end < 0 ? n : end;
                out.append(s, i, end);
                i = end;
            }
        }
        return out.toString();
    }

    private static String attrName(String attr) {
        int eq = attr.indexOf('=');
        return eq < 0 ? attr : attr.substring(0, eq);
    }

    private static String replaceGroup(String s, Pattern p, String token) {
        Matcher m = p.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + token + m.group(3)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 응답 → 스냅샷 텍스트(머리말 + 정규화 본문). */
    static String of(MvcResult r) throws Exception {
        MockHttpServletResponse res = r.getResponse();
        List<String> secrets = new ArrayList<>();
        Object attr = r.getRequest().getAttribute(CsrfToken.class.getName());
        if (attr == null) attr = r.getRequest().getAttribute("_csrf");
        if (attr instanceof CsrfToken t && t.getToken() != null && !t.getToken().isEmpty()) secrets.add(t.getToken());

        StringBuilder head = new StringBuilder();
        head.append("@status: ").append(res.getStatus()).append('\n');
        head.append("@contentType: ").append(res.getContentType()).append('\n');
        if (res.getRedirectedUrl() != null && res.getHeader("Location") == null) {
            head.append("@redirectedUrl: ").append(res.getRedirectedUrl()).append('\n');
        }
        if (res.getForwardedUrl() != null) head.append("@forwardedUrl: ").append(res.getForwardedUrl()).append('\n');
        for (String name : new TreeSet<>(res.getHeaderNames())) {
            if (!name.regionMatches(true, 0, "HX-", 0, 3) && !name.equalsIgnoreCase("Location")) continue;
            for (String v : res.getHeaders(name)) head.append("@header ").append(name).append(": ").append(v).append('\n');
        }
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        String text = head + "\n" + body;
        for (String secret : secrets) text = text.replace(secret, CSRF);
        String trace = res.getHeader("X-Trace-Id");
        if (trace != null && !trace.isEmpty()) text = text.replace(trace, TRACE_ID);
        return normalize(text);
    }

    /** 응답을 {@code contract/html/<path>} 스냅샷과 비교(기록 모드에서는 없는 파일만 기록). */
    static void assertMatches(String path, MvcResult r) throws Exception {
        String actual = of(r);
        java.nio.file.Path expected = java.nio.file.Path.of("src/test/resources/contract/html").resolve(path);
        if (java.nio.file.Files.exists(expected) && !java.nio.file.Files.readString(expected).equals(actual)) {
            // 진단용: 실제 렌더 결과를 build/contract-actual/html/** 에 남긴다(diff -r 로 비교). 스냅샷은 건드리지 않는다.
            java.nio.file.Path out = java.nio.file.Path.of("build/contract-actual/html").resolve(path);
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, actual);
        }
        ContractSnapshot.assertMatches("html/" + path, actual);
    }
}
