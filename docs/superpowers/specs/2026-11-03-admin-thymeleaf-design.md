# admin 화면 Thymeleaf 전환 설계

- 작성일: 2026-11-03
- 대상: `admin-app`, `admin-ui`

## 1. 의도와 이유

운영 콘솔 화면(`admin-ui`, React 18 + Vite + TypeScript, 테스트 제외 TSX 약 9,500줄)을 **`admin-app` 안의 Spring Boot + Thymeleaf 서버 렌더링 화면**으로 다시 만든다.

이유는 **프론트엔드 유지보수 인력이 없기 때문**이다. 앞으로 화면 수정은 Java/Spring 개발자가 맡는다. 그래서 성공 기준은 이렇다.
- Node, npm, TypeScript, React 지식 없이 화면을 고칠 수 있다.
- 사용자(운영자) 입장에서는 기능과 겉모습이 지금과 같다.

## 2. 목표 / 비목표 / 실패 조건

### 목표
1. `admin-ui`에서 할 수 있는 모든 기능을 Thymeleaf 화면에서도 똑같이 할 수 있다(기능 동등성).
2. 디자인 시스템을 그대로 쓴다. `tokens.css`, `components.css`, `globals.css`의 토큰과 클래스, IBM Plex Sans KR / IBM Plex Mono 폰트, 아이콘, 일러스트, 로고를 유지한다. 화면이 지금과 시각적으로 같아야 한다.
3. 폐쇄망에서 문제 없이 기동하고 동작한다. 브라우저와 서버 모두 외부 도메인 요청이 0건이다.
4. `admin-app` 빌드에서 Node와 npm이 사라진다. `./gradlew :admin-app:bootJar`만으로 완결된다.

### 비목표
- 화면 개선, 리디자인, 기능 추가나 삭제
- JSON API(`/admin/api/**`)의 변경이나 정리
- 다른 모듈(`core`, `webauthn`, `passkey-app`, `rp-app`, `sdk-java`)의 변경
- 라이선스 정책 변경(DEAD 상태 화면 접근 등). §4.3을 참고한다.

### 실패 조건 (기술적으로 돌아가도 실패로 본다)
- 화면 하나라도 기존 기능이 빠지거나 동작이 달라진다. 권한별 노출, 입력 검증, 확인 절차, 자동 갱신, 단축키를 포함한다.
- CDN이나 외부 폰트 등 외부 URL을 하나라도 참조한다.
- 기존 JUnit 테스트(보안 테스트, 통합 테스트, ArchUnit)가 수정되거나 깨진다.
- 보안 수준이 낮아진다. CSRF, MFA 차단, 테넌트 격리(TenantBoundary), RP_ADMIN 범위 제한, 라이선스 차단을 포함한다.

## 3. 제약

| 항목 | 내용 |
|---|---|
| Java / Spring Boot | **Java 17, Spring Boot 3.5.14**를 유지한다. `passkey-app`과 같으며 루트 `build.gradle.kts`와 `gradle/libs.versions.toml`이 정한다. |
| 추가 허용 의존성 | `spring-boot-starter-thymeleaf`, htmx, Alpine.js. 아래 "빌더 판단"의 보조 라이브러리도 포함한다. |
| JS 배포 | htmx와 Alpine.js는 **정확한 버전을 고정한 파일을 `admin-app/src/main/resources/static/admin/js/vendor/`에 직접 넣는다**. CDN과 WebJars는 쓰지 않는다. 폐쇄망 빌드 머신에서 Maven 저장소 의존을 늘리지 않기 위해서다. 버전과 출처는 같은 폴더의 `README.md`에 적는다. |
| 폰트 | `@fontsource` 패키지에 들어 있는 woff2 파일(Sans KR 400/500/600/700, Mono 400/500)을 `static/admin/fonts/`로 옮기고 `@font-face`를 로컬 경로로 선언한다. |
| URL | 화면 URL은 지금과 같다: `/admin/login`, `/admin/tenants`, `/admin/tenants/{id}`, `/admin/activity`, `/admin/audit-chain`, `/admin/settings`, `/admin/license`, `/admin/forgot-password`, `/admin/reset-password`, `/admin/signup`. 탭 상태를 URL로 표현하고 있다면 그것도 유지한다. |
| JSON API | `/admin/api/**` 컨트롤러, DTO, 동작을 **그대로 둔다**. 이 API를 검증하는 기존 보안·통합 테스트도 손대지 않는다. |
| 빌드/배포 | `admin-app/build.gradle.kts`의 node 플러그인, `buildUi`, `processResources` 복사를 제거한다. `admin-app/Dockerfile`의 `admin-ui` COPY와 관련 주석을 정리한다. 이미지 이름, 포트, jar 이름, `deploy/` 출력 위치는 그대로다. |

