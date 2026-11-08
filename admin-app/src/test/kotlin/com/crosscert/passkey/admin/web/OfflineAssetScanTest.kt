package com.crosscert.passkey.admin.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

/**
 * 폐쇄망 배포 보장: 템플릿·정적 리소스(vendor 제외)에 외부 URL 참조가 없고,
 * th:utext / x-html 이 쓰이지 않으며, vendor JS 버전이 README 에 고정돼 있는지 검사한다.
 */
class OfflineAssetScanTest {

    companion object {
        private val RESOURCES: Path = Path.of("src/main/resources")
        private val VENDOR: Path = RESOURCES.resolve("static/admin/js/vendor")
        private val EXTERNAL_URL: Pattern =
            Pattern.compile("(?i)(src|href|url\\()\\s*=?\\s*[\"']?(https?:)?//")

        private val URL_TOKEN: Pattern = Pattern.compile("(https?:)?//[^\"'\\s)>]*")

        private fun scanTargets(): List<Path> {
            val out = ArrayList<Path>()
            for (dir in arrayOf("templates", "static")) {
                val root = RESOURCES.resolve(dir)
                if (!Files.isDirectory(root)) {
                    continue
                }
                Files.walk(root).use { s ->
                    s.filter { Files.isRegularFile(it) }
                        .filter { p -> !p.startsWith(VENDOR) }
                        .filter { p ->
                            val n = p.fileName.toString()
                            n.endsWith(".html") || n.endsWith(".css") || n.endsWith(".js")
                        }
                        .forEach { out.add(it) }
                }
            }
            return out
        }

        /**
         * 외부 URL 참조를 찾는다. 예외는 매치된 URL 토큰 자체가 정확히 http://www.thymeleaf.org 인 경우뿐이다.
         * xmlns / xmlns:* 속성은 패턴(src|href|url()에 걸리지 않으므로 별도 예외가 필요 없다.
         */
        fun findExternalUrls(text: String): List<String> {
            val hits = ArrayList<String>()
            val m = EXTERNAL_URL.matcher(text)
            while (m.find()) {
                val tok = URL_TOKEN.matcher(text)
                val g2: String? = m.group(2)
                tok.region(m.end() - (if (g2 == null) 2 else g2.length + 2), text.length)
                val url = if (tok.lookingAt()) tok.group() else ""
                if (url == "http://www.thymeleaf.org") {
                    continue
                }
                hits.add(text.substring(m.start(), Math.min(text.length, m.end() + 40)).replace('\n', ' '))
            }
            return hits
        }
    }

    @Test
    fun assertNoExternalUrl() {
        val hits = ArrayList<String>()
        for (p in scanTargets()) {
            for (h in findExternalUrls(Files.readString(p, StandardCharsets.UTF_8))) {
                hits.add("$p : $h")
            }
        }
        assertThat(hits).`as`("외부 URL 참조").isEmpty()
    }

    @Test
    fun scannerRejectsExternalUrlEvenNextToXmlns() {
        assertThat(
            findExternalUrls(
                "<image href=\"https://cdn.example/a.svg\" xmlns=\"http://www.w3.org/2000/svg\"/>",
            ),
        ).hasSize(1)
        assertThat(findExternalUrls("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>")).isEmpty()
        assertThat(findExternalUrls("<html xmlns:th=\"http://www.thymeleaf.org\">")).isEmpty()
        assertThat(findExternalUrls("<a href=\"//cdn.example/x\">")).hasSize(1)
        assertThat(findExternalUrls("a{background:url(https://x.example/f.woff2)}")).hasSize(1)
        assertThat(findExternalUrls("<a href=\"http://www.thymeleaf.org.evil/x\">")).hasSize(1)
    }

    @Test
    fun assertNoUnsafeHtml() {
        val hits = ArrayList<String>()
        for (p in scanTargets()) {
            val text = Files.readString(p, StandardCharsets.UTF_8)
            if (text.contains("th:utext") || text.contains("x-html")) {
                hits.add(p.toString())
            }
        }
        assertThat(hits).`as`("th:utext / x-html 사용").isEmpty()
    }

    @Test
    fun assertVendorPinned() {
        val readme = VENDOR.resolve("README.md")
        assertThat(readme).`as`("vendor README").exists()
        val text = Files.readString(readme, StandardCharsets.UTF_8)
        assertThat(text).contains("htmx.org@2.0.4", "alpinejs@3.14.8", "@alpinejs/focus@3.14.8")
    }
}
