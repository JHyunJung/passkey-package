# admin-app Kotlin 전환 (3단계) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** admin-app 본 코드(158파일)와 테스트(135파일)를 실무 정석 Kotlin 으로 전부 옮기되, 화면·HTTP·보안·세션 동작은 바꾸지 않는다.

**Architecture:** 먼저 Java 상태에서 안전망(JSON 형태·렌더 HTML 스냅샷, 세션 직렬화 왕복, CSV/PDF 특성)을 기록·동결하고, 2단계와 같은 Kotlin 빌드 기반을 깐다. 본 코드를 패키지 묶음 단위로 Kotlin 화하면서 기존 Java 테스트를 안전망으로 쓰고(접근자 이름·import 만 수정), 마지막에 테스트를 Kotlin 화한 뒤 de-interop 한다.

**Tech Stack:** Kotlin 2.4.21, Spring Boot 4.1.2 (Spring 7, Security 7, Session Redis, Thymeleaf, Jackson 3 + jackson-module-kotlin), JUnit 5, AssertJ, mockito-kotlin, jsoup, ArchUnit, Testcontainers 2, Java 17 target.

**Spec:** `docs/superpowers/specs/2026-11-07-admin-app-kotlin-design.md` (계승: `docs/superpowers/specs/2026-11-06-passkey-app-kotlin-design.md`, 패턴: `docs/superpowers/notes/kotlin-conversion-patterns.md` — 모든 implementer 필독)

## Global Constraints

- 패턴 메모 `docs/superpowers/notes/kotlin-conversion-patterns.md` §1(빌드)·§3(함정 표) 전부 적용. passkey-app 의 기존 Kotlin 코드가 스타일 기준.
- Kotlin **2.4.21**(루트 `bomProperty` 이미 적용됨). admin-app 에 `kotlin.jvm` + `kotlin.spring`, `jvmToolchain(17)`, `-Xjsr305=strict`, `javaParameters = true`. MockK·Kotest·코루틴 금지, Mock 은 mockito-kotlin.
- 스타일: 주 생성자 주입, Lombok 제거, record → `data class`(`@JvmRecord` 아님), `val` 기본, null 은 타입으로, 로거는 file-private `LoggerFactory.getLogger(X::class.java)`. 예외 흐름·검증 순서·로그/화면 메시지 문구 불변. `!!` 금지(불가피하면 근거 주석).
- 폼/요청 객체의 검증 대상 필드는 nullable + 같은 제약; 원래 기본형(`int`/`long`/`boolean`) 컴포넌트는 Kotlin 기본형 non-null 유지(바인딩 typeMismatch 동작 보존).
- core·webauthn 반환값은 원 코드의 null 처리 그대로 타입화. `?.`/`?:` 로 실패 경로를 조용히 통과시키기 금지.
- 이름·패키지·빈 이름·`@PreAuthorize` SpEL 문자열·URL 매핑·뷰 이름·모델 속성 이름 불변. ArchUnit 대상 클래스 단순 이름 불변.
- `src/main/resources/templates/**`·`static/**` 수정 금지. 예외: Kotlin getter 이름 차이(`is*` Boolean 등)로 불가피한 최소 수정 — 그 경우 템플릿 대신 Kotlin 쪽 `@get:JvmName` 으로 맞추는 것을 우선하고, 템플릿을 고치면 커밋 메시지에 파일·줄 기록.
- **동결**: `admin-app/src/test/resources/contract/**` — Task 1·2 에서 기록한 뒤로 수정 금지(새 스냅샷 추가만 허용). `AuditChainJacksonCompatTest` 픽스처 포함.
- Task 4~13(본 코드 전환) 동안 Java 테스트 수정은 접근자 호출 이름·import 만. 수정 파일·줄 수를 커밋 메시지에 기록.
- 모든 커밋에서 `./gradlew :admin-app:test --continue --max-workers=1` 의 실패 집합 ⊆ base 실패 집합(Task 1 Step 1 에서 기록, 기대값 공집합). `:passkey-app:test`·`:rp-app:test` 는 Task 3·17 에서 확인.
- 회귀 수집 스크립트: `bash /private/tmp/claude-501/-Users-jhyun-Git-10-work-crosscert-Passkey2/53d985a9-c4e6-4c0e-b8a3-3a0aa8ee078b/scratchpad/ad-baseline.sh <tag> admin-app` (worktree 가드가 복합 git 명령을 거부하므로 스크립트/단일 명령 사용).

## Review Focus

