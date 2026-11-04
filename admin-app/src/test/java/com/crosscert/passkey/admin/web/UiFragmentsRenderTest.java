package com.crosscert.passkey.admin.web;

import com.crosscert.passkey.admin.web.format.ViewHelpers;
import com.crosscert.passkey.admin.web.support.UiDialect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.server.ResponseStatusException;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.StringTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공통 UI 조각(templates/admin/fragments)을 Thymeleaf 로 렌더링해, 원본 컴포넌트(admin-ui/src/ui/*.test.tsx)가
 * 확인하던 클래스명·ARIA 속성이 같은 마크업으로 나오는지 본다. 브라우저에서 도는 Alpine 동작은 여기서 다루지 않고
 * 속성(x-data, x-on:…)이 붙었는지만 본다.
 */
class UiFragmentsRenderTest {

    private static SpringTemplateEngine engine;
    private static GenericApplicationContext appCtx;
    private static final String UI = "admin/fragments/ui/";

    @BeforeAll
    static void setUp() {
        appCtx = new GenericApplicationContext();
        appCtx.registerBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC));
        appCtx.registerBean("fmt", ViewHelpers.class);
        appCtx.refresh();

        StringTemplateResolver host = new StringTemplateResolver();
        host.setOrder(1);
        host.setTemplateMode(TemplateMode.HTML);
        host.setResolvablePatterns(java.util.Set.of("<*"));
        host.setCacheable(false);
        ClassLoaderTemplateResolver files = new ClassLoaderTemplateResolver();
        files.setOrder(2);
        files.setPrefix("templates/");
        files.setSuffix(".html");
        files.setTemplateMode(TemplateMode.HTML);
        files.setCharacterEncoding("UTF-8");
        files.setCacheable(false);
        engine = new SpringTemplateEngine();
        engine.addTemplateResolver(host);
        engine.addTemplateResolver(files);
        engine.addDialect(new UiDialect());
    }

    /** 조각 호출식 하나를 렌더링한다. 예: ui("button", "button('저장', 'primary', null)", Map.of()) */
    private static String ui(String file, String call, Map<String, Object> vars) {
        return renderExpr("~{" + UI + file + " :: " + call + "}", vars);
    }

    private static String icon(String call) {
        return renderExpr("~{admin/fragments/icons :: " + call + "}", Map.of());
    }

    private static String renderExpr(String fragmentExpr, Map<String, Object> vars) {
        MockServletContext sc = new MockServletContext();
        MockHttpServletRequest req = new MockHttpServletRequest(sc);
        MockHttpServletResponse res = new MockHttpServletResponse();
        WebContext ctx = new WebContext(JakartaServletWebApplication.buildApplication(sc).buildExchange(req, res), Locale.KOREAN, new java.util.HashMap<>(vars));
        ctx.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, new ThymeleafEvaluationContext(appCtx, null));
        return engine.process("<div th:replace=\"" + fragmentExpr + "\"></div>", ctx).replaceAll("\\s+", " ").trim();
    }

    /** 첫 번째로 {@code <tag ...class="...">} 가 나오는 여는 태그 전체. */
    private static String tag(String html, String contains) {
        Matcher m = Pattern.compile("<[a-zA-Z][^>]*" + Pattern.quote(contains) + "[^>]*>").matcher(html);
        assertThat(m.find()).as("태그 '%s' in %s", contains, html).isTrue();
        return m.group();
    }

    private static int count(String html, String needle) {
        return html.split(Pattern.quote(needle), -1).length - 1;
    }

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
        return r;
    }

    // ── 아이콘 ────────────────────────────────────────────

    @Test
    @DisplayName("아이콘 38종: svg, aria-hidden, currentColor, 크기 인자, xmlns 없음")
    void iconsRender() {
        List<String> names = List.of("shield", "key", "users", "building", "cog", "activity", "receipt", "filter", "search", "plus", "x",
                "check", "chevronDown", "chevronRight", "chevronLeft", "chevronUp", "copy", "eye", "eyeOff", "trash", "alert", "info",
                "help", "server", "lock", "logOut", "refresh", "download", "externalLink", "fingerprint", "link", "globe", "calendar",
                "hash", "menu", "dots", "sparkles", "brandMark");
        assertThat(names).hasSize(38);
        for (String n : names) {
            String html = icon(n + "(20)");
            assertThat(html).as(n).startsWith("<svg").contains("width=\"20\"").contains("height=\"20\"").contains("aria-hidden=\"true\"").doesNotContain("xmlns");
            if (!n.equals("brandMark")) assertThat(html).as(n).contains("stroke=\"currentColor\"");
        }
        assertThat(icon("check(null)")).contains("width=\"16\"");
        assertThat(icon("brandMark(null)")).contains("width=\"22\"");
    }

    @Test
    @DisplayName("Dots 아이콘은 세 점이 같은 y(가로)")
    void dotsAreHorizontal() {
        String html = icon("dots(18)");
        assertThat(count(html, "<circle")).isEqualTo(3);
        assertThat(count(html, "cy=\"12\"")).isEqualTo(3);
    }

    // ── basics.test.tsx ──────────────────────────────────

    @Test
    @DisplayName("Badge tone 이 기존 클래스 체계로 매핑된다")
    void badgeToneClasses() {
        String ok = ui("badge", "badge('활성', 'ok', null, null, null)", Map.of());
        assertThat(tag(ok, "class=")).contains("badge", "badge--success", "badge--dot");
        assertThat(ui("badge", "badge('정지', 'bad', null, null, null)", Map.of())).contains("badge--danger");
        String plat = ui("badge", "badge('PLATFORM', 'plat', false, null, null)", Map.of());
        assertThat(plat).contains("badge--violet").doesNotContain("badge--dot");
    }

    @Test
    @DisplayName("StatusBadge 가 상태코드를 한국어 라벨로 보여준다")
    void statusBadgeKorean() {
        String html = ui("statusBadge", "statusBadge('ACTIVE')", Map.of());
        assertThat(html).contains(">활성<").contains("badge--success").contains("title=\"ACTIVE\"");
        assertThat(ui("statusBadge", "statusBadge('REVOKED')", Map.of())).contains("badge--danger");
        assertThat(ui("statusBadge", "statusBadge('NEW_ONE')", Map.of())).contains(">NEW_ONE<");
    }

    @Test
    @DisplayName("Button 은 기본 type=button, variant 클래스")
    void buttonTypeAndVariant() {
        String html = ui("button", "button('회수', 'danger-solid', null)", Map.of());
        assertThat(html).contains("type=\"button\"").contains("class=\"btn btn--danger-solid\"").contains(">회수<");
        assertThat(ui("button", "button('저장', null, null)", Map.of())).contains("class=\"btn\"");
    }

    @Test
    @DisplayName("Button attrs 는 속성으로 붙고 class 는 덧붙으며 값 없는 속성도 쓴다")
    void buttonAttrs() {
        Map<String, Object> attrs = new java.util.LinkedHashMap<>();
        attrs.put("hx-post", "/admin/x?a=1&b=\"2\"");
        attrs.put("class", "extra");
        attrs.put("disabled", "");
        String html = ui("button", "button('실행', 'primary', ${attrs})", m("attrs", attrs));
        assertThat(html).contains("hx-post=\"/admin/x?a=1&amp;b=&quot;2&quot;\"").contains("disabled=\"\"");
        assertThat(tag(html, "hx-post")).containsPattern("class=\"btn extra btn--primary\"");
    }

    @Test
    @DisplayName("ui:attrs: on* 인라인 이벤트 이름은 버리고, false·null 은 생략, true 는 값 없는 속성")
    void attrsBlocksInlineHandlersAndHandlesBooleans() {
        Map<String, Object> attrs = new java.util.LinkedHashMap<>();
        attrs.put("onclick", "alert(1)");
        attrs.put("OnMouseOver", "alert(2)");
        attrs.put("hx-get", "/ok");
        attrs.put("disabled", true);
        attrs.put("hidden", false);
        attrs.put("title", null);
        attrs.put("bad name", "x");
        String html = ui("button", "button('x', null, ${attrs})", m("attrs", attrs));
        assertThat(html).contains("hx-get=\"/ok\"").contains("disabled=\"\"")
                .doesNotContainIgnoringCase("onclick").doesNotContainIgnoringCase("onmouseover").doesNotContain("alert")
                .doesNotContain("hidden").doesNotContain("title=").doesNotContain("bad name");
    }

    @Test
    @DisplayName("IconButton 은 label 을 aria-label 로 노출")
    void iconButtonLabel() {
        String html = ui("iconButton", "iconButton('더보기', ~{admin/fragments/icons :: dots(18)}, 'sm', 'danger', null)", Map.of());
        assertThat(html).contains("aria-label=\"더보기\"").contains("title=\"더보기\"").contains("icon-btn icon-btn--sm icon-btn--danger").contains("<svg");
    }

    @Test
    @DisplayName("KpiCard attrs 가 있으면 aria-pressed 버튼")
    void kpiCardClickable() {
        String html = ui("kpiCard", "kpiCard('활성 Tenant', '3', null, null, true, ${ {'hx-get': '/x'} })", Map.of());
        assertThat(html).contains("<button").contains("aria-pressed=\"true\"").contains("kpi--clickable").contains("kpi--selected").contains("활성 Tenant");
    }

    @Test
    @DisplayName("KpiCard attrs 가 없으면 버튼이 아니다")
    void kpiCardPlain() {
        String html = ui("kpiCard", "kpiCard('등록 Credential', '10', '힌트', 'warn', null, null)", Map.of());
        assertThat(html).doesNotContain("<button").contains("class=\"kpi\"").contains("kpi__hint kpi__hint--warn");
    }

    @Test
    @DisplayName("PropChip 긴 값은 말줄임 클래스 + title 로 전체값 제공")
    void propChipLongValue() {
        String longValue = "passkey.".repeat(10) + "example.com";
        String html = ui("propChip", "propChip('RP ID', ${v}, true)", m("v", longValue));
        assertThat(html).contains("class=\"propchip__v\"").contains("title=\"" + longValue + "\"").contains("aria-label=\"RP ID 복사\"");
        assertThat(ui("propChip", "propChip('RP ID', 'a', false)", Map.of())).contains("propchip--nocopy").doesNotContain("<button");
    }

    @Test
    @DisplayName("Alert: 제목+본문 두 줄이면 아이콘을 위로 정렬(banner--top), 한 줄이면 가운데")
    void alertTopAlignment() {
        // 본문 조각은 호출 쪽 템플릿의 요소라 호스트 문자열 안에서 직접 만든다.
        String host = engineHost("<p th:replace=\"~{" + UI + "alert :: alert('ok', '메일을 확인하세요', ~{::b}, null, null)}\"></p><b>본문</b>");
        assertThat(host).contains("banner--top").contains("banner--success");
        String one = engineHost("<p th:replace=\"~{" + UI + "alert :: alert('ok', null, ~{::b}, null, null)}\"></p><b>한 줄</b>");
        assertThat(one).doesNotContain("banner--top");
    }

    @Test
    @DisplayName("Alert bad 는 role=alert")
    void alertBadIsRoleAlert() {
        String html = ui("alert", "alert('bad', '위변조 의심', null, null, null)", Map.of());
        assertThat(html).contains("role=\"alert\"").contains("위변조 의심").contains("banner--danger");
        assertThat(ui("alert", "alert('info', 'x', null, null, null)", Map.of())).contains("role=\"status\"").contains("banner--info");
    }

    @Test
    @DisplayName("CardHeader cap: 제목 옆 한 줄 캡션(card__cap), sub 는 제목 아래")
    void cardHeaderCap() {
        String html = ui("card", "cardHeader('Tenant별 Chain 상태', '설명', '방금 전 검증 기준', null)", Map.of());
        assertThat(html).contains("<h2 class=\"card__title\">Tenant별 Chain 상태</h2>").contains("class=\"card__cap\">방금 전 검증 기준<")
                .contains("class=\"card__sub\">설명<");
        assertThat(html.indexOf("card__title")).isLessThan(html.indexOf("card__cap"));
    }

    @Test
    @DisplayName("EmptyState 일러스트는 장식(alt=\"\")")
    void emptyStateIllustrationIsDecorative() {
        String html = ui("emptyState", "emptyState('/admin/img/empty-tenants.webp', '없음', null, null, false)", Map.of());
        assertThat(html).contains("alt=\"\"").contains("src=\"/admin/img/empty-tenants.webp\"").contains("class=\"empty__title\">없음<");
    }

    // ── forms.test.tsx ───────────────────────────────────

    @Test
    @DisplayName("Switch: Alpine 상태(switchToggle)가 aria-checked 와 제출용 hidden input 을 정한다")
    void switchRole() {
        String html = ui("switch", "switch('MFA 필수', 'mfa', false, null, ${ {'hx-post': '/x'} })", Map.of());
        assertThat(tag(html, "role=\"switch\"")).contains("type=\"checkbox\"", "class=\"sr-only\"", "aria-checked=\"false\"", "x-model=\"on\"",
                "x-bind:aria-checked=\"on\"", "hx-post=\"/x\"").doesNotContain("checked=\"checked\"").doesNotContain("name=");
        assertThat(tag(html, "type=\"hidden\"")).contains("name=\"mfa\"", "value=\"false\"", "x-bind:value=\"on ? 'true' : 'false'\"");
        assertThat(html).contains("class=\"switch\"").contains(">MFA 필수<").contains("switchToggle(").contains("&quot;checked&quot;:false");
        String on = ui("switch", "switch('x', 'f', true, true, null)", Map.of());
        assertThat(tag(on, "role=\"switch\"")).contains("checked").contains("disabled").contains("aria-checked=\"true\"");
        assertThat(tag(on, "type=\"hidden\"")).contains("value=\"true\"");
    }

    @Test
    @DisplayName("Segmented: Alpine 상태(segmented)가 aria-pressed 를 정하고 선택값을 hidden input 으로 제출")
    void segmentedPressed() {
        List<Map<String, Object>> opts = List.of(m("value", "24h", "label", "24h", "attrs", Map.of("hx-get", "/a")), m("value", "7d", "label", "7d"));
        String html = ui("segmented", "segmented('range', ${opts}, '7d', '기간')", m("opts", opts));
        assertThat(html).contains("class=\"seg\"").contains("role=\"group\"").contains("aria-label=\"기간\"").contains("segmented(").contains("&quot;value&quot;:&quot;7d&quot;");
        assertThat(tag(html, "type=\"hidden\"")).contains("name=\"range\"", "value=\"7d\"", "x-bind:value=\"value\"");
        assertThat(tag(html, "data-value=\"7d\"")).contains("aria-pressed=\"true\"", "class=\"seg__btn seg__btn--on\"");
        assertThat(count(html, "aria-pressed=\"false\"")).isEqualTo(1);
        assertThat(html).contains("hx-get=\"/a\"").contains("x-on:click=\"select($el.dataset.value)\"").contains("x-bind:aria-pressed=\"value === $el.dataset.value\"")
                // 객체 바인딩이어야 클라이언트 선택 시 서버가 그린 seg__btn--on 도 제거된다(문자열 바인딩은 기존 클래스를 남긴다)
                .contains("x-bind:class=\"{ 'seg__btn--on': value === $el.dataset.value }\"").doesNotContain("? 'seg__btn seg__btn--on'");
        assertThat(ui("segmented", "segmented(null, ${opts}, '7d', '기간')", m("opts", opts))).doesNotContain("type=\"hidden\"");
    }

    @Test
    @DisplayName("ChoiceCard role=radio")
    void choiceCardRadio() {
        String html = ui("choiceCard", "choiceCard(false, '허용 목록', '설명', null)", Map.of());
        assertThat(tag(html, "role=\"radio\"")).contains("aria-checked=\"false\"").doesNotContain("choice--on");
        assertThat(ui("choiceCard", "choiceCard(true, '허용 목록', null, null)", Map.of())).contains("aria-checked=\"true\"").contains("choice choice--on");
    }

    @Test
    @DisplayName("FilterChip aria-pressed")
    void filterChipPressed() {
        String html = ui("chips", "filterChip('AUTH_*', true, null, null, null, null)", Map.of());
        assertThat(html).contains("aria-pressed=\"true\"").contains("fchip fchip--on").contains(">AUTH_*<");
        String chip = ui("chips", "removableChip('https://a.com', 'https://a.com 삭제', 'added', true, null, null)", Map.of());
        assertThat(chip).contains("chip chip--added mono").contains("aria-label=\"https://a.com 삭제\"").contains("chip__x");
    }

    @Test
    @DisplayName("ChipInput: Enter 로 추가, 삭제 버튼, 오류는 role=alert")
    void chipInputMarkup() {
        String html = ui("chipInput", "chipInput('corsAllowlist', ${vals}, 'origin', 'origin 추가', null, null, '/admin/x/validate', null)",
                m("vals", List.of("https://a.com")));
        assertThat(html).contains("x-data=\"chipInput(").contains("&quot;values&quot;:[&quot;https://a.com&quot;]").contains("&quot;validateUrl&quot;")
                .contains("aria-label=\"origin 추가\"").contains("placeholder=\"origin\"")
                .contains("x-on:keydown.enter.prevent=\"onEnter()\"").contains("class=\"chip__x\"").contains("x-bind:aria-label=\"v + ' 삭제'\"")
                .contains("class=\"field__error\" role=\"alert\"").contains("x-bind:name=\"name\"");
    }

    @Test
    @DisplayName("Field 오류 표시")
    void fieldError() {
        String host = engineHost("<p th:replace=\"~{" + UI + "field :: field('이메일', 'em', '도움말', '필수', true, ~{::i})}\"></p><input id=\"em\" th:remove=\"none\"><i></i>");
        assertThat(host).contains("class=\"field__error\"").contains("id=\"em-error\"").contains(">필수<").contains("field__req").doesNotContain("도움말");
        String help = engineHost("<p th:replace=\"~{" + UI + "field :: field('이메일', 'em', '도움말', null, null, ~{::i})}\"></p><i>x</i>");
        assertThat(help).contains("class=\"hint\">도움말<").doesNotContain("field__error").contains("for=\"em\"");
    }

    // ── Dialog.test.tsx ──────────────────────────────────

    @Test
    @DisplayName("Dialog: 제목으로 라벨링되고 aria-modal, 포커스 트랩, Escape·스크림")
    void dialogMarkup() {
        String html = ui("dialog", "dialog('new-tenant', '신규 Tenant 생성', null, null, null, null, null, null)", Map.of());
        assertThat(tag(html, "role=\"dialog\"")).contains("aria-modal=\"true\"", "aria-labelledby=\"new-tenant-title\"", "x-trap.noscroll=\"open\"");
        assertThat(html).contains("id=\"new-tenant-title\"").contains(">신규 Tenant 생성<")
                .contains("x-on:keydown.escape.window=\"onEscape()\"").contains("x-on:mousedown=\"onScrim($event)\"")
                .contains("x-on:dialog:open.window=\"onOpen($event)\"").contains("class=\"scrim\"");
    }

    @Test
    @DisplayName("Dialog role=alertdialog 지원, dismissible=false 는 설정으로 전달")
    void dialogAlertdialogAndDismissible() {
        String html = ui("dialog", "dialog('x', '정지', null, null, null, null, null, ${ {'role': 'alertdialog', 'dismissible': false, 'closeOnScrim': false} })", Map.of());
        assertThat(html).contains("role=\"alertdialog\"").contains("&quot;dismissible&quot;:false").contains("&quot;closeOnScrim&quot;:false");
    }

    @Test
    @DisplayName("Dialog headerIcon 이 있어도 접근 가능한 이름은 제목뿐")
    void dialogHeaderIcon() {
        String host = engineHost("<p th:replace=\"~{" + UI + "dialog :: dialog('s', '세션 만료', '30초', null, null, null, ~{::b}, null)}\"></p><b>29s</b>");
        assertThat(host).contains("dialog__head dialog__head--icon").contains("aria-labelledby=\"s-title\"").contains(">29s<").contains("dialog__head-text");
        assertThat(count(host, "id=\"s-title\"")).isEqualTo(1);
    }

    @Test
    @DisplayName("Dialog footer 와 title 없는 ariaLabel")
    void dialogFooterAndAriaLabel() {
        String host = engineHost("<p th:replace=\"~{" + UI + "dialog :: dialog('f', null, null, ~{::s}, ~{::u}, null, null, ${ {'ariaLabel': '명령 팔레트', 'wide': 'x'} })}\"></p><s>본문</s><u><button autofocus>취소</button><button>정지</button></u>");
        assertThat(host).contains("aria-label=\"명령 팔레트\"").doesNotContain("aria-labelledby").contains("dialog dialog--xwide")
                .contains("class=\"dialog__body\"").contains("class=\"dialog__foot\"").contains("<button autofocus>취소</button>");
    }

    @Test
    @DisplayName("ConfirmDialog: alertdialog, 기본 포커스는 취소, danger-solid, L3 는 이름 입력")
    void confirmDialogL3() {
        String html = ui("confirmDialog", "confirmDialog('suspend', 'L3', 'Tenant 정지', '정지', '설명', null, null, 'acme', 'slug 입력', null, ${ {'hx-post': '/admin/tenants/1/suspend'} })", Map.of());
        assertThat(tag(html, "role=\"alertdialog\"")).contains("aria-modal=\"true\"", "aria-labelledby=\"suspend-title\"");
        assertThat(html).contains("<button type=\"button\" class=\"btn\" autofocus").contains(">취소<").contains("btn btn--danger-solid").contains("hx-post=\"/admin/tenants/1/suspend\"")
                .contains("x-bind:disabled=\"busy || !gateOk\"").contains("&quot;typing&quot;:true").contains("&quot;closeOnScrim&quot;:false")
                .contains("id=\"suspend-confirm\"").contains("일치해야 정지 버튼이 활성화됩니다.").contains("id=\"suspend-error\"");
        // 실패 처리 계약: 확인 버튼(data-confirm)의 htmx 결과만 보고, 실패하면 열어 둔 채 안에서 오류를 보인다.
        assertThat(html).contains("x-on:htmx:after-request=\"onAfterRequest($event)\"").contains("x-on:htmx:before-request=\"onBeforeRequest($event)\"")
                .contains("&quot;verb&quot;:&quot;정지&quot;").contains("<button type=\"button\" class=\"btn btn--danger-solid\" data-confirm");
        assertThat(tag(html, "id=\"suspend-error\"")).contains("role=\"alert\"", "x-show=\"error\"", "banner banner--danger");
        assertThat(html).contains("x-text=\"error && error.title\"").contains("x-text=\"error && error.text\"");
    }

    @Test
    @DisplayName("ConfirmDialog: L3 확인 체크 문구, danger=false 는 primary")
    void confirmDialogAcknowledge() {
        String html = ui("confirmDialog", "confirmDialog('c', 'L3', '제목', '실행', null, null, false, null, null, '되돌릴 수 없음을 이해했습니다', null)", Map.of());
        assertThat(html).contains("class=\"confirm__ack\"").contains("되돌릴 수 없음을 이해했습니다").contains("&quot;ack&quot;:true").contains("btn btn--primary").doesNotContain("input mono");
        String l1 = ui("confirmDialog", "confirmDialog('c', 'L1', '제목', '삭제', null, null, null, null, null, '무시됨', null)", Map.of());
        assertThat(l1).doesNotContain("confirm__ack").doesNotContain("input mono");
    }

    // ── Tabs.test.tsx / table.test.tsx ───────────────────

    private static List<Map<String, Object>> tabItems() {
        return List.of(m("id", "a", "label", "개요", "href", "/t?tab=a"),
                m("id", "b", "label", "AAGUID 정책", "href", "/t?tab=b", "badgeText", "확인 필요 2", "badgeReason", "MDS 미확인 AAGUID 2건"),
                m("id", "c", "label", "Admin 사용자", "href", "/t?tab=c", "count", 3));
    }

    @Test
    @DisplayName("Tabs: tablist, aria-selected, 선택된 탭만 tabindex=0(로빙)")
    void tabsRoving() {
        String html = ui("tabs", "tabs(${items}, 'a', 'Tenant 탭')", m("items", tabItems()));
        assertThat(tag(html, "role=\"tablist\"")).contains("aria-label=\"Tenant 탭\"").contains("x-data=\"tabs\"");
        assertThat(tag(html, "href=\"/t?tab=a\"")).contains("role=\"tab\"", "aria-selected=\"true\"", "tabindex=\"0\"", "tabs__btn tabs__btn--active");
        assertThat(tag(html, "href=\"/t?tab=b\"")).contains("aria-selected=\"false\"", "tabindex=\"-1\"", "title=\"MDS 미확인 AAGUID 2건\"");
        assertThat(tag(html, "href=\"/t?tab=c\"")).contains("tabindex=\"-1\"");
    }

    @Test
    @DisplayName("Tabs: active 가 어느 항목과도 맞지 않으면 첫 탭이 tabindex=0")
    void tabsFallbackToFirst() {
        String html = ui("tabs", "tabs(${items}, 'zzz', 'Tenant 탭')", m("items", tabItems()));
        assertThat(tag(html, "href=\"/t?tab=a\"")).contains("tabindex=\"0\"").contains("aria-selected=\"false\"");
        assertThat(tag(html, "href=\"/t?tab=b\"")).contains("tabindex=\"-1\"");
        assertThat(tag(html, "href=\"/t?tab=c\"")).contains("tabindex=\"-1\"");
    }

    @Test
    @DisplayName("Tabs: 경고는 '확인 필요 N' 라벨(badge--warning)과 사유 title, count 는 tabs__count")
    void tabsBadgeAndCount() {
        String html = ui("tabs", "tabs(${items}, 'a', 'Tenant 탭')", m("items", tabItems()));
        assertThat(tag(html, "badge badge--warning tabs__badge")).contains("title=\"MDS 미확인 AAGUID 2건\"");
        assertThat(html).contains(">확인 필요 2<").contains("class=\"tabs__count\">3<").doesNotContain("tabs__dot");
    }

    @Test
    @DisplayName("DataTable: 헤더(숫자 열·sr-only), 그룹 행·빈 행·펼침 행 조각")
    void dataTableFragments() {
        List<Map<String, Object>> cols = List.of(m("key", "name", "header", "이름"), m("key", "n", "header", "건수", "align", "right", "width", "80px"), m("key", "act", "header", "액션", "srOnly", true));
        String host = engineHost("<p th:replace=\"~{" + UI + "dataTable :: dataTable(${cols}, ~{::tr})}\"></p><tr><td>a</td></tr>", m("cols", cols));
        assertThat(host).contains("class=\"tw\"").contains("class=\"table\"").contains("style=\"width: 80px\"").contains("class=\"num\"").contains("<span class=\"sr-only\">액션</span>").contains("<td>a</td>");
        assertThat(ui("dataTable", "tableGroupRow(3, '오늘')", Map.of())).contains("class=\"table__group\"").contains("colspan=\"3\"").contains(">오늘<");
        assertThat(engineHost("<p th:replace=\"~{" + UI + "dataTable :: tableEmptyRow(3, ~{::p.e})}\"></p><p class=\"e\">없음</p>")).contains("table__empty").contains("<p class=\"e\">없음</p>");
        assertThat(engineHost("<p th:replace=\"~{" + UI + "dataTable :: tableExpandedRow(3, ~{::.exp})}\"></p><div class=\"exp\">detail-b</div>")).contains("table__expanded").contains("detail-b");
    }

    // ── Pagination (table.test.tsx) ──────────────────────

    @Test
    @DisplayName("Pagination: 범위 텍스트와 경계 비활성")
    void paginationRangeAndBoundaries() {
        String html = ui("pagination", "pagination(1, 50, 120, '/admin/activity?type=A', null, null)", Map.of());
        assertThat(html).contains(">1–50 / 120건<").contains(">1 / 3<");
        assertThat(tag(html, "aria-label=\"이전 페이지\"")).contains("disabled").startsWith("<button");
        assertThat(tag(html, "aria-label=\"다음 페이지\"")).contains("href=\"/admin/activity?type=A&amp;page=2&amp;size=50\"");
    }

    @Test
    @DisplayName("Pagination: 마지막 페이지와 0건")
    void paginationLastAndEmpty() {
        String last = ui("pagination", "pagination(3, 50, 120, '/x', null, null)", Map.of());
        assertThat(last).contains(">101–120 / 120건<");
        assertThat(tag(last, "aria-label=\"다음 페이지\"")).contains("disabled");
        assertThat(ui("pagination", "pagination(1, 50, 0, '/x', null, null)", Map.of())).contains(">0건<");
    }

    @Test
    @DisplayName("Pagination: page 가 마지막을 넘으면 마지막 페이지로 보정")
    void paginationClampsPage() {
        String html = ui("pagination", "pagination(5, 50, 120, '/x', null, null)", Map.of());
        assertThat(html).contains(">101–120 / 120건<").contains(">3 / 3<");
        assertThat(tag(html, "aria-label=\"다음 페이지\"")).contains("disabled");
        assertThat(tag(html, "aria-label=\"이전 페이지\"")).contains("href=\"/x?page=2&amp;size=50\"");
    }

    @Test
    @DisplayName("Pagination: 페이지당 선택")
    void paginationPageSize() {
        String html = ui("pagination", "pagination(1, 50, 10, '/x?q=1', ${ {50, 100, 200} }, null)", Map.of());
        assertThat(html).contains("페이지당").contains("data-base=\"/x?q=1&amp;page=1&amp;size=\"").contains("<option value=\"50\" selected=\"selected\">50</option>")
                .contains("<option value=\"100\">100</option>");
    }

    // ── misc.test.tsx ────────────────────────────────────

    @Test
    @DisplayName("Menu: 트리거(aria-haspopup), 메뉴/항목 role, 위험 구획")
    void menuMarkup() {
        List<Map<String, Object>> items = List.of(m("label", "회전", "attrs", Map.of("hx-post", "/rotate")), m("label", "접두사 복사"));
        List<Map<String, Object>> danger = List.of(m("label", "회수", "danger", true, "attrs", Map.of("hx-post", "/revoke")));
        String html = ui("menu", "menu('키 액션', ${items}, ${danger}, null, null)", m("items", items, "danger", danger));
        assertThat(tag(html, "aria-haspopup=\"menu\"")).contains("class=\"icon-btn\"", "aria-label=\"키 액션\"", "x-on:click.stop=\"toggle()\"");
        assertThat(tag(html, "role=\"menu\"")).contains("aria-label=\"키 액션\"", "x-on:click.stop");
        assertThat(count(html, "role=\"menuitem\"")).isEqualTo(3);
        assertThat(html).contains("class=\"menu__section\">위험<").contains("menu__sep").contains("menu__item menu__item--danger").contains("hx-post=\"/revoke\"");
        assertThat(html).contains("x-on:keydown=\"onKeydown($event)\"").contains("x-data=\"menu\"");
    }

    @Test
    @DisplayName("Menu: trigger=button 이면 테두리 있는 .btn")
    void menuButtonTrigger() {
        String html = ui("menu", "menu('더보기', ${items}, null, null, 'button')", m("items", List.of(m("label", "a"))));
        assertThat(tag(html, "aria-haspopup=\"menu\"")).contains("class=\"btn btn--dots\"");
        assertThat(html).doesNotContain("class=\"icon-btn\"").contains("<circle");
    }

    @Test
    @DisplayName("Menu: 비활성 항목은 aria-disabled 와 사유를 보이고 속성(실행)은 붙지 않는다")
    void menuDisabledItem() {
        String reason = "위변조 의심 tenant가 있으면 쓸 수 없습니다.";
        List<Map<String, Object>> items = List.of(m("label", "내보내기", "attrs", Map.of("hx-get", "/export")),
                m("label", "체인 백필…", "danger", true, "disabled", true, "disabledReason", reason, "attrs", Map.of("hx-post", "/backfill")));
        String html = ui("menu", "menu('더 보기', ${items}, null, null, 'button')", m("items", items));
        String disabled = tag(html, "aria-disabled=\"true\"");
        assertThat(disabled).contains("title=\"" + reason + "\"").doesNotContain("hx-post");
        assertThat(html).contains("class=\"hint\">" + reason + "<").contains("menu__reason");
        assertThat(tag(html, "hx-get=\"/export\"")).doesNotContain("aria-disabled");
    }

    @Test
    @DisplayName("Menu: 항목 아이콘(이름)")
    void menuItemIcon() {
        String html = ui("menu", "menu('m', ${items}, null, null, null)", m("items", List.of(m("label", "복사", "icon", "copy"))));
        assertThat(html).contains("<svg").contains("<rect");
    }

    @Test
    @DisplayName("ConfirmByTyping: actionLabel 이 있으면 안내 문구에 그 버튼 이름을 쓴다")
    void confirmByTypingActionLabel() {
        String html = ui("confirmByTyping", "confirmByTyping('c2', 'acme', 'slug 입력', '정지')", Map.of());
        assertThat(html).contains("일치해야 정지 버튼이 활성화됩니다.").contains("for=\"c2\"").contains("id=\"c2\"").contains("aria-describedby=\"c2-h\"").contains("id=\"c2-h\"")
                .contains("&quot;expected&quot;:&quot;acme&quot;").contains("x-model=\"v\"").contains("class=\"input mono\"");
        assertThat(ui("confirmByTyping", "confirmByTyping('c', 'acme', 'x', null)", Map.of())).contains("일치해야 실행 버튼이 활성화됩니다.");
    }

    @Test
    @DisplayName("StatusStrip 은 요약 라벨을 가진 img")
    void statusStripImg() {
        List<Boolean> results = java.util.Arrays.asList(true, true, false, null);
        String html = ui("statusStrip", "statusStrip(${r}, '최근 4회 중 1회 위변조', null)", m("r", results));
        assertThat(tag(html, "role=\"img\"")).contains("aria-label=\"최근 4회 중 1회 위변조\"", "class=\"strip\"");
        assertThat(count(html, "strip__bar--bad")).isEqualTo(1);
        assertThat(count(html, "strip__bar--none")).isEqualTo(1);
        assertThat(count(html, "class=\"strip__bar")).isEqualTo(4);
    }

    @Test
    @DisplayName("Stepper done 은 체크, now 는 aria-current")
    void stepperStates() {
        List<Map<String, Object>> steps = List.of(m("label", "QR 스캔", "state", "done"), m("label", "코드 확인", "state", "now"), m("label", "복구 코드 저장", "state", "todo", "description", "마지막"));
        String html = ui("stepper", "stepper(null, ${steps})", m("steps", steps));
        assertThat(html).contains("aria-label=\"진행 단계\"").contains(">✓<").contains(">2<").contains(">3<").contains("stepper__item--done").contains("stepper__desc\">마지막<");
        assertThat(tag(html, "stepper__item--now")).contains("aria-current=\"step\"");
        assertThat(count(html, "aria-current")).isEqualTo(1);
    }

    @Test
    @DisplayName("SecretBox 값과 복사 버튼")
    void secretBox() {
        String html = ui("secretBox", "secretBox('pk_live_x_secret', null)", Map.of());
        assertThat(html).contains("class=\"secret\"").contains("class=\"secret__value\">pk_live_x_secret<").contains(">클립보드<").contains("btn btn--sm btn--primary").contains("copyBtn(");
    }

    @Test
    @DisplayName("CopyBtn: 아이콘 버튼은 label 이 aria-label, 일반 버튼은 라벨과 복사됨 전환")
    void copyBtn() {
        String icon = ui("copyBtn", "copyBtn('abc', 'RP ID 복사', true)", Map.of());
        assertThat(tag(icon, "aria-label=\"RP ID 복사\"")).contains("icon-btn icon-btn--sm", "x-on:click=\"copy($event)\"");
        String btn = ui("copyBtn", "copyBtn('abc', null, false)", Map.of());
        assertThat(btn).contains("class=\"btn btn--sm\"").contains("copied ? '복사됨' : label").contains(">복사<");
    }

    // ── Skeleton.test.tsx ────────────────────────────────

    @Test
    @DisplayName("Skeleton: status 역할과 aria-busy, 줄 수")
    void skeleton() {
        String html = ui("skeleton", "skeleton(3, null)", Map.of());
        assertThat(tag(html, "role=\"status\"")).contains("aria-busy=\"true\"", "aria-label=\"불러오는 중\"");
        assertThat(count(html, "skeleton-block__line")).isEqualTo(3);
        assertThat(html).contains("아직 불러오는 중입니다…").contains("x-show=\"slow\"");
        assertThat(count(ui("skeleton", "skeleton(null, '표')", Map.of()), "skeleton-block__line")).isEqualTo(4);
    }

    // ── LoadError.test.tsx ───────────────────────────────

    @Test
    @DisplayName("LoadError: role=alert, 조사, 오류 문구, 다시 시도·복사, HTTP 상태")
    void loadError() {
        Throwable error = new ResponseStatusException(org.springframework.http.HttpStatusCode.valueOf(503), "x");
        String html = ui("loadError", "loadError('목록', ${e}, ${ {'hx-get': '/retry'} }, false, false)", m("e", error));
        assertThat(tag(html, "role=\"alert\"")).contains("class=\"empty\"");
        assertThat(html).contains("목록을 불러오지 못했습니다").contains("서버가 잠시 응답하지 않습니다. 저장된 데이터는 그대로 있습니다. 잠시 후 다시 시도하세요.")
                .contains("btn btn--primary").contains("hx-get=\"/retry\"").contains(">다시 시도<").contains("btn btn--ghost").contains("HTTP 503").contains("load-error.webp");
        String josa = ui("loadError", "loadError('정보', ${e}, null, true, true)", m("e", error));
        assertThat(josa).contains("정보를 불러오지 못했습니다").contains("다시 시도 중…").contains("empty empty--compact");
        assertThat(tag(josa, "btn btn--primary")).contains("disabled");
    }

    // ── OtpInput.test.tsx ────────────────────────────────

    @Test
    @DisplayName("OtpInput: 6칸, 접근 가능한 이름, 첫 칸 one-time-code")
    void otpInput() {
        String html = ui("otpInput", "otpInput('code', null, null, true, false)", Map.of());
        assertThat(count(html, "class=\"otp__cell\"")).isEqualTo(6);
        assertThat(html).contains("aria-label=\"인증 코드 1번째 자리\"").contains("aria-label=\"인증 코드 6번째 자리\"").contains("<legend class=\"sr-only\">인증 코드 6자리</legend>")
                .contains("name=\"code\"").contains("autocomplete=\"one-time-code\"");
        assertThat(count(html, "autocomplete=\"off\"")).isEqualTo(5);
        assertThat(count(html, " autofocus=")).isEqualTo(1);
        assertThat(html).contains("x-on:input=\"onInput(0, $event)\"").contains("x-on:paste=\"onPaste($event)\"").contains("x-on:keydown=\"onKeydown(5, $event)\"");
        assertThat(count(ui("otpInput", "otpInput('c', 8, '복구 코드', false, true)", Map.of()), "otp__cell\"")).isEqualTo(8);
    }

    // ── unsaved.test.tsx / ToastHost.test.tsx ────────────

    @Test
    @DisplayName("SaveBar: region, 저장·되돌리기, 변경 개수")
    void saveBar() {
        String html = ui("saveBar", "saveBar('policy-form', '요약', null, null)", Map.of());
        assertThat(tag(html, "role=\"region\"")).contains("aria-label=\"저장되지 않은 변경\"", "class=\"savebar\"", "x-show=\"dirty\"");
        assertThat(html).contains("저장하지 않은 변경 <span x-text=\"count\">0</span>건").contains(">되돌리기<").contains("btn btn--primary").contains(">저장<").contains("&quot;form&quot;:&quot;policy-form&quot;")
                .contains("x-on:click=\"revert()\"").contains("x-on:click=\"save()\"").contains(">요약<");
    }

    @Test
    @DisplayName("UnsavedGuard: 이동 확인은 alertdialog, 기본 포커스는 머무르기")
    void unsavedGuard() {
        String html = ui("unsavedGuard", "unsavedGuard(null, null)", Map.of());
        assertThat(tag(html, "role=\"alertdialog\"")).contains("aria-modal=\"true\"", "aria-labelledby=\"unsaved-guard-title\"", "x-trap.noscroll=\"open\"");
        assertThat(html).contains(">저장하지 않은 변경이 있습니다<").contains(">이동하면 변경 사항이 사라집니다.<").contains("autofocus").contains(">머무르기<").contains("btn btn--danger-solid").contains(">이동<");
    }

    @Test
    @DisplayName("ToastHost: 서버 toast 이벤트를 받는 rack, 오류는 alert 로 남는다")
    void toastHost() {
        String html = ui("toastHost", "toastHost", Map.of());
        assertThat(tag(html, "class=\"toast-rack\"")).contains("x-on:toast.window=\"push($event.detail)\"", "x-data=\"toastHost\"");
        assertThat(html).contains("x-bind:role=\"t.kind === 'err' ? 'alert' : 'status'\"").contains("toast__icon").contains("<summary>자세히</summary>").contains("aria-label=\"닫기\"");
    }

    // ── 공통 ─────────────────────────────────────────────

    @Test
    @DisplayName("외부 URL·xmlns 가 렌더 결과에 없다")
    void noExternalUrls() {
        String html = ui("dialog", "dialog('d', 't', 's', null, null, null, null, null)", Map.of()) + ui("toastHost", "toastHost", Map.of())
                + ui("menu", "menu('m', ${i}, null, null, null)", m("i", List.of(m("label", "a", "icon", "copy"))));
        assertThat(html).doesNotContain("http://").doesNotContain("https://").doesNotContain("xmlns");
    }

    /** 호스트 템플릿 문자열(호출 쪽 요소를 함께 가지는 경우)을 그대로 렌더링한다. */
    private static String engineHost(String hostHtml) {
        return engineHost(hostHtml, Map.of());
    }

    private static String engineHost(String hostHtml, Map<String, Object> vars) {
        MockServletContext sc = new MockServletContext();
        WebContext ctx = new WebContext(JakartaServletWebApplication.buildApplication(sc).buildExchange(new MockHttpServletRequest(sc), new MockHttpServletResponse()),
                Locale.KOREAN, new java.util.HashMap<>(vars));
        ctx.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, new ThymeleafEvaluationContext(appCtx, null));
        return engine.process("<div>" + hostHtml + "</div>", ctx).replaceAll("\\s+", " ").trim();
    }
}
