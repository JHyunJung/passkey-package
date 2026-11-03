# admin 화면 Thymeleaf 전환 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `admin-ui`(React)의 모든 화면을 `admin-app` 안의 Thymeleaf + htmx + Alpine.js 서버 렌더링 화면으로 바꾸고, Node 빌드와 `admin-ui`를 제거한다.

**Architecture:** 새 패키지 `com.crosscert.passkey.admin.web`의 `@Controller`가 Thymeleaf 뷰와 조각을 반환한다. 데이터와 변경 작업은 **기존 `@RestController` 빈을 Java 메서드로 직접 호출**해서 얻는다(HTTP 왕복 없음). 그래서 컨트롤러 안의 로직과 `@PreAuthorize`가 그대로 적용되고, JSON API는 한 줄도 바뀌지 않는다. 서버 왕복은 htmx가, 브라우저 안 상태(다이얼로그, 단축키, 카운트다운)는 Alpine.js가 맡는다.

**Tech Stack:** Java 17, Spring Boot 3.5.14, Spring Security 6.5, `spring-boot-starter-thymeleaf`, `thymeleaf-extras-springsecurity6`, htmx 2.0.4, Alpine.js 3.14.8(+ `@alpinejs/focus` 3.14.8), ZXing core 3.5.3(QR), JUnit 5 / MockMvc.

**Spec:** `docs/superpowers/specs/2026-11-03-admin-thymeleaf-design.md`

## Global Constraints