1. **템플릿 프로퍼티 해석** — 뷰 모델이 data class 가 되며 `is*` Boolean·`get*` 메서드의 SpEL 해석이 바뀌어 화면 일부가 비거나 500 이 나는 경우. → Task 2 렌더 HTML 스냅샷이 판정, 매 web Task 필수 통과.
2. **세션 역직렬화** — 로그인 후 다음 요청에서 세션 속성(AdminUserDetails, 활성 테넌트)이 Redis 왕복 후 복원되지 않아 로그아웃·500. → Task 1 `SessionSerializationTest`(JDK 왕복) + Task 17 실기동 로그인 후 여러 화면 이동.
3. **권한·테넌트 격리 프록시** — Kotlin final 클래스·`@PreAuthorize`/`@Transactional` 프록시 누락, `#param` SpEL 이름 소실로 권한 판정이 바뀜. → 기존 보안 테스트 + Task 6 `KotlinProxyWiringTest`(all-open·CGLIB·`#param` 해석) + ArchUnit.
4. **폼 바인딩 누락/빈 값** — 필드 빠진 POST·빈 문자열·숫자 아닌 값이 같은 `BindingResult` 오류·같은 화면 메시지로 가야 함. → 기존 컨트롤러 테스트 + Task 2 스냅샷에 폼 오류 fragment 포함.
5. **CSV/PDF 내보내기** — 줄바꿈·이스케이프·인코딩·BOM·헤더가 바뀌면 고객 데이터 반출물이 달라짐. → Task 1 `ExportCharacterizationTest`.

---

### Task 1: base 측정 + JSON·세션·내보내기 안전망 (Java, 빌드 변경 없음)

**Files:**
- Create: `admin-app/src/test/java/com/crosscert/passkey/admin/contract/AdminJsonShapeContractTest.java` + `admin-app/src/test/resources/contract/json/*.shape.json`
- Create: `admin-app/src/test/java/com/crosscert/passkey/admin/contract/SessionSerializationTest.java`
- Create: `admin-app/src/test/java/com/crosscert/passkey/admin/contract/ExportCharacterizationTest.java` (+ 필요 시 `contract/export/*`)
- 참고: passkey-app 의 `JsonShape`·`contract.record` 사용법(`passkey-app/src/test/kotlin/.../contract/`), admin 의 `AuditChainJacksonCompatTest`·`AdminWebSliceTest`.

- [ ] **Step 1: base 측정** — 회귀 스크립트 `base` 태그 실행, `admin-app.failed.txt`·total 을 커밋 메시지와 ledger 에 기록.
- [ ] **Step 2: JSON 형태 스냅샷** — `@RestController`/`@ResponseBody` 24개 클래스(`TenantAdminController, CredentialAdminController, ProfileController, LicenseController, PlatformInsightsController, MeController, ActiveTenantController, MfaController, ActivityController, AuditLogController, MonthlyReportController(JSON 부분), KeyMgmtController, SystemInfoController, AdoptionController, ApiKeyAdminController, AuditChainMonitorController, MdsAdminController, FunnelController, SignupRequestController, AdminUserController, PasswordResetController, AaguidPolicyController, SecurityPolicyController`, `WebExceptionAdvice` 오류 봉투)의 각 GET 엔드포인트 성공 응답 1개 + 대표 오류 응답(검증 실패·404·403)을 슬라이스(MockMvc, 서비스 결정적 스텁)로 호출해 JSON 형태(키 이름·순서·타입·null 여부) 스냅샷. 쓰기 엔드포인트는 요청 바디 누락 필드 `{}` 에 대한 응답 형태 1개씩.
- [ ] **Step 3: 세션 직렬화** — `grep -rn "setAttribute\|@SessionAttributes\|HttpSession" admin-app/src/main` 으로 세션 저장 객체 전수 → 각 타입(+`AdminUserDetails` 를 담은 `SecurityContextImpl`/`UsernamePasswordAuthenticationToken`)을 `ObjectOutputStream` 왕복, 복원 후 `equals`/핵심 필드 단언. 목록을 테스트 주석에 고정.
- [ ] **Step 4: 내보내기 특성** — CSV 6곳(`CredentialsTabController, AuditTabController, TenantsPageController, InsightsPageController, ActivityPageController, FunnelTabController`)의 다운로드 응답 전체 바이트(헤더 포함: Content-Type, Content-Disposition), 쉼표·따옴표·줄바꿈·한글 포함 데이터로 1회씩. PDF(`MonthlyReportService`)는 PDFBox 추출 텍스트 전체. 뮤테이션 1개(예: 구분자 변경)로 실패 확인 후 원복.
- [ ] **Step 5: `-Pcontract.record=true` 로 기록 → 내용 검토 → 비교 모드 2회 연속 통과.**
- [ ] **Step 6: 커밋** — `test(admin-app): Kotlin 전환 전 JSON 형태·세션 직렬화·내보내기 안전망`

