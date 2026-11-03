package com.crosscert.passkey.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 폐쇄망 배포 보장: 템플릿·정적 리소스(vendor 제외)에 외부 URL 참조가 없고,
 * th:utext / x-html 이 쓰이지 않으며, vendor JS 버전이 README 에 고정돼 있는지 검사한다.
 */
class OfflineAssetScanTest {

    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final Path VENDOR = RESOURCES.resolve("static/admin/js/vendor");
    private static final Pattern EXTERNAL_URL =
            Pattern.compile("(?i)(src|href|url\\()\\s*=?\\s*[\"']?(https?:)?//");

    private static List<Path> scanTargets() throws IOException {
        List<Path> out = new ArrayList<>();
        for (String dir : new String[] {"templates", "static"}) {
            Path root = RESOURCES.resolve(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(root)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> !p.startsWith(VENDOR))
                        .filter(p -> {
                            String n = p.getFileName().toString();
                            return n.endsWith(".html") || n.endsWith(".css") || n.endsWith(".js");
                        })
                        .forEach(out::add);
            }
        }
        return out;
    }

    @Test
    void assertNoExternalUrl() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path p : scanTargets()) {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            Matcher m = EXTERNAL_URL.matcher(text);
            while (m.find()) {
                String tail = text.substring(m.start(), Math.min(text.length(), m.end() + 40));
                if (tail.contains("http://www.thymeleaf.org") || tail.contains("xmlns")) {
                    continue;
                }
                hits.add(p + " : " + tail.replace('\n', ' '));
            }
        }
        assertThat(hits).as("외부 URL 참조").isEmpty();
    }

    @Test
    void assertNoUnsafeHtml() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path p : scanTargets()) {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            if (text.contains("th:utext") || text.contains("x-html")) {
                hits.add(p.toString());
            }
        }
        assertThat(hits).as("th:utext / x-html 사용").isEmpty();
    }

    @Test
    void assertVendorPinned() throws IOException {
        Path readme = VENDOR.resolve("README.md");
        assertThat(readme).as("vendor README").exists();
        String text = Files.readString(readme, StandardCharsets.UTF_8);
        assertThat(text).contains("htmx.org@2.0.4", "alpinejs@3.14.8", "@alpinejs/focus@3.14.8");
    }
}