## 4. 결정된 구조

### 4.1 화면 계층
- **화면 컨트롤러를 새로 만든다.** 패키지 이름은 빌더가 정한다(예: `com.crosscert.passkey.admin.web`). `@Controller`가 Thymeleaf 뷰나 조각(fragment)을 반환한다.
- 화면 컨트롤러는 **기존 서비스 계층을 직접 호출**한다. JSON API 컨트롤러를 HTTP로 다시 부르지 않는다. 기존 `@RestController`의 권한 검사(`@PreAuthorize`, TenantBoundary 호출 등)를 화면 컨트롤러에도 **똑같이** 적용한다. 서비스에 있는 검사는 자동으로 따라오지만, 컨트롤러에 있는 검사는 빠짐없이 옮겨야 한다.
- 결과적으로 같은 기능의 입구가 둘(JSON API, 화면)이 된다. 이건 기존 테스트 보존과 변경 최소화를 위해 의도적으로 받아들인 중복이다.

### 4.2 상호작용
- **htmx**는 서버 왕복을 맡는다. 페이지 이동(`hx-boost`), 탭 전환, 목록 검색과 페이징, 폼 제출 뒤 부분 갱신, 폴링(`hx-trigger="every Ns"`)이다.
- **Alpine.js**는 브라우저 안의 상태를 맡는다. 다이얼로그, 메뉴, 칩 입력, OTP 입력, 이름을 타이핑해야 확인되는 다이얼로그, 저장 안 한 변경 경고(SaveBar, beforeunload), 토스트, 명령 팔레트, 단축키, 유휴 세션 모달 카운트다운, 클립보드 복사다.
- 공통 JS(단축키 등록, 토스트 수신, htmx CSRF 헤더 설정)는 `static/admin/js/app.js` 하나에 둔다. 화면별 JS 파일은 꼭 필요할 때만 만든다.
- 토스트는 서버가 `HX-Trigger` 응답 헤더로 이벤트를 보내면 Alpine이 받아서 표시한다.

### 4.3 보안 체인
기존 `AdminSecurityConfig`의 정책을 유지한다. 서버가 페이지를 그리게 되면서 필요한 부분만 조정한다.
- **미인증 처리:** 미인증 요청이 화면 경로로 오면 `/admin/login`으로 리다이렉트하고, `/admin/api/**`로 오면 지금처럼 401 JSON을 준다. 진입점을 경로에 따라 나누는 방식이다.
- **권한 규칙:** 화면 경로는 지금 `permitAll`이다. React가 클라이언트에서 막았기 때문이다. 이제는 화면 경로를 **인증 필수**로 바꾼다. 로그인, 비밀번호 찾기·재설정, 가입 요청 화면과 정적 에셋은 예외다. PLATFORM_OPERATOR 전용 화면(`RequirePlatform`으로 막던 tenants 목록, activity, audit-chain, license)은 서버에서 강제한다.
- **MFA:** MFA 대기 세션은 화면 경로에서도 MFA 화면 외에는 막는다. `MfaPendingFilter`의 적용 범위를 화면 경로까지 넓히거나, 같은 효과를 내는 화면용 처리를 둔다.
- **라이선스:** `core/.../LicenseGuardFilter`와 그 면제 목록(`DEAD_EXEMPT`)은 **수정하지 않는다**. 라이선스 화면(`LicensePage`)은 다른 화면처럼 그대로 옮긴다.
  - **알려진 제약:** onprem 모드의 DEAD 상태에서는 화면 경로와 정적 에셋이 503으로 막혀 라이선스 화면을 직접 열 수 없다. 지금 React 구성에서도 같은 문제가 있고(직접 진입 시 503), `passkey.deployment.mode`가 기본값 `saas`라 현재 어떤 배포에서도 생기지 않는다.
  - 고객사 제공 방식(SaaS / 구축형)을 정할 때 별도 과제로 다룬다. 이번 범위가 아니다.