### Task 2: 렌더 HTML 스냅샷 (Java, 빌드 변경 없음)

**Files:**
- Create: `admin-app/src/test/java/com/crosscert/passkey/admin/contract/HtmlSnapshot.java` (정규화 유틸) + `RenderedHtmlContractTest*.java`(화면군별로 나눠도 됨) + `admin-app/src/test/resources/contract/html/**`

- [ ] **Step 1: `HtmlSnapshot.normalize(String): String`** — CSRF 토큰 값, CSP nonce, 현재 시각/상대 시각 문자열, 랜덤 ID 만 고정 토큰으로 치환. 그 외는 바이트 그대로. 단위 테스트로 치환 대상만 바뀌는지 확인.
- [ ] **Step 2: 화면 컨트롤러 전부**(web 하위 `*PageController`/`*TabController`/`ShellController`/`CommandPaletteController`/`LoginPageController`/`PasswordPagesController`/`HomeRedirectController`/`ScreenErrorViewResolver` 오류 화면) — 각 주 GET 1개(전체 페이지) + htmx fragment 1개 이상(`HX-Request: true`) + 폼 검증 오류 fragment 가 있는 컨트롤러는 그 응답 1개. 역할별 차이가 있는 화면(PLATFORM_OPERATOR vs RP_ADMIN)은 둘 다. 서비스는 결정적 스텁(`is*` Boolean 이 true/false 둘 다 나오는 데이터 포함 — Review Focus 1). 상태 코드·`HX-*` 헤더·리다이렉트 Location 도 스냅샷에 포함.
- [ ] **Step 3: 커버리지 표** — 컨트롤러 × 스냅샷 수 표를 테스트 클래스 KDoc/Javadoc 에 기록. 뮤테이션 1개(템플릿이 참조하는 record 컴포넌트 하나를 이름 변경)로 실패 확인 후 원복.
- [ ] **Step 4: 기록 → 검토 → 비교 모드 2회 통과(시간 의존 flaky 없음).**
- [ ] **Step 5: 커밋** — `test(admin-app): 화면 렌더 HTML 스냅샷 안전망`

### Task 3: admin-app Kotlin 빌드 기반 (Kotlin 코드 0)

**Files:** Modify `admin-app/build.gradle.kts` (plugins `alias(libs.plugins.kotlin.jvm)`, `alias(libs.plugins.kotlin.spring)` — passkey-app 과 동일 방식; `kotlin {}` 블록; deps `jackson-module-kotlin`, `kotlin-reflect`; test `mockito-kotlin`; `springBoot.mainClass` 는 아직 Java 클래스 유지)

- [ ] **Step 1: 적용 후** `./gradlew :admin-app:dependencies --configuration runtimeClasspath | grep -E "kotlin-stdlib|kotlin-reflect|jackson-module-kotlin"` → 2.4.21 / BOM 버전.
- [ ] **Step 2: 회귀** — admin-app 실패 집합 ⊆ base, Task 1·2 스냅샷 전부 통과(jackson-module-kotlin 등록 영향 0). `:passkey-app:test`·`:rp-app:test` 녹색.
- [ ] **Step 3: 커밋** — `build(admin-app): Kotlin 2.4.21 빌드 기반`

### Task 4~13: 본 코드 Kotlin 화 (공통 절차)

각 Task: 나열된 패키지의 `src/main/java/...` Java 파일 삭제 → `src/main/kotlin/<같은 패키지>` 에 Kotlin 생성. Java 테스트는 접근자/import 만 수정. 회귀 스크립트로 실패 집합 ⊆ base, 계약·스냅샷 전부 통과. Lombok 생성 멤버를 Kotlin 이 직접 써야 하면 그 Java 클래스를 같은 Task 로 당겨온다(기록). 커밋 `refactor(admin-app): <패키지> Kotlin 화` + 테스트 수정 줄 기록.

