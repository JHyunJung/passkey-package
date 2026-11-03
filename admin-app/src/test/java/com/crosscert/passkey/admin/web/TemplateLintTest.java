package com.crosscert.passkey.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

/**
 * 템플릿 정적 검사. components.css 의 {@code [hidden] { display: none !important }} 는 Alpine x-show 의 인라인 display 를
 * 이기므로, 정적 {@code hidden}(또는 th:hidden)과 {@code x-show} 를 한 요소에 같이 두면 그 요소는 영영 보이지 않는다.
 * 미리 숨기려면 {@code x-cloak} 만 쓴다.
 */
class TemplateLintTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    @Test
    void noElementCombinesXShowWithStaticHidden() throws IOException {
        List<String> bad = new ArrayList<>();
        try (Stream<Path> s = Files.walk(TEMPLATES)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".html")).toList()) {
                String html = Files.readString(p, StandardCharsets.UTF_8);
                for (Element e : Jsoup.parse(html).getAllElements()) {
                    boolean show = e.hasAttr("x-show");
                    boolean hidden = e.hasAttr("hidden") || e.hasAttr("th:hidden");
                    if (show && hidden) bad.add(TEMPLATES.relativize(p) + " <" + e.tagName() + " x-show=\"" + e.attr("x-show") + "\">");
                }
            }
        }
        assertThat(bad).as("x-show 와 정적 hidden 을 함께 쓴 요소").isEmpty();
    }
}
