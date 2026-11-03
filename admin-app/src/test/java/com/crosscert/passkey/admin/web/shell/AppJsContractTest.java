package com.crosscert.passkey.admin.web.shell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * app.js 의 약속(htmx 응답 처리·CSRF 갱신·토스트 단일 구현)을 소스 수준에서 고정한다.
 * 실제 동작은 브라우저 확인 대상이다(parity 문서).
 */
class AppJsContractTest {

    private static String appJs() throws IOException {
        return Files.readString(Path.of("src/main/resources/static/admin/js/app.js"), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("responseHandling: 204 는 스왑 안 함, 422 만 스왑(+error), 4xx/5xx 는 스왑 없이 error")
    void responseHandling() throws Exception {
        String js = appJs();
        int i = js.indexOf("htmx.config.responseHandling");
        assertThat(i).isGreaterThan(0);
        String block = js.substring(i, js.indexOf("];", i));
        assertThat(block).contains("{ code: '204', swap: false }")
                .contains("{ code: '422', swap: true, error: true }")
                .contains("{ code: '[23]..', swap: true }")
                .contains("{ code: '[45]..', swap: false, error: true }");
        assertThat(block.indexOf("'422'")).as("422 가 [45].. 보다 앞").isLessThan(block.indexOf("'[45]..'"));
    }

    @Test
    @DisplayName("htmx:configRequest 에서 XSRF-TOKEN 쿠키를 읽어 X-XSRF-TOKEN 헤더를 갱신")
    void csrfRefresh() throws Exception {
        String js = appJs();
        assertThat(js).contains("htmx:configRequest").contains("'XSRF-TOKEN'").contains("e.detail.headers['X-XSRF-TOKEN'] = token");
    }

    @Test
    @DisplayName("토스트는 ui.js 의 toastHost 하나만: app.js 는 별도 스토어·컴포넌트를 만들지 않고 `toast` 이벤트만 올린다")
    void singleToastImplementation() throws Exception {
        String js = appJs();
        assertThat(js).doesNotContain("Alpine.store").doesNotContain("Alpine.data('toastHost'");
        assertThat(js).contains("new CustomEvent('toast'");
        assertThat(Files.readString(Path.of("src/main/resources/static/admin/js/ui.js"), StandardCharsets.UTF_8))
                .contains("Alpine.data('toastHost'");
    }

    @Test
    @DisplayName("공통 틀 컴포넌트 등록: platformKey, shellMenu, chainCard")
    void components() throws Exception {
        assertThat(appJs()).contains("Alpine.data('platformKey'").contains("Alpine.data('shellMenu'").contains("Alpine.data('chainCard'");
    }
}
