package com.crosscert.passkey.admin.contract;

import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 렌더 HTML 스냅샷 커버리지 (Kotlin 전환 안전망, 동결) — {@code src/test/resources/contract/html/**}.
 *
 * <p>테스트: {@link RenderedHtmlTenantContractTest}, {@link RenderedHtmlSettingsContractTest},
 * {@link RenderedHtmlPlatformContractTest}, {@link RenderedHtmlShellAuthContractTest} — 공통 구성 {@link HtmlContractSupport},
 * 정규화 {@link HtmlSnapshot}(단위 테스트 {@link HtmlSnapshotTest}). 스냅샷 = 상태 코드·Content-Type·{@code HX-*}·{@code Location}
 * 헤더 + 정규화한 본문 전체.
 *
 * <p>열 설명: 전체 = 레이아웃 포함 전체 페이지(GET), 조각 = {@code HX-Request: true} 부분 응답, 폼오류 = 검증 실패 응답(422/400·
 * confirmError·필드 OOB), 역할 = PLATFORM_OPERATOR·RP_ADMIN 둘 다(리다이렉트·403 포함), 불리언 = 같은 화면에서 뷰 모델/DTO 불리언이
 * true·false 둘 다 그려지는 데이터(스텁으로 일부러 만든 것만 적는다 — 그 밖의 불리언은 {@link Fill} 기본값 true 쪽만).
 * 탭 컨트롤러의 "전체" 는 테넌트 상세 페이지를 그 탭 주소({@code ?tab=…})로 연 것이다(탭 본문은 페이지가 조각으로 불러온다).
 *
 * <table>
 *   <caption>컨트롤러 × 스냅샷</caption>
 *   <tr><th>컨트롤러</th><th>수</th><th>전체</th><th>조각</th><th>폼오류</th><th>역할</th><th>불리언 true+false</th></tr>
 *   <tr><td>TenantsPageController</td><td>7</td><td>2 (+새 tenant 다이얼로그) + RP 302</td><td>2 (HX-Push-Url)</td><td>1 (422)</td><td>O</td><td>-</td></tr>
 *   <tr><td>TenantDetailPageController</td><td>11</td><td>5 (개요 P·RP·정지·모르는 탭·404)</td><td>6 (개요 탭·체인 2·정지·해제·404)</td><td>-</td><td>O</td><td>ChainView.intact, 상태 ACTIVE/SUSPENDED</td></tr>
 *   <tr><td>ApiKeysTabController</td><td>6</td><td>상세 ?tab=apikeys</td><td>6 (탭 P·RP·발급·재발급·회수)</td><td>1 (422)</td><td>O</td><td>ApiKeyView 활성/회수</td></tr>
 *   <tr><td>AaguidTabController</td><td>8 + 전체 1</td><td>상세 ?tab=aaguid</td><td>8</td><td>2 (미리보기 422·저장 400)</td><td>O</td><td>mdsStrict</td></tr>
 *   <tr><td>WebauthnTabController</td><td>9 + 전체 1</td><td>상세 ?tab=webauthn</td><td>9</td><td>2 (비교 422·저장 400)</td><td>-</td><td>DiffRow.array, requireUserVerification·mdsRequired(alpha/beta)</td></tr>
 *   <tr><td>AuditTabController</td><td>6 + 전체 2</td><td>상세 ?tab=audit (P·RP)</td><td>6</td><td>-</td><td>O</td><td>ChainResult.intact</td></tr>
 *   <tr><td>CredentialsTabController</td><td>7 + 전체 1</td><td>상세 ?tab=credentials</td><td>7 (+404)</td><td>1 (확인 없는 회수 400)</td><td>-</td><td>Row.authenticatorNamed, 인증 이벤트 SUCCESS/FAILED</td></tr>
 *   <tr><td>FunnelTabController</td><td>3 + 전체 1</td><td>상세 ?tab=funnel</td><td>3</td><td>-</td><td>O</td><td>AdoptionDto.Month.current</td></tr>
 *   <tr><td>SettingsPageController</td><td>4 + 탭 주소 전체 3</td><td>3 + ?tab=mds·security·system 3</td><td>1</td><td>-</td><td>O</td><td>CurrentAdmin.platform</td></tr>
 *   <tr><td>AccountTabController</td><td>7</td><td>설정 페이지(계정 탭 즉시)</td><td>7</td><td>2 (확인·해제 422)</td><td>O</td><td>mfaEnabled</td></tr>
 *   <tr><td>AdminUsersTabController</td><td>10</td><td>설정 ?tab=admins</td><td>10 (+409·RP 403)</td><td>2 (역할·tenant 422)</td><td>O</td><td>OpRow.platform·mfa, ACTIVE/SUSPENDED</td></tr>
 *   <tr><td>MdsTabController</td><td>5</td><td>설정 ?tab=mds</td><td>5</td><td>-</td><td>O (RP 403)</td><td>Run.failed, strip true/false/null, 동기화 이력 있음/없음</td></tr>
 *   <tr><td>SecurityPolicyTabController</td><td>7</td><td>설정 ?tab=security</td><td>7</td><td>2 (422·잠금 확인 다이얼로그)</td><td>O (RP 403)</td><td>Form.mfa(mfaRequired)</td></tr>
 *   <tr><td>SystemInfoTabController</td><td>2</td><td>설정 ?tab=system</td><td>2</td><td>-</td><td>O (RP 403)</td><td>-</td></tr>
 *   <tr><td>LicensePageController</td><td>5</td><td>3 (+RP 302)</td><td>2 (재확인 성공·실패)</td><td>-</td><td>O</td><td>View.onprem</td></tr>
 *   <tr><td>InsightsPageController</td><td>4</td><td>1 + RP 302</td><td>2 (+RP 403)</td><td>-</td><td>O</td><td>riskWarn(위험 0/12)</td></tr>
 *   <tr><td>ActivityPageController</td><td>9</td><td>2 + RP 302</td><td>6 (+잘못된 ID 422·RP 403)</td><td>-</td><td>O</td><td>필터 selected</td></tr>
 *   <tr><td>AuditChainPageController</td><td>12</td><td>1 + RP 302</td><td>10 (+400·409)</td><td>1 (해결 메모 422)</td><td>O</td><td>Row.intact, Incident.open</td></tr>
 *   <tr><td>ShellController</td><td>7</td><td>-</td><td>7 (+비 htmx 302)</td><td>-</td><td>O</td><td>SwitcherItem.active, showLink</td></tr>
 *   <tr><td>CommandPaletteController</td><td>4</td><td>-</td><td>4</td><td>-</td><td>O</td><td>-</td></tr>
 *   <tr><td>LoginPageController</td><td>8</td><td>4 (로그인·오류·MFA·인증 후 302)</td><td>4</td><td>2 (TOTP·복구 코드 422)</td><td>-</td><td>loginError</td></tr>
 *   <tr><td>PasswordPagesController</td><td>10</td><td>4 (+링크 302)</td><td>6</td><td>3</td><td>-</td><td>sent</td></tr>
 *   <tr><td>SignupPageController</td><td>3</td><td>1</td><td>2</td><td>1</td><td>-</td><td>-</td></tr>
 *   <tr><td>HomeRedirectController</td><td>4</td><td>4 (302)</td><td>-</td><td>-</td><td>O (+익명)</td><td>-</td></tr>
 *   <tr><td>ScreenErrorViewResolver</td><td>4</td><td>3 (404 P·RP, 500 디스패치)</td><td>1 (404)</td><td>-</td><td>O</td><td>-</td></tr>
 * </table>
 *
 * <p>합계 171 스냅샷(설정 탭 주소 전체 3 포함)(테넌트 상세 17 = 페이지 5 + 탭 주소 전체 6 + 조각 6). {@code ProfileView.local}(로그인 화면 데모 계정)은
 * 슬라이스의 실제 {@code ProfileController}(활성 프로필) 값 하나만 그려진다(false) — 기존 {@code LoginPageControllerTest} 가
 * true 쪽을 단언한다.
 *
 * <p>뮤테이션 확인(Task 2): {@code TenantsPageController.TenantRow} 의 {@code rpId} 컴포넌트를 {@code rpHost} 로 바꾸면(템플릿
 * {@code list-rows.html} 의 {@code ${t.rpId}}) {@link RenderedHtmlTenantContractTest} 가 실패하는 것을 확인하고 원복했다.
 *
 * <p>아래 테스트는 (1) {@code admin.web} 의 화면 컨트롤러가 전부 스냅샷 슬라이스에 올라 있는지, (2) 컨트롤러마다(그리고
 * {@code ScreenErrorViewResolver}) 기록된 스냅샷 파일이 있는지({@link #SNAPSHOT_PREFIX} 의 경로 접두사로) 지킨다.
 */
class RenderedHtmlContractCoverage {

    /**
     * 컨트롤러 단순 이름 → 그 컨트롤러가 그린 스냅샷의 경로 접두사({@code contract/html/} 아래). 탭 컨트롤러의 전체 페이지는
     * 상세·설정 페이지 스냅샷이라 여기서는 조각 접두사만 본다.
     */
    static final Map<String, List<String>> SNAPSHOT_PREFIX = Map.ofEntries(
            Map.entry("TenantsPageController", List.of("tenant/tenants.")),
            Map.entry("TenantDetailPageController", List.of("tenant/detail.")),
            Map.entry("ApiKeysTabController", List.of("tenant/apikeys.")),
            Map.entry("AaguidTabController", List.of("tenant/aaguid.")),
            Map.entry("WebauthnTabController", List.of("tenant/webauthn.")),
            Map.entry("AuditTabController", List.of("tenant/audit.")),
            Map.entry("CredentialsTabController", List.of("tenant/credentials.")),
            Map.entry("FunnelTabController", List.of("tenant/funnel.")),
            Map.entry("SettingsPageController", List.of("settings/page.", "settings/tab.")),
            Map.entry("AccountTabController", List.of("settings/account.")),
            Map.entry("AdminUsersTabController", List.of("settings/admins.")),
            Map.entry("MdsTabController", List.of("settings/mds.")),
            Map.entry("SecurityPolicyTabController", List.of("settings/security.")),
            Map.entry("SystemInfoTabController", List.of("settings/system.")),
            Map.entry("LicensePageController", List.of("platform/license.")),
            Map.entry("InsightsPageController", List.of("platform/insights.")),
            Map.entry("ActivityPageController", List.of("platform/activity.")),
            Map.entry("AuditChainPageController", List.of("platform/audit-chain.")),
            Map.entry("ShellController", List.of("shell/license-banner.", "shell/chain-card.", "shell/tenant-switcher.", "shell/active-tenant.")),
            Map.entry("CommandPaletteController", List.of("shell/palette.")),
            Map.entry("LoginPageController", List.of("auth/login.", "auth/mfa.")),
            Map.entry("PasswordPagesController", List.of("auth/forgot.", "auth/reset.", "auth/reset-link.")),
            Map.entry("SignupPageController", List.of("auth/signup.")),
            Map.entry("HomeRedirectController", List.of("shell/home.")),
            Map.entry("ScreenErrorViewResolver", List.of("error/not-found.", "error/dispatch-500.")));

    @Test
    void everyScreenControllerIsInTheSnapshotSlice() {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        Set<String> screens = scan.findCandidateComponents("com.crosscert.passkey.admin.web").stream()
                .map(BeanDefinition::getBeanClassName)
                .filter(n -> !n.contains("Test")) // 테스트 전용 더미 컨트롤러 제외
                .collect(Collectors.toCollection(TreeSet::new));
        AdminWebSliceTest slice = HtmlContractSupport.HtmlContractSlice.class.getAnnotation(AdminWebSliceTest.class);
        Set<String> covered = Arrays.stream(slice.controllers()).map(Class::getName).collect(Collectors.toCollection(TreeSet::new));
        // 24 = Task 2 시점 admin.web 의 @Controller 전수(화면 23 + HomeRedirect). 화면 컨트롤러가 늘거나 줄면 이 수와
        // HtmlContractSlice·SNAPSHOT_PREFIX·Javadoc 표를 함께 고친다(스캔이 0개를 돌려주는 설정 오류도 여기서 잡힌다).
        assertThat(screens).hasSize(24);
        assertThat(covered).containsAll(screens);
        Set<String> simple = screens.stream().map(n -> n.substring(n.lastIndexOf('.') + 1)).collect(Collectors.toSet());
        assertThat(SNAPSHOT_PREFIX.keySet()).containsAll(simple);
    }

    @Test
    void everyControllerHasRecordedSnapshots() throws IOException {
        Path root = Path.of("src/test/resources/contract/html");
        List<String> files;
        try (Stream<Path> s = Files.walk(root)) {
            files = s.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/')).toList();
        }
        SNAPSHOT_PREFIX.forEach((controller, prefixes) -> {
            for (String prefix : prefixes) {
                assertThat(files).as(controller + " 스냅샷 " + prefix + "*").anyMatch(f -> f.startsWith(prefix));
            }
        });
    }
}
