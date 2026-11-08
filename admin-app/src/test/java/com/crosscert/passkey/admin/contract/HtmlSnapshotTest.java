package com.crosscert.passkey.admin.contract;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link HtmlSnapshot#normalize} 는 정해진 대상만 바꾸고 나머지는 바이트 그대로 둔다. */
class HtmlSnapshotTest {

    static final Pattern NOW_2026_11_08 = HtmlSnapshot.realNowPattern(Instant.parse("2026-11-08T05:00:00Z"));

    @Test
    void csrfTokenValuesOnly() {
        String in = "<form><input type=\"hidden\" name=\"_csrf\" value=\"AbC-12_x\"/></form>\n"
                + "<body hx-headers=\"{&quot;X-XSRF-TOKEN&quot;: &quot;q1w2e3&quot;}\">";
        assertThat(HtmlSnapshot.normalize(in, NOW_2026_11_08)).isEqualTo(
                "<form><input name=\"_csrf\" type=\"hidden\" value=\"{{CSRF}}\"/></form>\n"
                        + "<body hx-headers=\"{&quot;X-XSRF-TOKEN&quot;: &quot;{{CSRF}}&quot;}\">");
        // 다른 hidden 입력·다른 헤더 이름은 그대로
        String other = "<input name=\"token\" value=\"AbC\"> X-OTHER-TOKEN&quot;: &quot;q1&quot;";
        assertThat(HtmlSnapshot.normalize(other, NOW_2026_11_08)).isEqualTo(other);
    }

    @Test
    void nonceTraceIdAndRandomUuid() {
        String in = "<script nonce=\"r4nd0m\"></script>"
                + "{\"toast\":{\"traceId\":\"dffd84aa30f8429d\"}}"
                + " 3f2b8c1e-9d4a-4b6f-8e2a-1c3d5e7f9a0b";
        assertThat(HtmlSnapshot.normalize(in, NOW_2026_11_08)).isEqualTo(
                "<script nonce=\"{{NONCE}}\"></script>"
                        + "{\"toast\":{\"traceId\":\"{{TRACE_ID}}\"}}"
                        + " {{RANDOM_ID}}");
    }

    @Test
    void fixedTestIdsAndClockTimesUntouched() {
        // 버전 0 UUID(스텁 ID)·고정 시계 날짜(2026-10)·KST 표기·짧은 hex 는 그대로
        String in = "00000000-0000-0000-0000-00000000f111 2026-10-11T12:34:56+09:00 2026.10.11 12:34 \"traceId\":\"abc\"";
        assertThat(HtmlSnapshot.normalize(in, NOW_2026_11_08)).isEqualTo(in);
    }

    @Test
    void realNowIsoTimesOnly() {
        String in = "a 2026-11-08T05:00:01.123Z b 2026-11-07T23:10+09:00 c 2026-11-08 d 2026-10-13T03:34:56Z";
        assertThat(HtmlSnapshot.normalize(in, NOW_2026_11_08))
                .isEqualTo("a {{NOW}} b {{NOW}} c 2026-11-08 d 2026-10-13T03:34:56Z");
    }

    @Test
    void attributeOrderCanonicalizedValuesAndWhitespaceKept() {
        String in = "<button hx-swap=\"none\"\n        hx-post=\"/a?x=1&amp;y=2\" data-x='{\"a\": \"b > c\"}' disabled>t</button>";
        assertThat(HtmlSnapshot.sortAttributes(in))
                .isEqualTo("<button data-x='{\"a\": \"b > c\"}'\n        disabled hx-post=\"/a?x=1&amp;y=2\" hx-swap=\"none\">t</button>");
        // 이미 이름순이면 바이트 그대로, 두 실행 순서가 달라도 같은 결과
        String sorted = "<a class=\"x\" href=\"/b\">l</a>";
        assertThat(HtmlSnapshot.sortAttributes(sorted)).isEqualTo(sorted);
        assertThat(HtmlSnapshot.sortAttributes("<a href=\"/b\" class=\"x\">l</a>")).isEqualTo(sorted);
        assertThat(HtmlSnapshot.sortAttributes("<br/><img src=\"s\" alt=\"a\" />"))
                .isEqualTo("<br/><img alt=\"a\" src=\"s\" />");
    }

    @Test
    void commentsScriptsAndTextUntouched() {
        String in = "<!-- <b z=\"1\" a=\"2\"> --><!DOCTYPE html>"
                + "<script b=\"1\" a=\"2\">if (a<b && c>d) { x('<i y=1 x=2>') }</script>"
                + "<p>1 < 2 and <b>x</b></p>";
        assertThat(HtmlSnapshot.sortAttributes(in)).isEqualTo("<!-- <b z=\"1\" a=\"2\"> --><!DOCTYPE html>"
                + "<script a=\"2\" b=\"1\">if (a<b && c>d) { x('<i y=1 x=2>') }</script>"
                + "<p>1 < 2 and <b>x</b></p>");
    }

    @Test
    void plainHtmlWithoutTargetsIsByteIdentical() {
        String in = "@status: 200\n@header HX-Trigger: {\"toast\":{\"kind\":\"success\",\"message\":\"\\uC800\"}}\n\n"
                + "<div class=\"a\" id=\"b\">\n  <span>한글 &amp; text</span>\n</div>\n";
        assertThat(HtmlSnapshot.normalize(in, NOW_2026_11_08)).isEqualTo(in);
    }
}