- **CSRF:** 기존 `CookieCsrfTokenRepository`(Path=/admin)를 유지한다. Thymeleaf `th:action` 폼은 토큰이 자동으로 들어가고, htmx 요청은 `X-XSRF-TOKEN` 헤더로 보낸다. 미인증 예외 경로(가입 요청, 비밀번호 재설정)의 CSRF 면제 범위는 넓히지 않는다. 같은 기능의 화면 폼은 CSRF 토큰을 넣어 보낸다.
- **로그인 응답:** 기존 성공·실패·로그아웃 핸들러는 지금 응답 형식(JSON)을 기대하는 기존 테스트를 깨지 않아야 한다. 기존 통합 테스트 11개는 form-urlencoded 본문에 `X-XSRF-TOKEN` **헤더**를 붙여 보내고 2xx를 기대한다. 그래서 "폼 제출이면 리다이렉트"로 나누면 안 된다. 분기 기준은 이렇게 한다.
  - `HX-Request` 헤더가 있거나 CSRF가 `_csrf` **파라미터**로 온 요청(Thymeleaf 화면) → 리다이렉트 또는 조각
  - 그 밖의 요청 → 지금처럼 JSON

  감사 로그와 레이트 리밋 동작은 변하면 안 된다.
- **RP_ADMIN이 PLATFORM 전용 화면에 들어올 때:** React와 똑같이 자기 테넌트 상세(`/admin/tenants/{자기 테넌트}`)로 리다이렉트한다.
- **옛 URL:** `/admin/api-keys`, `/admin/audit`, `/admin/mds`, `/admin/keys`는 React의 `*` 경로 동작과 똑같이 리다이렉트한다.

### 4.4 화면 목록 (동등성 기준)
아래 `admin-ui` 소스가 각 화면의 **정답지**다. 기존 vitest 테스트(`*.test.tsx`, `*.test.ts`)는 기대 동작 명세로 읽는다.

| 화면 | 원본 |
|---|---|
| 공통 틀 | `shell/Sidebar`, `Header`, `Breadcrumb`, `ErrorBoundary`, `components/LicenseBanner` |
| 공통 부가 기능 | `extras/CommandPalette`, `ShortcutSheet`, `IdleSessionModal`, `useGoShortcuts`, `useHelpShortcut`, `usePaletteShortcut` |
| 로그인 / MFA | `pages/LoginPage`, `MfaChallenge`, `settings/RecoveryCodesModal`, `shell/QrCode` |
| 비밀번호 찾기·재설정, 가입 요청 | `ForgotPasswordPage`, `ResetPasswordPage`, `SignupRequestPage` |
| 테넌트 목록 / 생성 | `TenantsListPage`, `NewTenantDialog` |
| 테넌트 상세 (탭) | `TenantDetailPage`, `tenant/TenantOverview`, `WebauthnConfigTab`, `AaguidPolicyTab`, `ApiKeysTab`, `CredentialsTab`, `CredentialDetailDialog`, `AuditTab`, `FunnelTab` |
| 활동 | `ActivityPage`(+ `activityDiff`, `activityLabels`) |
| 감사 체인 | `AuditChainPage`(+ `api/auditChainMonitor`, `monthlyReport`) |
| 설정 (탭) | `SettingsPage`, `settings/AccountTab`, `AdminUsersTab`, `SecurityPolicyTab`, `MdsStatusTab`, `SystemInfoTab` |
| 라이선스 | `LicensePage` |
| 공통 UI 35종 | `ui/*` → Thymeleaf 조각과 Alpine 컴포넌트 |

### 4.5 클라이언트 로직 이전
- `lib/*`, `pages/*Helpers.ts`, `i18n/labels.ts`, `lib/josa.ts`, `lib/kst.ts`, `lib/policyValidation.ts` 같은 순수 로직은 **Java로 옮긴다**. 대상은 서버 유틸이나 Thymeleaf 헬퍼 빈이고, 각각의 vitest 테스트를 JUnit 테스트로 옮겨서 같은 경우의 수를 검증한다.
- 입력 검증 규칙은 서버(Bean Validation 또는 기존 서비스 검증)를 기준으로 한다. 즉시 피드백이 필요한 필드는 htmx로 서버에 검증을 요청한다. 브라우저 쪽에 규칙을 따로 두지 않는다.
- QR 코드(MFA 등록)는 서버에서 만든다(ZXing). 비밀값을 외부로 보내지 않는다.
- CSV 내보내기는 서버 응답(`Content-Disposition`)으로 처리한다. 화면에 걸린 필터를 요청 값으로 넘긴다. CSV 형식(`csvExport`)은 지금과 같게 한다.
- 복구 코드 `.txt` 다운로드는 **예외로 브라우저에서 처리한다.** 화면에 이미 그려진 코드를 Alpine/JS 몇 줄로 Blob으로 만들어 내려받는다. 평문 복구 코드를 서버 세션(Redis)에 보관하지 않아 지금의 보안 수준을 그대로 지키기 위해서다(설계자 판단). 파일 이름 규칙(`recoveryCodeFilename`)은 지금과 같게 한다.
- 이미 서버 쪽에서 만드는 기능(월간 리포트 PDF 등)은 그대로 쓴다.