- Java 17, Spring Boot 3.5.14를 유지한다. 루트 `build.gradle.kts`와 `gradle/libs.versions.toml`은 바꾸지 않는다.
- `/admin/api/**` 컨트롤러, DTO, 동작을 그대로 둔다. **기존 테스트 파일(`admin-app/src/test/**`)은 한 줄도 수정하지 않는다.** 새 테스트만 추가한다.
- `core`, `webauthn`, `passkey-app`, `rp-app`, `sdk-java`는 수정하지 않는다. `LicenseGuardFilter`도 포함한다.
- 외부 URL 참조는 0건이어야 한다. CDN, Google Fonts, WebJars를 쓰지 않는다. JS는 `static/admin/js/vendor/`에 버전을 고정한 파일로 넣고, 출처와 버전은 같은 폴더의 `README.md`에 적는다.
- 화면 URL은 지금과 같다: `/admin/login`, `/admin/tenants`, `/admin/tenants/{id}?tab=…`, `/admin/activity`, `/admin/audit-chain`, `/admin/settings?tab=…`, `/admin/license`, `/admin/forgot-password`, `/admin/reset-password`, `/admin/signup`. 옛 URL `/admin/api-keys`, `/admin/audit`, `/admin/mds`, `/admin/keys`와 `/admin`, `/`는 React의 `*` 경로와 똑같이 리다이렉트한다.
- 디자인 시스템: `admin-ui/src/styles/{tokens,components,globals}.css`의 기존 값은 바꾸지 않는다. 클래스 추가만 허용하고, 추가한 클래스는 `components.css` 끝의 `/* ── thymeleaf port ── */` 구역에 둔다.
- 각 화면의 **정답지**는 `admin-ui/src`의 해당 TSX와 `*.test.ts(x)`다. 문구, 라벨, 순서, 권한별 노출, 확인 절차는 원본과 같아야 한다.
- 평문 복구 코드는 서버 세션에 저장하지 않는다. 복구 코드 `.txt` 다운로드는 브라우저에서 Blob으로 만든다.
- 커밋 메시지는 저장소 관례(`feat(admin-web): …`, 한국어 본문)를 따르고, 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`를 붙인다.

## Review Focus

1. **RP_ADMIN이 URL로 다른 테넌트에 접근하는 경우** (`/admin/tenants/{남의 id}?tab=api-keys`): 데이터가 노출되지 않아야 하고, React와 같은 처리(자기 테넌트로 이동 또는 오류 화면)가 나와야 한다. → Task 2, Task 9에 테스트를 둔다.
2. **세션이 만료된 상태에서 보낸 htmx 요청** (폴링, 탭 전환): 조각 자리에 로그인 화면 전체가 끼워지면 안 된다. `HX-Redirect: /admin/login`으로 전체 이동해야 한다. → Task 2에 테스트를 둔다.
3. **MFA 대기 세션이 화면이나 정적 파일을 요청하는 경우**: 화면 요청은 `/admin/login`(MFA 단계)으로 리다이렉트하고, CSS·JS·폰트는 허용해야 한다(그래야 MFA 화면이 깨지지 않는다). JSON API는 지금처럼 403 `mfa_required`. → Task 2에 테스트를 둔다.
4. **저장하지 않은 변경이 있는 상태에서 `hx-boost` 링크로 이동하는 경우**: `beforeunload`만으로는 막히지 않는다. `htmx:beforeRequest`에서도 확인창을 띄워야 한다. → Task 5에 테스트를 둔다.
5. **사용자 입력에 HTML/스크립트가 들어간 경우** (테넌트 이름, RP 이름, 감사 로그 detail): `th:utext`를 쓰지 않고, Alpine `x-html`에 사용자 값을 넣지 않는다. → Task 1의 정적 스캔 테스트에 `th:utext`/`x-html` 금지 검사를 넣는다.

---

## File Structure

```
admin-app/
  build.gradle.kts                          (modify: Task 1 thymeleaf/zxing 추가, Task 17 node 제거)
  Dockerfile                                (modify: Task 17)
  src/main/java/com/crosscert/passkey/admin/
    config/AdminSecurityConfig.java         (modify: Task 2)
    config/AdminWebMvcConfig.java           (modify: Task 2 SPA forward → redirect만 남김)
    auth/MfaPendingFilter.java              (modify: Task 2, 기존 동작에 추가만)
    web/                                    (create, 화면 계층 전체)
      support/   ViewModelAdvice, WebExceptionAdvice, Htmx, Toasts, CurrentAdmin
      format/    Labels, Josa, KstFormat, ApiKeyExpiry, RecoveryCodeFilename, CsvWriter, …
      auth/      LoginPageController, PasswordPagesController, SignupPageController
      tenant/    TenantsPageController, TenantDetailPageController, tenant 탭별 *TabController
      activity/  ActivityPageController
      audit/     AuditChainPageController
      settings/  SettingsPageController, settings 탭별 *TabController
      license/   LicensePageController
      shell/     CommandPaletteController
  src/main/resources/
    templates/admin/
      layout.html                           (Task 4) 공통 틀
      fragments/ui/*.html                   (Task 3) 공통 UI 조각
      fragments/icons.html                  (Task 3)
      fragments/shell/*.html                (Task 4, 5)
      auth/*.html  tenants/*.html  tenant/*.html  activity/*.html
      audit-chain/*.html  settings/*.html  license/*.html
    static/admin/
      css/tokens.css components.css globals.css fonts.css
      fonts/*.woff2
      js/vendor/htmx.min.js alpine.min.js alpine-focus.min.js README.md
      js/app.js                             (Task 4, 5)
      img/ crosscert-logo-transparent.png favicon.png illustrations/*.webp
  src/test/java/com/crosscert/passkey/admin/web/
    AdminWebSliceTest.java                  (Task 2) @WebMvcTest 공통 메타 애너테이션 + 스텁
    OfflineAssetScanTest.java               (Task 1)
    …화면별 *PageControllerTest.java
docs/superpowers/parity/2026-11-03-admin-thymeleaf-parity.md   (Task 3에서 만들고 화면 task마다 갱신)
```

경계 원칙:
- 화면 컨트롤러는 **REST 컨트롤러 빈 → 뷰 모델** 변환과 템플릿 선택만 한다. 비즈니스 규칙을 새로 쓰지 않는다.
- REST 컨트롤러가 `ApiResponse<T>`를 반환하면 `.data()`를 꺼내 쓰고, `ResponseEntity<?>`를 반환하면 상태 코드와 본문(Map)을 해석한다.
- REST 컨트롤러 안의 `@ExceptionHandler`는 직접 호출에는 적용되지 않는다. 예외는 `WebExceptionAdvice`(Task 2)가 화면용 응답으로 바꾼다.

## 공통 화면 작업 절차 (Task 6~16이 따른다)

화면 task마다 아래 단계를 따른다. 각 task에는 그 화면에 고유한 결정만 적는다.

1. **원본 읽기:** "원본" 목록의 TSX와 테스트를 읽고, 기능 체크리스트를 parity 문서의 해당 절에 적는다. 테스트 케이스 하나가 체크 항목 하나다.
2. **실패하는 테스트 작성:** `XxxPageControllerTest`(`@AdminWebSliceTest`)에 아래를 넣는다.
   - 대표 데이터로 렌더링했을 때 핵심 문구와 요소가 있는지 (`content().string(containsString(...))`, 또는 Jsoup으로 셀렉터 검사)
   - 권한별 노출 차이
   - htmx 조각 요청(`HX-Request: true`)이면 레이아웃 없이 조각만 반환하는지
   - 변경 요청이면 `HX-Trigger` 토스트 헤더와 REST 빈 호출 인자
3. **실행해서 실패 확인:** `./gradlew :admin-app:test --tests '*XxxPageControllerTest'` → FAIL.
4. **구현:** 컨트롤러 + 템플릿 + (필요하면) Alpine 컴포넌트.
5. **실행해서 통과 확인:** 위 명령 → PASS.
6. **동등성 확인:** `./gradlew :admin-app:bootRun --args='--spring.profiles.active=local'`과 `cd admin-ui && npm run dev`(5173, `/admin/api` 프록시)를 동시에 띄운다. 같은 계정으로 두 화면을 열어 체크리스트를 하나씩 확인하고, 1280px 폭 스크린샷을 비교한다. 결과(✅ / 차이와 사유)를 parity 문서에 적는다.
7. **커밋.**

---

### Task 1: 빌드 의존성, 정적 에셋, 폐쇄망 스캔 테스트

**Files:**
- Modify: `admin-app/build.gradle.kts`
- Create: `admin-app/src/main/resources/static/admin/css/{tokens,components,globals,fonts}.css`, `static/admin/fonts/*.woff2`, `static/admin/js/vendor/{htmx.min.js,alpine.min.js,alpine-focus.min.js,README.md}`, `static/admin/img/**`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/web/OfflineAssetScanTest.java`

**Interfaces:**
- Produces: 정적 경로 `/admin/css/*.css`, `/admin/fonts/*`, `/admin/js/vendor/*`, `/admin/img/*`. 이후 모든 템플릿은 `th:href="@{/admin/css/tokens.css}"` 형태로 참조한다.

- [ ] **Step 1: `OfflineAssetScanTest` 작성.** `src/main/resources/templates`와 `static`(vendor 폴더 제외)의 모든 `.html/.css/.js` 파일을 읽어 다음을 검사한다.
  - `assertNoExternalUrl`: 정규식 `(?i)(src|href|url\()\s*=?\s*["']?(https?:)?//`에 걸리는 곳이 0건. 단, `xmlns`와 `http://www.thymeleaf.org`는 예외.
  - `assertNoUnsafeHtml`: `th:utext`와 `x-html` 등장 0건.
  - `assertVendorPinned`: `static/admin/js/vendor/README.md`에 `htmx.org@2.0.4`, `alpinejs@3.14.8`, `@alpinejs/focus@3.14.8` 문자열이 있다.
- [ ] **Step 2: 실행해서 실패 확인.** `./gradlew :admin-app:test --tests '*OfflineAssetScanTest'` → FAIL(README 없음).
- [ ] **Step 3: 의존성 추가.** `admin-app/build.gradle.kts`의 `dependencies`에 `spring-boot-starter-thymeleaf`, `org.thymeleaf.extras:thymeleaf-extras-springsecurity6`(BOM 버전), `com.google.zxing:core:3.5.3`, 테스트용 `org.jsoup:jsoup:1.18.1`을 추가한다. node 관련 설정은 Task 17까지 그대로 둔다.
- [ ] **Step 4: 에셋 복사.**
  - CSS 3개는 `admin-ui/src/styles/`에서 그대로 복사한다.
  - `fonts.css`에는 `admin-ui/node_modules/@fontsource/ibm-plex-sans-kr/{400,500,600,700}.css`와 `ibm-plex-mono/{400,500}.css`의 `@font-face`를 옮긴다. `url()`은 `/admin/fonts/<파일명>`으로 바꾸고, 참조된 woff2 파일만 복사한다.
  - vendor JS는 npm 패키지 `htmx.org@2.0.4/dist/htmx.min.js`, `alpinejs@3.14.8/dist/cdn.min.js` → `alpine.min.js`, `@alpinejs/focus@3.14.8/dist/cdn.min.js` → `alpine-focus.min.js`로 복사한다. 각 파일의 sha256을 README에 적는다.
  - 이미지는 `admin-ui/public/*`와 `admin-ui/src/assets/illustrations/*.webp`를 `static/admin/img/`로 복사한다.
- [ ] **Step 5: 실행해서 통과 확인.** 같은 명령 → PASS. `./gradlew :admin-app:bootJar` 성공.
- [ ] **Step 6: 커밋.** `feat(admin-web): Thymeleaf·ZXing 의존성과 로컬 정적 에셋(CSS·폰트·htmx·Alpine), 외부 URL 스캔 테스트`

### Task 2: 화면용 보안 체인, 예외 처리, 테스트 기반

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminWebMvcConfig.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaPendingFilter.java`
- Create: `web/support/Htmx.java`, `web/support/Toasts.java`, `web/support/CurrentAdmin.java`, `web/support/ViewModelAdvice.java`, `web/support/WebExceptionAdvice.java`, `templates/admin/error.html`
- Test: `src/test/java/com/crosscert/passkey/admin/web/AdminWebSliceTest.java`, `web/PageSecurityTest.java`, `web/LoginHandlerBranchTest.java`

**Interfaces:**
- Produces:
  - `Htmx.isHtmx(HttpServletRequest) : boolean` (`HX-Request: true`)
  - `Htmx.redirect(HttpServletResponse, String path)` (htmx면 `HX-Redirect` 헤더 + 200, 아니면 302)
  - `Toasts.success(HttpServletResponse, String msg)`, `Toasts.error(HttpServletResponse, String msg)`: `HX-Trigger: {"toast":{"kind":"success|error","message":"…"}}`. 같은 응답에 여러 번 부르면 마지막 것이 이긴다.
  - `CurrentAdmin`: record `(String email, String role, List<UUID> tenantIds, boolean mfaEnabled, boolean mfaRequired, int sessionIdleTimeoutMinutes, boolean platform)`. `MeController.me(auth, req).data()`로 만든다.
  - `ViewModelAdvice`(`@ControllerAdvice(basePackages="com.crosscert.passkey.admin.web")`): 모든 화면 모델에 `me`(CurrentAdmin, 미인증이면 null)와 `profile`(`ProfileController.profile().data()`)을 넣는다.
  - `@AdminWebSliceTest`: `@WebMvcTest` + 기존 `TenantAdminControllerSecurityTest`와 같은 JPA 스텁과 MockBean 묶음 + `AdminSecurityConfig` import. 메타 애너테이션으로 둔다. 화면 테스트는 이걸 쓰고, REST 컨트롤러 빈은 `@MockBean`으로 둔다.

규칙:
- **URL 권한:**
  - `permitAll`: `/admin/login`(GET/POST), `/admin/logout`, `/admin/forgot-password`, `/admin/reset-password`, `/admin/signup`(GET/POST), `/admin/css/**`, `/admin/js/**`, `/admin/fonts/**`, `/admin/img/**`, `/favicon.ico`
  - `authenticated`: `/`, `/admin`, `/admin/`, `/admin/tenants/**`, `/admin/activity`, `/admin/audit-chain/**`, `/admin/settings/**`, `/admin/license`, `/admin/api-keys`, `/admin/audit`, `/admin/mds`, `/admin/keys`, `/admin/_/**`(htmx 조각 전용 경로 접두사)
  - 기존 `/admin/api/**` 규칙은 그대로 둔다. React용 `/admin/assets/**`, `/admin/index.html` 허용은 Task 17에서 지운다.
- **진입점(`spaEntryPoint`):** `/admin/api/**`이거나 JSON 요청이면 지금처럼 401 JSON. htmx 요청이면 `HX-Redirect: /admin/login` + 401. 그 외에는 **`/admin/login`으로 302**(지금은 `/admin`).
- **로그인 성공·실패 핸들러:** 화면 요청 판별 `isPageLogin(req) = Htmx.isHtmx(req) || req.getParameter("_csrf") != null`.
  - 성공: 화면 요청이면 `mfaRequired ? "/admin/login" : "/admin"`으로 `Htmx.redirect`. 아니면 기존 JSON 그대로.
  - 실패: 화면 요청이면 `Htmx.redirect(res, "/admin/login?error")`. 로그인 화면(Task 6)은 `error` 파라미터가 있으면 `LoginPage.tsx`와 같은 오류 문구를 보여준다. 아니면 기존 JSON 그대로. 필터 단계의 핸들러에서 템플릿을 렌더링하지 않기 위해서다.
  - 감사 로그, 잠금 카운트, 이벤트 발행 순서는 바꾸지 않는다. 응답을 쓰는 마지막 부분만 분기한다.
- **`MfaPendingFilter`:** 기존 허용 목록은 그대로 둔다. 추가 규칙:
  - 대기 중 허용: `GET /admin/login`, `POST /admin/login/mfa`, `/admin/css/**`, `/admin/js/**`, `/admin/fonts/**`, `/admin/img/**`
  - `/admin/api/`로 시작하지 않는 그 밖의 경로는 403 JSON 대신 `/admin/login`으로 리다이렉트(`Htmx.redirect`)
- **`AccessDeniedHandler`:** `/admin/api/**`는 기존 JSON. 화면 경로면 `error.html`을 403으로 렌더링.
- **`WebExceptionAdvice`:** `@ControllerAdvice(basePackages="com.crosscert.passkey.admin.web") @Order(Ordered.HIGHEST_PRECEDENCE)`. `BusinessException`, `IllegalArgumentException`, `AccessDeniedException`, `ResponseStatusException`, `Exception`을 처리한다. htmx 요청이면 `Toasts.error` + 204, 아니면 `error.html`. 문구는 `admin-ui/src/lib/describeError.ts`를 따른다(Task 3의 `ErrorMessages` 사용).
- **`AdminWebMvcConfig`:** forward 줄을 모두 지우고 `/` → `/admin` 리다이렉트만 남긴다. `/admin`과 옛 URL 리다이렉트는 Task 6의 `HomeRedirectController`가 맡는다.

- [ ] **Step 1: 실패하는 테스트 작성** (`PageSecurityTest`, `LoginHandlerBranchTest`)
  - `anonymousPageRedirectsToLogin`: GET `/admin/tenants` → 302 `Location: /admin/login`
  - `anonymousHtmxGetsHxRedirect`: GET `/admin/_/tenants/list` + `HX-Request: true` → 401, 헤더 `HX-Redirect=/admin/login`
  - `anonymousApiStill401Json`: GET `/admin/api/tenants` → 401, body `{"error":"unauthorized"}`
  - `staticAssetsPublic`: GET `/admin/css/tokens.css` → 200
  - `mfaPendingPageRedirects`: 세션 속성 `MFA_PENDING=TRUE` + 인증 → GET `/admin/tenants` → 302 `/admin/login`
  - `mfaPendingAllowsLoginAndAssets`: 위 세션으로 GET `/admin/login` → 200, GET `/admin/js/app.js` → 200
  - `mfaPendingApiStill403Json`: 위 세션으로 GET `/admin/api/tenants` → 403 `{"error":"mfa_required"}`
  - `pagePostWithoutCsrfRejected`: 인증 + POST `/admin/_/tenants` (csrf 없음) → 403
  - `loginJsonUnchanged`: `X-XSRF-TOKEN` 헤더 방식 로그인 성공 → 200 JSON에 `email`, `role`, `mfaRequired`
  - `loginFormRedirects`: `_csrf` 파라미터 방식 로그인 성공 → 302 `/admin`, MFA 사용자면 `/admin/login`
  - `loginHtmxFailureRedirectsWithError`: `HX-Request` + 잘못된 비밀번호 → 헤더 `HX-Redirect=/admin/login?error`. 감사 로그 `ADMIN_LOGIN_FAILED`가 append된다(`verify(audit).append(...)`).
- [ ] **Step 2: 실행해서 실패 확인.** `./gradlew :admin-app:test --tests '*PageSecurityTest' --tests '*LoginHandlerBranchTest'` → FAIL
- [ ] **Step 3: 위 규칙대로 구현한다.**
- [ ] **Step 4: 새 테스트와 기존 전체 테스트 실행.** `./gradlew :admin-app:test` → 전부 PASS. 특히 기존 `MfaPendingFilterTest`, `AdminSecurityConfigCsrfCookieTest`, `*ControllerSecurityTest`가 통과해야 한다. Testcontainers IT는 Docker가 있는 환경에서 `./gradlew :admin-app:test`로 함께 실행된다.
- [ ] **Step 5: 커밋.** `feat(admin-web): 화면 경로 인증·MFA 대기·htmx 미인증 처리와 로그인 응답 분기, 화면 예외 처리`

### Task 3: 화면 표시 로직의 Java 이전과 공통 UI 조각

**Files:**
- Create: `web/format/Labels.java`(← `i18n/labels.ts`), `Josa.java`(← `lib/josa.ts`), `KstFormat.java`(← `lib/kst.ts`, `lib/formatDateTime.ts`), `ApiKeyExpiry.java`(← `lib/apiKeyExpiry.ts`), `ApkKeyHash.java`(← `lib/apkKeyHash.ts`), `PlatformLabels.java`(← `lib/platform.ts`), `PolicyValidation.java`(← `lib/policyValidation.ts`), `ErrorMessages.java`(← `lib/describeError.ts`, `lib/transientError.ts`), `RecoveryCodeFilename.java`(← `lib/recoveryCodeFilename.ts`), `CsvWriter.java`(← `lib/csvExport.ts`), `Search.java`(← `lib/search.ts`)
- Create: `web/format/ViewHelpers.java`. 위 클래스를 묶어 템플릿에서 `${@fmt.status(x)}`, `${@fmt.kst(t)}`, `${@fmt.josa(name,'을/를')}`처럼 쓰는 빈 `@Component("fmt")`
- Create: `templates/admin/fragments/icons.html`(← `icons/Icons.tsx`, 아이콘마다 `th:fragment="name(size)"`), `templates/admin/fragments/ui/*.html`(← `ui/*` 34종)
- Create: `docs/superpowers/parity/2026-11-03-admin-thymeleaf-parity.md`
- Test: `src/test/java/com/crosscert/passkey/admin/web/format/*Test.java`(vitest 케이스 1:1), `web/UiFragmentsRenderTest.java`

**Interfaces:**
- Produces:
  - `@fmt` 빈의 메서드. 화면 task들이 이 이름을 그대로 쓴다: `status(String)`, `aaguidMode(String)`, `kst(OffsetDateTime|Instant)`, `kstDate(...)`, `relative(Instant)`, `josa(String word, String pair)`, `apiKeyExpiry(OffsetDateTime)`, `platform(String)`, `error(Throwable)`
  - `PolicyValidation.validate(SecurityPolicyDto.Update) : Map<String,String>`. 키는 필드명, 값은 원본과 같은 오류 문구다.
  - `CsvWriter.write(List<String> header, List<List<String>> rows) : byte[]`. BOM, 이스케이프, 줄바꿈은 원본 `csvExport.ts`와 같다.
  - `RecoveryCodeFilename.of(String email, LocalDate kstDate) : String`. 브라우저 쪽 Blob 다운로드에서 쓰려고 템플릿에 값으로 내려준다.
  - UI 조각의 시그니처. `th:replace="~{admin/fragments/ui/button :: button(label, variant, attrs)}"` 형태이고, 각 조각 파일 맨 위 주석에 파라미터를 적는다. 상태가 있는 컴포넌트(Dialog, Menu, Tabs, ChipInput, OtpInput, ConfirmByTyping, Switch, Segmented, SaveBar, CopyBtn, SecretBox)는 Alpine 컴포넌트로 만들고 `app.js`의 `Alpine.data('dialog'|'menu'|'chipInput'|'otpInput'|'confirmByTyping'|'saveBar'|'copyBtn', …)`로 등록한다.
- `app.js`는 Task 4가 만든다. 이 task는 `static/admin/js/ui.js`에 Alpine 컴포넌트를 정의하고, Task 4가 이 파일을 로드한다.

- [ ] **Step 1: vitest 케이스를 JUnit으로 옮긴 실패 테스트 작성.** `lib/*.test.ts`, `i18n/labels.test.ts`의 `it(...)` 하나를 `@Test` 하나로 옮기고, 입력과 기대값은 그대로 쓴다. `UiFragmentsRenderTest`는 Thymeleaf `TemplateEngine`으로 각 조각을 렌더링해 원본 컴포넌트와 같은 클래스명과 ARIA 속성이 나오는지 검사한다. 기준은 `ui/*.test.tsx`의 셀렉터다(예: Dialog `role="dialog" aria-modal="true"`, Tabs `role="tablist"`/`role="tab" aria-selected`).
- [ ] **Step 2: 실행해서 실패 확인.** `./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.web.format.*' --tests '*UiFragmentsRenderTest'` → FAIL
- [ ] **Step 3: 구현.** TSX의 인라인 `style={{…}}`는 화면 task들이 공통으로 쓸 만한 것만 `components.css`의 port 구역에 클래스로 추가한다. Dialog의 포커스 트랩은 `x-trap.noscroll`(Alpine focus 플러그인)을 쓰고, `Escape`로 닫기와 닫은 뒤 포커스 복귀는 `useFocusTrap.test.tsx`와 같게 한다.
- [ ] **Step 4: 실행해서 통과 확인.** 같은 명령 → PASS
- [ ] **Step 5: parity 문서 골격 작성.** 화면별 절(§공통 틀, §로그인, …)과 표(체크 항목 | 원본 테스트 | 결과)를 만든다.
- [ ] **Step 6: 커밋.** `feat(admin-web): 표시 로직 Java 이전(라벨·조사·KST·검증·CSV)과 공통 UI 조각·아이콘`

### Task 4: 공통 틀(레이아웃, 사이드바, 헤더, 브레드크럼, 라이선스 배너, 토스트)

**Files:**
- Create: `templates/admin/layout.html`, `templates/admin/fragments/shell/{sidebar,header,breadcrumb,license-banner,toast-host,tenant-switcher}.html`, `static/admin/js/app.js`
- Create: `web/shell/ShellController.java`. 조각 엔드포인트 `GET /admin/_/shell/license-banner`(폴링), `POST /admin/_/active-tenant`(→ `ActiveTenantController.switchTenant`)
- Test: `web/shell/ShellControllerTest.java`

**Interfaces:**
- Consumes: Task 2 `CurrentAdmin`, `ViewModelAdvice`, `Htmx`, `Toasts`. Task 3 `@fmt`, 아이콘·UI 조각, `ui.js`.
- Produces:
  - 페이지 템플릿 규약: `<html th:replace="~{admin/layout :: page(title=~{::title}, crumbs=~{::crumbs}, content=~{::main})}">`. `layout.html`은 `<head>`에 `fonts.css`, `tokens.css`, `globals.css`, `components.css`, `htmx.min.js`, `alpine-focus.min.js`, `ui.js`, `app.js`, `alpine.min.js`(defer, 마지막) 순으로 넣는다.
  - `<body hx-boost="true" hx-headers='{"X-XSRF-TOKEN": "…"}'>`. 토큰은 `${_csrf.token}`이다.
  - `app.js`는 `htmx:configRequest`에서 쿠키 `XSRF-TOKEN`을 다시 읽어 헤더를 갱신한다(로그인 직후 토큰 교체 대응). 또 `toast` 이벤트를 받아 `toast-host`(Alpine store `toasts`)에 넣는다.
  - `htmx.config`: `responseHandling`을 설정해 4xx/5xx 응답도 `HX-Trigger`를 처리하고, 422 응답은 swap한다(폼 검증 오류 조각용).

동작 기준(원본 `shell/Sidebar.tsx`, `Header.tsx`, `Breadcrumb.tsx`, `useCrumbTenant.ts`, `components/LicenseBanner.tsx`, `App.tsx`의 레이아웃 부분과 각 테스트):
- 사이드바 메뉴의 노출은 역할(`me.platform`)에 따르고, 현재 경로를 강조한다. 세션 남은 시간 표시(`Sidebar.timeout.test.tsx`)는 Alpine으로 1초마다 갱신한다.
- 테넌트 스위처(다중 테넌트 RP_ADMIN)는 POST 후 선택한 테넌트 상세로 이동한다.
- 라이선스 배너는 원본과 같은 주기로 `hx-trigger="load, every <원본 주기>"` 폴링한다.

- [ ] **Step 1: 실패하는 테스트 작성.** `ShellControllerTest`(`@AdminWebSliceTest`, 테스트용 더미 페이지 컨트롤러 하나 포함):
  - PLATFORM이면 사이드바에 tenants·activity·audit-chain·license 링크가 있다.
  - RP_ADMIN이면 그 링크들이 없다.
  - `<body>`에 `hx-boost="true"`가 있다.
  - head 스크립트 순서가 위 규약과 같다.
  - `POST /admin/_/active-tenant`는 `ActiveTenantController.switchTenant`를 호출하고 `HX-Redirect`를 반환한다.
  - 라이선스 배너 조각은 상태(WARNING/NETWORK_GRACE)에 따라 원본 문구를 보여준다.
- [ ] **Step 2: 실행해서 실패 확인.** `./gradlew :admin-app:test --tests '*ShellControllerTest'` → FAIL
- [ ] **Step 3: 구현.**
- [ ] **Step 4: 실행해서 통과 확인.** → PASS
- [ ] **Step 5: 동등성 확인**(공통 절차 6). parity §공통 틀을 채운다.
- [ ] **Step 6: 커밋.** `feat(admin-web): 공통 레이아웃·사이드바·헤더·브레드크럼·라이선스 배너·토스트`

### Task 5: 명령 팔레트, 단축키, 유휴 세션 모달, 저장 안 한 변경 경고

**Files:**
- Create: `templates/admin/fragments/shell/{command-palette,shortcut-sheet,idle-session-modal}.html`, `web/shell/CommandPaletteController.java`(`GET /admin/_/palette?q=`)
- Modify: `static/admin/js/app.js`
- Test: `web/shell/CommandPaletteControllerTest.java`, `web/shell/AppJsContractTest.java`

**Interfaces:**
- Consumes: Task 4 layout, Task 3 `Search`.
- Produces: `app.js`의 전역 Alpine store `Alpine.store('dirty', {count:0, mark(), clear()})`. 화면 폼은 `x-on:input="$store.dirty.mark()"`를 쓰고, 저장에 성공하면 `HX-Trigger: saved`로 `clear()`한다.

동작 기준(원본 `extras/*`와 테스트, `ui/useUnsavedGuard.tsx`, `unsaved.test.tsx`):
- **단축키:** Cmd/Ctrl+K는 팔레트, `?`는 단축키 안내, `g` 다음 키는 이동이다. 키 매핑은 `useGoShortcuts.ts`와 같다. 입력란에 포커스가 있으면 무시하는 규칙도 원본과 같다.
- **명령 팔레트:** 검색어를 입력하면 `hx-get`(`hx-trigger="input changed delay:150ms"`)으로 결과 조각을 받는다. 화살표 이동, Enter 실행, Esc 닫기를 지원한다. 결과 항목과 정렬은 원본 `CommandPalette.tsx`와 같다(테넌트 검색 포함).
- **유휴 세션 모달:** `me.sessionIdleTimeoutMinutes`를 기준으로 원본과 같은 시점에 경고를 띄우고 카운트다운한다. "연장"을 누르면 `GET /admin/api/me`를 호출해 세션을 갱신하고, 시간이 다 되면 `/admin/logout`으로 보낸다. 사용자 활동과 htmx 요청이 있으면 타이머를 리셋한다.
- **저장 안 한 변경:** `$store.dirty.count > 0`이면 `beforeunload`와 `htmx:beforeRequest`(GET이고 `hx-boost` 이동인 경우)에서 확인창을 띄운다. 취소하면 `evt.preventDefault()`.

- [ ] **Step 1: 실패하는 테스트 작성.**
  - `CommandPaletteControllerTest`: `q=acme`이면 이름에 acme가 들어간 테넌트 결과가 원본 순서로 나오고, RP_ADMIN은 자기 테넌트만 보인다.
  - layout 렌더링에 `command-palette`, `shortcut-sheet`, `idle-session-modal` 조각이 포함된다.
  - `app.js` 정적 검사(`AppJsContractTest`, 파일 문자열 검사): `htmx:beforeRequest` 리스너와 `beforeunload` 리스너가 둘 다 `$store.dirty`/`Alpine.store('dirty')`를 참조한다.
- [ ] **Step 2: 실행해서 실패 확인.** `./gradlew :admin-app:test --tests '*CommandPaletteControllerTest' --tests '*AppJsContractTest'` → FAIL
- [ ] **Step 3: 구현.**
- [ ] **Step 4: 실행해서 통과 확인.** → PASS
- [ ] **Step 5: 동등성 확인**(공통 절차 6). 원본 테스트(`CommandPalette.test.tsx`, `IdleSessionModal.test.tsx`, `ShortcutSheet.test.tsx`, `useGoShortcuts.test.tsx`, `usePaletteShortcut.test.tsx`, `unsaved.test.tsx`)의 케이스를 브라우저에서 하나씩 확인하고 parity에 적는다.
- [ ] **Step 6: 커밋.** `feat(admin-web): 명령 팔레트·단축키·유휴 세션 모달·저장 안 한 변경 경고`

### Task 6: 로그인, MFA 단계, 홈·옛 URL 리다이렉트

**원본:** `pages/LoginPage.tsx`, `MfaChallenge.tsx`, `App.tsx`(로그인 전후 분기, `*` 경로), `me/roles.ts`, `me/RequirePlatform.tsx`와 각 테스트

**Files:**
- Create: `web/auth/LoginPageController.java`, `web/HomeRedirectController.java`, `templates/admin/auth/{login,mfa}.html`
- Test: `web/auth/LoginPageControllerTest.java`, `web/HomeRedirectControllerTest.java`

**Interfaces:**
- `GET /admin/login`:
  - 미인증이면 `auth/login`. 프로필이 `local`이면 원본처럼 테스트 계정을 미리 채운다(`ProfileController.profile()`).
  - 인증됐고 MFA 대기면 `auth/mfa`.
  - 인증 완료면 `/admin`으로 리다이렉트.
- 로그인 폼: `hx-post="/admin/login"`, 필드 `email`, `password`. 성공과 실패 응답은 Task 2의 핸들러가 처리한다.
- `POST /admin/login/mfa`(필드 `code`): `MfaController.verify(new VerifyRequest(code), auth, req)`를 호출한다.
  - 200이면 `Htmx.redirect("/admin")`. `usedRecoveryCode`가 true면 원본처럼 남은 개수 토스트를 띄운다.
  - 401이면 `auth/mfa :: error` 조각 + 422.
  - 세션이 무효화됐으면(잠금) `Htmx.redirect("/admin/login")`.
- `HomeRedirectController`: `/admin`, `/admin/`, `/admin/api-keys`, `/admin/audit`, `/admin/mds`, `/admin/keys`를 처리한다. PLATFORM이면 `/admin/tenants`, 아니면 `/admin/tenants/{tenantIds[0]}`로 보낸다(`App.tsx`의 `*`, `rpTenantId`와 같다).

- [ ] **Step 1~7: 공통 화면 작업 절차.** 테스트에 반드시 포함할 것:
  - `mfaWrongCodeShowsError`
  - `mfaLockoutRedirectsToLogin`(verify가 세션을 무효화한 경우)
  - `rpAdminHomeGoesToOwnTenant`
  - `legacyUrlsRedirect`
  - `localProfilePrefill`
  - `errorParamShowsLoginError`
  - 커밋: `feat(admin-web): 로그인·MFA 단계 화면과 홈·옛 URL 리다이렉트`

### Task 7: 비밀번호 찾기·재설정, 가입 요청 화면

**원본:** `pages/ForgotPasswordPage.tsx`, `ResetPasswordPage.tsx`, `SignupRequestPage.tsx`와 테스트, `api/passwordReset.ts`, `api/signupRequests.ts`

**Files:**
- Create: `web/auth/PasswordPagesController.java`, `web/auth/SignupPageController.java`, `templates/admin/auth/{forgot-password,reset-password,signup}.html`
- Test: `web/auth/PasswordPagesControllerTest.java`, `web/auth/SignupPageControllerTest.java`

**Interfaces:**
- `GET|POST /admin/forgot-password` → `PasswordResetController.request(...)`
- `GET /admin/reset-password?token=` 와 `POST /admin/reset-password` → `PasswordResetController.confirm(...)`
- `GET|POST /admin/signup` → `SignupRequestController.request(...)`. `@Valid`는 직접 호출에서 동작하지 않으므로, 화면 컨트롤러가 `jakarta.validation.Validator`로 `SignupRequestDto.Create`를 검증하고 오류를 필드 옆에 보여준다.
- 화면 POST는 CSRF 토큰을 포함한다. 기존 `/admin/api/**`의 CSRF 면제 범위는 넓히지 않는다.
- 완료 화면(일러스트 `signup-sent.webp` 등), 문구, 존재하지 않는 이메일도 같은 응답을 주는 동작은 원본과 같다.

- [ ] **Step 1~7: 공통 화면 작업 절차.**
  - 테스트 포함: `signupValidationErrorsInline`, `resetWithoutTokenShowsInvalid`, `forgotAlwaysSameMessage`, `postsRequireCsrf`
  - 커밋: `feat(admin-web): 비밀번호 찾기·재설정과 가입 요청 화면`

### Task 8: 테넌트 목록과 생성 다이얼로그

**원본:** `pages/TenantsListPage.tsx`, `NewTenantDialog.test.tsx`, `TenantsListPage.test.tsx`, `api/tenants.ts`, `tenant/tenantSignals.ts`

**Files:**
- Create: `web/tenant/TenantsPageController.java`, `templates/admin/tenants/{list,list-rows,new-dialog}.html`
- Test: `web/tenant/TenantsPageControllerTest.java`

**Interfaces:**
- `GET /admin/tenants`: 전체 페이지. RP_ADMIN이면 `HomeRedirectController`와 같은 규칙으로 리다이렉트한다.
- `GET /admin/_/tenants/list?q=&status=&page=`: 행 조각. 검색·필터·정렬·페이징은 원본과 같고, 현재 원본이 클라이언트에서 처리하는 부분은 서버에서 같은 결과가 나오게 한다. URL은 `hx-push-url`로 갱신한다.
- `POST /admin/_/tenants`: `TenantAdminController.create(...)`. 성공하면 원본과 같이 새 테넌트 상세로 이동하고 토스트를 띄운다. 검증 오류는 다이얼로그 안에 422 조각으로 보여준다.
- 빈 상태(`empty-tenants.webp`)와 검색 결과 없음(`no-results.webp`)을 보여준다.

- [ ] **Step 1~7: 공통 화면 작업 절차.** 커밋: `feat(admin-web): 테넌트 목록·검색·생성 다이얼로그`

### Task 9: 테넌트 상세 틀과 개요 탭

**원본:** `pages/TenantDetailPage.tsx`, `tenant/TenantOverview.tsx`, `KvList.tsx`, `SetRow.tsx`, `recentActivityAdapter.ts`, `tabFormat.ts`, `tenantSignals.ts`와 테스트, `shell/useCrumbTenant.ts`

**Files:**
- Create: `web/tenant/TenantDetailPageController.java`, `templates/admin/tenant/{detail,overview}.html`
- Test: `web/tenant/TenantDetailPageControllerTest.java`

**Interfaces:**
- `GET /admin/tenants/{idOrSlug}?tab=overview|webauthn|aaguid|api-keys|credentials|audit|funnel`: 실제 탭 키는 `TenantDetailPage.tsx`의 값을 그대로 쓴다. 전체 페이지에서 선택된 탭을 서버에서 렌더링한다.
- `GET /admin/_/tenants/{id}/tab/{tab}`: 탭 조각. 탭 링크는 `hx-get` + `hx-push-url="/admin/tenants/{id}?tab=…"`.
- 테넌트는 `TenantAdminController.get(idOrSlug, auth)`로 조회한다. `@PreAuthorize`와 TenantBoundary가 다른 테넌트 접근을 거부하면 `WebExceptionAdvice`가 처리하고, 원본과 같은 결과(오류 화면이나 리다이렉트)를 낸다.
- 정지·활성화(PLATFORM만): `POST /admin/_/tenants/{id}/suspend|activate` → 같은 이름의 REST 메서드. `ConfirmDialog`로 확인한다.
- 이후 탭 task들은 `TenantTabController` 인터페이스 없이, 같은 URL 규약 `GET /admin/_/tenants/{id}/tab/{tab}`을 각자의 컨트롤러에 매핑한다.

- [ ] **Step 1~7: 공통 화면 작업 절차.**
  - 테스트 포함: `rpAdminOtherTenantDenied`(Review Focus 1), `unknownTabFallsBackToOverview`, `suspendHiddenForRpAdmin`
  - 커밋: `feat(admin-web): 테넌트 상세 틀·탭 전환·개요 탭`

### Task 10: WebAuthn 설정 탭과 AAGUID 정책 탭

**원본:** `tenant/WebauthnConfigTab.tsx`(+ `.test.ts`, `.view.test.tsx`), `AaguidPolicyTab.tsx`(+ 테스트), `api/webauthn.ts`, `api/aaguidPolicy.ts`, `ui/SaveBar.tsx`, `ChipInput.tsx`

**Files:**
- Create: `web/tenant/WebauthnTabController.java`, `web/tenant/AaguidTabController.java`, `templates/admin/tenant/{webauthn,webauthn-diff,aaguid}.html`
- Test: `web/tenant/WebauthnTabControllerTest.java`, `web/tenant/AaguidTabControllerTest.java`

**Interfaces:**
- WebAuthn:
  - 미리보기: `POST /admin/_/tenants/{id}/webauthn/diff` → `TenantAdminController.diff(...)`. 변경 비교 조각을 반환한다.
  - 저장: `PUT /admin/_/tenants/{id}/webauthn` → `TenantAdminController.update(...)`.
  - `api/webauthn.ts`의 `adaptConfig`와 역변환을 따른다. `mdsRequired`처럼 화면에 노출하지 않는 값도 보존해서 다시 보내야 한다.
- AAGUID: `PUT /admin/_/tenants/{id}/aaguid` → `AaguidPolicyController.put(...)`. 실제 메서드 이름은 코드를 따른다.
- 두 탭 모두 SaveBar와 `$store.dirty`(Task 5)를 쓴다. Origin 형식 검증은 서버 `AllowedOriginFormat`의 결과를 필드 오류로 보여준다.

- [ ] **Step 1~7: 공통 화면 작업 절차.**
  - 테스트 포함: `hiddenMdsRequiredPreservedOnSave`, `diffShowsChangedFieldsOnly`, `invalidOriginInlineError`, `saveTriggersSavedEvent`
  - 커밋: `feat(admin-web): WebAuthn 설정·AAGUID 정책 탭`

### Task 11: API Key 탭

**원본:** `tenant/ApiKeysTab.tsx`, `apiKeyHelpers.ts`와 테스트, `api/apiKeys.ts`, `ui/SecretBox.tsx`, `ConfirmByTyping.tsx`, `lib/apiKeyExpiry.ts`, `lib/clipboard.ts`

**Files:**
- Create: `web/tenant/ApiKeysTabController.java`, `templates/admin/tenant/{api-keys,api-key-issued}.html`
- Test: `web/tenant/ApiKeysTabControllerTest.java`

**Interfaces:**
- 발급: `POST /admin/_/tenants/{id}/api-keys` → `ApiKeyAdminController.create(...)`
- 교체: `POST /admin/_/api-keys/{keyId}/rotate` → `rotate(...)`
- 회수: `DELETE /admin/_/api-keys/{keyId}` → `revoke(...)`
- 발급·교체 직후의 평문 키는 **응답 조각에만** 렌더링하고, 세션이나 모델 캐시에 남기지 않는다. 일러스트는 `api-key-issued.webp`.
- 회수는 ConfirmByTyping(원본과 같은 입력 문구)으로 확인한다.

- [ ] **Step 1~7: 공통 화면 작업 절차.**
  - 테스트 포함: `issuedSecretOnlyInResponse`(세션 속성에 키가 없음), `revokeRequiresTypedConfirm`, `expiryBadgeMatchesHelper`
  - 커밋: `feat(admin-web): API Key 탭`

### Task 12: 자격 증명 탭과 상세 다이얼로그

**원본:** `tenant/CredentialsTab.tsx`, `CredentialDetailDialog.tsx`, `credentialHelpers.ts`와 테스트, `api/credentials.ts`

**Files:**
- Create: `web/tenant/CredentialsTabController.java`, `templates/admin/tenant/{credentials,credential-rows,credential-detail}.html`
- Test: `web/tenant/CredentialsTabControllerTest.java`

**Interfaces:**
- 목록과 페이징: `GET /admin/_/tenants/{id}/tab/credentials?page=&q=` → `CredentialAdminController.list(...)` → `PageView<CredentialView>`
- 상세: `GET /admin/_/tenants/{id}/credentials/{credentialId}` → `authEvents(...)`
- 회수: `DELETE /admin/_/tenants/{id}/credentials/{credentialId}` → `revoke(...)`
- 빈 상태 일러스트는 `empty-credentials.webp`.

- [ ] **Step 1~7: 공통 화면 작업 절차.** 커밋: `feat(admin-web): 자격 증명 탭·상세 다이얼로그`

### Task 13: 감사 로그 탭과 퍼널 탭

**원본:** `tenant/AuditTab.tsx`, `auditHelpers.ts`, `FunnelTab.tsx`, `funnelHelpers.ts`와 테스트, `api/audit.ts`, `api/funnel.ts`, `lib/csvExport.ts`

**Files:**
- Create: `web/tenant/AuditTabController.java`, `web/tenant/FunnelTabController.java`, `templates/admin/tenant/{audit,audit-rows,funnel}.html`
- Test: `web/tenant/AuditTabControllerTest.java`, `web/tenant/FunnelTabControllerTest.java`

**Interfaces:**
- 감사 로그 목록: `GET /admin/_/tenants/{id}/tab/audit?action=&from=&to=` → `AuditLogController.list(...)`. 원본이 자동 갱신하는 주기를 `hx-trigger="every <원본 주기>"`로 옮기되, 필터 값을 유지해야 한다(`hx-include`).
- CSV: `GET /admin/tenants/{id}/audit.csv?<같은 필터>` → `CsvWriter.write(...)`, `Content-Disposition: attachment; filename=<원본 규칙>`
- 퍼널 차트: 원본처럼 div 막대를 서버 렌더링한다(`funnelHelpers`의 비율 계산은 Java로 옮긴다).

- [ ] **Step 1~7: 공통 화면 작업 절차.**
  - 테스트 포함: `csvMatchesOriginalFormat`(BOM, 헤더, 이스케이프), `pollingKeepsFilters`, `funnelPercentagesMatchHelper`
  - 커밋: `feat(admin-web): 감사 로그 탭(CSV·자동 갱신)·퍼널 탭`

### Task 14: 활동 화면

**원본:** `pages/ActivityPage.tsx`, `activityDiff.ts`, `activityLabels.ts`와 테스트, `api/activity.ts`

**Files:**
- Create: `web/activity/ActivityPageController.java`, `web/format/ActivityDiff.java`, `ActivityLabels.java`, `templates/admin/activity/{page,rows,detail}.html`
- Test: `web/activity/ActivityPageControllerTest.java`, `web/format/ActivityDiffTest.java`, `ActivityLabelsTest.java`

**Interfaces:**
- `GET /admin/activity`(PLATFORM만, 아니면 홈 리다이렉트)
- 목록 조각: `GET /admin/_/activity/rows?<필터>` → `ActivityController.activity(...)`. `ActivityPage.tsx:394`의 `POLL_MS`와 같은 주기로 폴링한다.
- 상세: `GET /admin/_/activity/{id}` → `detail(...)`
- 1초 단위 상대시간 갱신(`ActivityPage.tsx:312`)은 Alpine이 `data-ts` 속성을 읽어 처리한다.

- [ ] **Step 1~7: 공통 화면 작업 절차.**
  - 테스트 포함: `rpAdminRedirectedHome`, `diffRendersSameAsOriginal`(activityDiff 케이스 이전)
  - 커밋: `feat(admin-web): 활동 화면(폴링·상세·변경 비교)`

### Task 15: 감사 체인 화면

**원본:** `pages/AuditChainPage.tsx`(+ `.test.tsx`, `.backfillGuard.test.tsx`), `api/auditChain.ts`, `auditChainMonitor.ts`, `monthlyReport.ts`

**Files:**
- Create: `web/audit/AuditChainPageController.java`, `templates/admin/audit-chain/{page,overview,incidents,verify-result}.html`
- Test: `web/audit/AuditChainPageControllerTest.java`

**Interfaces:**
- 개요: `AuditChainMonitorController.overview(windowHours)`
- 검증: `verifyTenant(tenantId)`
- 백필: `backfill()`. 원본의 backfillGuard 조건(중복 실행 방지, 확인 절차)과 같다.
- 사건 목록·등록·해결: `incidents`, `createIncident`, `resolve`. 실제 메서드 이름은 코드를 따른다. `IncidentConflictException`, `IncidentNotTamperedException`, `IncidentNotFoundException`은 컨트롤러 `@ExceptionHandler`가 직접 호출에는 적용되지 않으므로, 화면 컨트롤러가 잡아서 원본과 같은 문구의 토스트로 바꾼다.
- 월간 리포트 PDF: 링크가 기존 `GET /admin/api/audit/chain/monthly-report?…`를 그대로 가리킨다(파일 다운로드라 HTML이 필요 없다).

- [ ] **Step 1~7: 공통 화면 작업 절차.**
  - 테스트 포함: `backfillGuardBlocksSecondRun`, `incidentConflictShowsToast`, `monthlyReportLinkPointsToExistingApi`
  - 커밋: `feat(admin-web): 감사 체인 화면(개요·검증·백필·사건·월간 리포트)`

### Task 16: 설정 화면과 라이선스 화면

**원본:** `pages/SettingsPage.tsx`, `settings/{AccountTab,AdminUsersTab,SecurityPolicyTab,MdsStatusTab,SystemInfoTab,RecoveryCodesModal}.tsx`와 테스트, `shell/QrCode.tsx`, `pages/LicensePage.tsx`와 테스트, 관련 `api/*.ts`

**Files:**
- Create: `web/settings/SettingsPageController.java`, `AccountTabController.java`, `AdminUsersTabController.java`, `SecurityPolicyTabController.java`, `MdsTabController.java`, `SystemInfoTabController.java`, `web/support/QrSvg.java`, `web/license/LicensePageController.java`, `templates/admin/settings/*.html`, `templates/admin/license/page.html`
- Test: 각 컨트롤러의 `*Test.java`, `web/support/QrSvgTest.java`

**Interfaces:**
- `GET /admin/settings?tab=`: 탭 접근은 `SettingsPage.tsx`의 `resolveTab(tab, platform)` 규칙을 그대로 따른다. 탭 조각은 `GET /admin/_/settings/tab/{tab}`.
- 계정 탭(MFA):
  - 등록: `POST /admin/_/settings/mfa/enroll` → `MfaController.enroll`. 응답 조각에 `QrSvg.render(otpauthUri, 200) : String`(ZXing → 인라인 SVG 문자열)과 Base32 secret을 넣는다. SVG는 `th:utext` 대신 서버에서 만든 `data:image/svg+xml;base64,…`를 `<img th:src>`로 넣는다(Review Focus 5의 규칙 유지).
  - 확인: `POST …/mfa/confirm` → `confirm`. 성공 조각이 RecoveryCodesModal이고, 코드 목록과 `RecoveryCodeFilename.of(...)` 값을 렌더링한다. `.txt` 다운로드와 복사는 Alpine이 화면의 코드로 Blob을 만들어 처리한다.
  - 해제: `POST …/mfa/disable` → `disable`.
- 관리자 사용자 탭: `AdminUserController`의 list, suspend, activate, addTenant, removeTenant. 가입 요청 승인·거절은 `SignupRequestController.list|approve|reject`이고, 원본 `AdminUsersTab.tsx`의 위치와 흐름을 따른다.
- 보안 정책 탭: 서버 검증 결과와 `PolicyValidation`(Task 3)으로 필드 오류를 보여주고, 저장은 `SecurityPolicyController`의 PUT 메서드. SaveBar와 `$store.dirty`를 쓴다.
- MDS 탭: `MdsAdminController.status|sync|history`. 키 관리가 MDS 탭이나 다른 위치에 있으면 `KeyMgmtController`도 원본 위치를 따른다.
- 시스템 정보 탭: `SystemInfoController.get()`
- 라이선스: `GET /admin/license`(PLATFORM만) → `LicenseController.get()`

- [ ] **Step 1~7: 공통 화면 작업 절차.**
  - 테스트 포함: `qrIsInlineDataUriNoExternal`, `recoveryCodesNotStoredInSession`(confirm 이후 세션 속성 목록에 코드가 없음), `settingsTabResolveMatchesOriginal`, `rpAdminSeesOnlyAccountTab`(원본 규칙대로), `licenseRpAdminRedirected`
  - 커밋: `feat(admin-web): 설정(계정·MFA·관리자·보안 정책·MDS·시스템 정보)과 라이선스 화면`

### Task 17: React 제거, 빌드·배포·문서 정리, 최종 검증

**Files:**
- Delete: `admin-ui/`(전체)
- Modify: `admin-app/build.gradle.kts`(node 플러그인, `node {}`, `buildUi`, `processResources` 복사 제거), `gradle/libs.versions.toml`의 `node-gradle` 플러그인 항목(다른 곳에서 안 쓰면 제거), `admin-app/Dockerfile`(`admin-ui` COPY와 Node 주석 제거), `AdminSecurityConfig.java`(`/admin/assets/**`, `/admin/index.html`, `/admin/favicon.*`, `/admin/crosscert-logo-transparent.png` 허용 제거. 새 경로는 Task 2에 이미 있다), `README.md`, `docs/dev-setup.md` 등에서 `admin-ui`/npm을 언급하는 부분
- Modify: `.gitignore`의 `admin-ui` 관련 줄
- Test: 전체 테스트, `OfflineAssetScanTest`

규칙: 전역 제약에 따라 루트 `build.gradle.kts`의 Java·Spring Boot 버전 줄은 건드리지 않는다. `libs.versions.toml`에서는 node 플러그인 항목만 지운다.

- [ ] **Step 1: parity 문서 확인.** 모든 절이 ✅이거나 사용자가 승인한 차이만 남아 있는지 확인한다. 미완료 항목이 있으면 이 task를 멈추고 보고한다.
- [ ] **Step 2: 삭제와 수정을 적용한다.**
- [ ] **Step 3: 참조가 남지 않았는지 확인.** `git grep -n -e 'admin-ui' -e 'npm ' -e 'node_modules' -e 'buildUi' -- ':!docs/superpowers'` → 0건
- [ ] **Step 4: 빌드와 테스트.** `./gradlew clean :admin-app:bootJar :admin-app:test` → 성공. 기존 테스트 파일은 수정 0건이어야 한다(`git diff --stat main -- admin-app/src/test`에 새 파일 추가만 있다).
- [ ] **Step 5: 이미지 빌드.** `docker build --platform linux/amd64 -t admin-app:verify -f admin-app/Dockerfile .` → 성공. 빌드 로그에 Node 다운로드가 없다.
- [ ] **Step 6: 폐쇄망 기동 확인.** 컨테이너를 `--network none`으로 띄우거나 로컬에서 네트워크를 끊고 실행한다(DB는 로컬 compose). 로그인부터 테넌트 상세, 설정까지 돌면서 브라우저 개발자 도구 Network에 외부 도메인 요청과 실패 요청이 0건인지 확인하고, 결과를 parity 문서 끝에 적는다.
- [ ] **Step 7: 커밋.** `refactor(admin): admin-ui(React)와 Node 빌드 제거 — 화면은 admin-app Thymeleaf로 대체`