- **Task 4** — `license, scheduler, system, retention, keymgmt, mds, activity, insights, funnel` (≈2.8k줄). `RetentionPurgeService`·`ActivityService` RAC, `@Scheduled` 크론/고정지연 문자열 불변.
- **Task 5** — `audit, policy, credential, apikey` (≈2.3k줄). 감사 체인 해시 입력 직렬화(`AuditChainJacksonCompatTest`) 불변이 핵심.
- **Task 6** — `auth, config` (≈1.75k줄) + Create `admin-app/src/test/kotlin/.../KotlinProxyWiringTest.kt`: `@Transactional`/`@PreAuthorize` 빈이 CGLIB 프록시, 실제 트랜잭션 readOnly 여부, `@PreAuthorize("...#param...")` 가 Kotlin 파라미터 이름으로 해석(거부 케이스 포함). `MfaSecretCipher`·`RecoveryCodeService`·`TenantBoundary` 는 예외 흐름·상수 시간 비교 그대로. `AdminUserDetails` 는 `Serializable` 유지(Task 1 테스트 통과).
- **Task 7** — `tenant, operator` (≈1.6k줄). ArchUnit 대상 `TenantAdminService`·`WebauthnDiffService` 이름 유지.
- **Task 8** — `web/format, web/support, web/HomeRedirectController` (≈2k줄). `ViewHelpers`·`KstFormat` 등 템플릿이 호출하는 메서드 시그니처(vararg 포함) 불변 — 템플릿 `fmt.*`·`#{...}` 사용처 grep. `StandaloneJson` 은 Kotlin 모듈 미등록 유지.
- **Task 9** — `web/shell, web/auth, web/license, web/insights` (≈1.5k줄).
- **Task 10** — `web/settings` (≈1.5k줄).
- **Task 11** — `web/tenant` 중 `TenantsPageController, TenantDetailPageController, TenantTabs, AuditHelpers, FunnelHelpers, ApiKeysTabController, AaguidTabController` (≈2.2k줄).
- **Task 12** — `web/tenant` 중 `CredentialsTabController, WebauthnTabController, FunnelTabController, AuditTabController` (≈1.9k줄).
- **Task 13** — `web/activity, web/audit, AdminApplication` (≈1.3k줄). `AdminApplication.kt` 는 `runApplication<AdminApplication>(*args) { setMainApplicationClass(AdminApplication::class.java) }`, `springBoot.mainClass = "com.crosscert.passkey.admin.AdminApplicationKt"`. 완료 시 `find admin-app/src/main -name '*.java'` 0. `bootJar` 기동해 시작 로그 `Started AdminApplication` 확인.

### Task 14~16: 테스트 Kotlin 화 (공통 절차)

Java 테스트 → `src/test/kotlin` 동일 패키지. 단언 의미 불변, 클래스#메서드 목록이 전환 전후 동일(스크립트로 비교해 커밋 메시지에 기록). mockito 는 mockito-kotlin(`any()`→`anyOrNull()` 규칙 — 패턴 메모). 실패 집합 ⊆ base.

- **Task 14** — 비-web 패키지 테스트 전부 + `AdminFlowIT, KeyRotationIT, MdsSchedulerIT, arch/TenantBoundaryArchTest` (≈15k줄). 너무 크면 implementer 가 auth·audit·config·apikey·credential / 나머지 로 커밋 2개로 나눔.
- **Task 15** — `web/tenant` 테스트 (≈5.7k줄).
- **Task 16** — 나머지 web 테스트 + `web/*.java` 최상위 테스트 + Task 1·2 의 `contract/*` 테스트 (≈10k줄). 완료 시 `find admin-app/src -name '*.java'` 0.

### Task 17: de-interop + 문서 + 실기동

- [ ] **Step 1: de-interop** (패턴 메모 §2-5) — Java 호출자 전제의 nullable 파라미터·`!!`·`Optional` 반환·`@Jvm*` 정리(프레임워크 요구 제외). 그로 인해 표현 불가해진 입력의 특성 테스트는 근거와 함께 삭제(목록 기록). **기준 동작은 브랜치 base Java 와 직접 대조**.
- [ ] **Step 2: 문서** — README 스택 표기, `docs/dev-setup.md`, `docs/backlog.md`, 패턴 메모에 admin 고유 함정(템플릿·세션·폼 바인딩) 추가.
- [ ] **Step 3: 전 모듈 회귀** — `ad-baseline.sh final admin-app passkey-app rp-app` 새 실패 0.
- [ ] **Step 4: 실기동** — 로컬 admin(SPRING_PROFILES_ACTIVE=local, Oracle·Redis 컨테이너) 로그인(+MFA) → 테넌트 목록·상세 각 탭·설정·감사 체인·Activity 열람, API Key 발급/폐기 1회, 로그아웃 후 재로그인(세션 왕복). Docker admin-app 이미지 `--platform linux/amd64` 빌드.
- [ ] **Step 5: 커밋** — `refactor(admin-app): Java 호출자 전제 interop 잔재 제거` / `docs: admin-app Kotlin 전환 반영`
