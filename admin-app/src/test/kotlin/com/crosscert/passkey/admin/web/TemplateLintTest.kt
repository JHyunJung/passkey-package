package com.crosscert.passkey.admin.web

import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 템플릿 정적 검사. components.css 의 `[hidden] { display: none !important }` 는 Alpine x-show 의 인라인 display 를
 * 이기므로, 정적 `hidden`(또는 th:hidden)과 `x-show` 를 한 요소에 같이 두면 그 요소는 영영 보이지 않는다.
 * 미리 숨기려면 `x-cloak` 만 쓴다.
 */
class TemplateLintTest {

    companion object {
        private val TEMPLATES: Path = Path.of("src/main/resources/templates")
    }

    @Test
    fun noElementCombinesXShowWithStaticHidden() {
        val bad = ArrayList<String>()
        Files.walk(TEMPLATES).use { s ->
            for (p in s.filter { f -> f.toString().endsWith(".html") }.toList()) {
                val html = Files.readString(p, StandardCharsets.UTF_8)
                for (e in Jsoup.parse(html).allElements) {
                    val show = e.hasAttr("x-show")
                    val hidden = e.hasAttr("hidden") || e.hasAttr("th:hidden")
                    if (show && hidden) bad.add("${TEMPLATES.relativize(p)} <${e.tagName()} x-show=\"${e.attr("x-show")}\">")
                }
            }
        }
        assertThat(bad).`as`("x-show 와 정적 hidden 을 함께 쓴 요소").isEmpty()
    }
}
