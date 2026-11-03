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
 * 이 테스트는 등록·설정 문자열이 소스에 있는지만 지킨다(동작 검증 아님). 실제 동작은 브라우저 확인 대상이다(parity 문서).
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
        assertThat(js).doesNotContain("Alpine.store('toasts')").doesNotContain("Alpine.data('toastHost'");
        assertThat(js).contains("new CustomEvent('toast'");
        assertThat(Files.readString(Path.of("src/main/resources/static/admin/js/ui.js"), StandardCharsets.UTF_8))
                .contains("Alpine.data('toastHost'");
    }

    @Test
    @DisplayName("공통 틀 컴포넌트 등록: platformKey, shellMenu, chainCard")
    void components() throws Exception {
        assertThat(appJs()).contains("Alpine.data('platformKey'").contains("Alpine.data('shellMenu'").contains("Alpine.data('chainCard'").contains("Alpine.store('chain'");
    }

    @Test
    @DisplayName("소스 문자열 가드(동작 검증 아님): 라이선스 링크 숨김 표식, 조각 동기화 처리, Esc window 리스너")
    void sourceGuards() throws Exception {
        String js = appJs();
        assertThat(js).contains("data-hide-on-path").contains("htmx:afterSettle").contains("setTimeout");
        String header = Files.readString(Path.of("src/main/resources/templates/admin/fragments/shell/header.html"), StandardCharsets.UTF_8);
        String switcher = Files.readString(Path.of("src/main/resources/templates/admin/fragments/shell/tenant-switcher.html"), StandardCharsets.UTF_8);
        assertThat(header).contains("x-on:keydown.escape.window=\"close()\"");
        assertThat(switcher).contains("x-on:keydown.escape.window=\"close()\"");
    }

    // ── Task 5: 저장 안 한 변경 · 단축키 · 팔레트 · 유휴 모달 (문자열 가드, 동작 검증 아님) ──

    private static String uiJs() throws IOException {
        return Files.readString(Path.of("src/main/resources/static/admin/js/ui.js"), StandardCharsets.UTF_8);
    }

    /** 첫 {@code marker} 부터 짝이 맞는 중괄호까지(리스너 본문). */
    private static String block(String js, String marker) {
        int i = js.indexOf(marker);
        assertThat(i).as(marker).isGreaterThanOrEqualTo(0);
        int open = js.indexOf('{', js.indexOf("function", i));
        int depth = 0;
        for (int k = open; k < js.length(); k++) {
            char c = js.charAt(k);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return js.substring(i, k + 1);
        }
        throw new AssertionError("unbalanced: " + marker);
    }

    @Test
    @DisplayName("dirty 스토어: count·mark·clear 가 있고, 저장 성공(HX-Trigger saved)이 clear() 를 부른다")
    void dirtyStore() throws Exception {
        String js = appJs();
        assertThat(js).contains("Alpine.store('dirty'").contains("count: 0").contains("mark()").contains("clear()");
        assertThat(block(js, "document.addEventListener('saved'")).contains("Alpine.store('dirty').clear()");
    }

    @Test
    @DisplayName("htmx:beforeRequest 리스너와 beforeunload 리스너가 둘 다 Alpine.store('dirty') 를 참조한다")
    void guardListenersReferenceDirtyStore() throws Exception {
        String js = appJs();
        String before = block(js, "document.addEventListener('htmx:beforeRequest'");
        assertThat(before).contains("Alpine.store('dirty')").contains("e.preventDefault()").contains("unsaved-ask")
                .contains("boosted").contains("'get'");
        String unload = block(js, "window.addEventListener('beforeunload'");
        assertThat(unload).contains("Alpine.store('dirty')").contains("e.preventDefault()");
        // beforeunload 리스너는 한 곳뿐(R10: 이중 경고 방지) — saveBar 는 달지 않는다.
        assertThat(js.split("addEventListener\\('beforeunload'", -1).length - 1).isEqualTo(1);
        assertThat(uiJs()).doesNotContain("beforeunload'");
    }

    @Test
    @DisplayName("saveBar 는 변경 수·저장 중을 dirty 스토어에 보고하고, unsavedGuard 는 스토어로 판단·이동을 다시 시도한다")
    void saveBarAndGuardUseStore() throws Exception {
        String ui = uiJs();
        assertThat(ui).contains("Alpine.store('dirty').report(").contains("Alpine.store('dirty').disarm()");
        String guard = ui.substring(ui.indexOf("Alpine.data('unsavedGuard'"));
        assertThat(guard).contains("Alpine.store('dirty').needsGuard()").contains("Alpine.store('dirty').allow()").contains("window.adminNavigate(");
        assertThat(guard).doesNotContain("onChange(");
    }

    @Test
    @DisplayName("전역 단축키 키 매핑: ⌘/Ctrl+K, ?, g 다음 t/a/c/s (useGoShortcuts·usePaletteShortcut·useHelpShortcut 와 같은 규칙)")
    void keyMap() throws Exception {
        String js = appJs();
        assertThat(js).contains("{ t: '/admin/tenants', a: '/admin/activity', c: '/admin/audit-chain', s: '/admin/settings' }");
        assertThat(js).contains("String(e.key).toLowerCase() !== 'k'").contains("document.querySelector('[role=\"alertdialog\"][aria-modal=\"true\"]')")
                .contains("e.key !== '?' || e.metaKey || e.ctrlKey || e.altKey || e.repeat")
                .contains("document.querySelector('[aria-modal=\"true\"]')")
                .contains("/^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName)").contains("[contenteditable]:not([contenteditable=\"false\"])")
                .contains("setTimeout(function () { goArmed = false; }, 1000)")
                .contains("data-go-shortcuts");
    }

    @Test
    @DisplayName("유휴 세션 모달 상수: 경고 60초 전, 최소 10초, 연장 유예 10초, 활동 이벤트 4종, 연장은 GET /admin/api/me, 로그아웃은 POST 폼")
    void idleConstants() throws Exception {
        String js = appJs();
        assertThat(js).contains("Alpine.data('idleSession'").contains("WARN_LEAD_SECONDS = 60").contains("EXTEND_GRACE_MS = 10000")
                .contains("Math.max(this.total - WARN_LEAD_SECONDS, 10)").contains("Math.max(this.total - this.warnAfter, 1)")
                .contains("['mousemove', 'keydown', 'click', 'scroll']").contains("fetch('/admin/api/me'")
                .contains("idle-logout-form").contains("세션 만료까지 10초 남았습니다.").contains("세션 만료까지 30초 남았습니다.");
        assertThat(js).contains("Alpine.data('commandPalette'");
    }

    @Test
    @DisplayName("[소스 문자열 가드] 팔레트: 입력칸 autofocus, 트랩은 noreturn, show() 가 opener 를 기억·입력칸 포커스, close() 가 opener 복귀")
    void paletteFocusSourceGuard() throws IOException {
        String tpl = Files.readString(Path.of("src/main/resources/templates/admin/fragments/shell/command-palette.html"), StandardCharsets.UTF_8);
        assertThat(tpl).contains("id=\"palette-input\"").contains("x-ref=\"input\"").contains("autofocus").contains("x-trap.noscroll.noreturn=\"open\"");
        // x-show 는 rAF 를 기다린다 — 팔레트 표시는 동기 style 바인딩이어야 한다.
        assertThat(tpl).contains("x-bind:style=\"open ? null : 'display: none'\"").doesNotContain("x-show=\"open\"");
        String js = appJs();
        int start = js.indexOf("Alpine.data('commandPalette'");
        assertThat(start).isPositive();
        String pal = js.substring(start, start + 4000);
        assertThat(pal).contains("this.opener = ae").contains("this.$refs.input.focus()").contains("o.focus()");
    }

    @Test
    @DisplayName("빈 자리 요소(#chain-warn · #activity-detail)는 :empty 일 때 숨긴다")
    void emptySlotsAreHidden() throws IOException {
        String css = Files.readString(Path.of("src/main/resources/static/admin/css/components.css"), StandardCharsets.UTF_8);
        assertThat(css).contains("#chain-warn:empty, #activity-detail:empty { display: none; }");
        // 적용된 필터 래퍼는 상자를 만들지 않아(display: contents) 숨은 행이 있어도 필터 카드의 gap 을 차지하지 않는다.
        assertThat(css).contains("#activity-applied { display: contents; }");
    }
}