### 4.6 스타일
- `tokens.css`, `components.css`, `globals.css`를 `static/admin/css/`로 그대로 옮긴다.
- TSX의 인라인 스타일(`style={{…}}`, 약 519곳)은 시각적으로 같은 결과가 나오도록 옮긴다. 반복되는 것은 `components.css`에 클래스로 추가하고, 한 번만 쓰이는 것은 인라인으로 둔다. 기존 클래스와 토큰의 값은 바꾸지 않는다.
- 아이콘(`icons/Icons.tsx`)은 SVG Thymeleaf 조각으로, 일러스트(`assets/illustrations`)와 로고, 파비콘은 정적 파일로 옮긴다.

### 4.7 전환 방식과 `admin-ui` 처리
- **한 번에 바꾼다.** 기능 브랜치(예: `feat/admin-thymeleaf`)에서 모든 화면을 만들고 검증한 뒤 한 번에 머지한다. React와 Thymeleaf가 함께 돌아가는 중간 상태는 출시하지 않으므로, 둘이 공존하게 하는 장치는 만들지 않는다.
- 전환하는 동안에는 `admin-ui`를 참고용으로 그대로 둔다.
- 모든 화면의 동등성 검증이 끝나면 **`admin-ui` 디렉터리와 Node 빌드를 저장소에서 제거한다**. 이 부분은 설계자 판단이다. 프론트엔드 유지보수 부담을 없애는 것이 이번 작업의 목적이기 때문이다. 같은 시점에 `AdminWebMvcConfig`의 SPA forward 설정과 보안 설정의 `/admin/assets/**`, `/admin/index.html` 허용 규칙도 정리한다.
- README와 `docs/`에서 `admin-ui`나 npm 빌드를 언급하는 부분을 현재 구조에 맞게 고친다.

## 5. 검증

1. **기존 테스트:** `./gradlew :admin-app:test`(통합 테스트 포함)가 **기존 테스트 코드를 수정하지 않은 채** 모두 통과해야 한다.
2. **화면 보안 테스트(신규, MockMvc):** 화면 경로별로 다음을 검증한다.
   - 미인증이면 로그인으로 리다이렉트
   - RP_ADMIN이 다른 테넌트나 PLATFORM 전용 화면에 접근하면 차단
   - MFA 대기 세션이면 차단
   - CSRF 토큰이 없는 POST는 거부
3. **로직 테스트(신규, JUnit):** §4.5에서 옮긴 로직마다 원래 vitest 케이스를 옮긴다.
4. **동등성 확인:** 화면마다 기존 React 화면과 새 화면을 같은 데이터로 띄워 비교한다. 기능 체크리스트와 스크린샷 비교를 쓴다. 이 비교는 전환 작업 중에만 하는 도구이고, 저장소의 상시 빌드에는 넣지 않는다.
5. **폐쇄망 확인:** 렌더링된 HTML, CSS, JS에 외부 URL(`http(s)://`로 시작하는 외부 도메인)이 없음을 자동 테스트로 검사한다. 정적 리소스와 템플릿 전체를 스캔한다. 추가로 네트워크를 끊은 환경에서 기동해 수동으로 확인한다.

## 6. 빌더에게 맡기는 것
- 화면 컨트롤러 패키지 구조, 템플릿 디렉터리 구조, 조각 분할 방식
- htmx / Alpine.js의 정확한 버전. 각각 최신 안정 2.x / 3.x로 하고 고정한다.
- Thymeleaf Layout Dialect나 htmx-spring-boot 같은 보조 라이브러리 사용 여부. 쓰더라도 Maven Central 의존성 1~2개 수준으로 제한한다.
- 화면별 작업 순서. 단, 공통 틀과 공통 UI 조각을 먼저 만든다.
- 동등성 비교 도구의 구체적인 형태
