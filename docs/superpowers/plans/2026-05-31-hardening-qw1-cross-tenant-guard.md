# QW-1 — Cross-tenant 회귀 방지 Implementation Plan

> REQUIRED SUB-SKILL: **superpowers:subagent-driven-development** — execute each Task in its own focused subagent context; one Task per dispatch; verify (compile + invariant check + tests) before commit.

## Goal

admin-app은 VPD-EXEMPT(`APP_ADMIN_USER`)로 DB에 접속하므로 cross-tenant 행 격리가 **DB 레벨이 아니라 애플리케이션 `TenantBoundary` 단일 계층**에만 의존한다(`sec-admin-vpd-exempt-sole-layer`). 즉 어느 한 서비스 메서드가 `assertCanAccessTenant`/`currentTenantScope` 호출을 한 줄 빠뜨리면 즉시 cross-tenant 노출이 된다. 이 Phase는 **현재 동작/UI/응답을 일절 바꾸지 않으면서** 그 "한 줄 누락 회귀"를 막는 방어 인프라 3종을 추가한다:

1. `WebauthnDiffService.diff()` self-guard (`sec-webauthndiff-no-boundary-fragile`)
2. `ApiKeyAuthFilter` 진입부 방어적 `TenantContextHolder.clear()` (`sec-apikeyfilter-no-defensive-clear`)
3. ArchUnit boundary 강제 규칙 (`sec-admin-vpd-exempt-sole-layer`)

**핵심 불변식**: 세 변경 모두 "활성 버그 수정"이 아니라 **defense-in-depth 추가**다. 정상 호출 경로의 응답 바이트·UI·해시·서명키는 전혀 바뀌지 않아야 한다. 변경 후 기존 `RpAdminBoundaryIT`/`PlatformOperatorUnrestrictedIT`가 green 이어야 한다(이 환경에서 Oracle IT는 flaky 하므로 컴파일·단위 검증으로 대체하고 env 실패는 별도 표기).

## Architecture

- **레이어 책임**: Controller `@PreAuthorize`(role 게이팅, 1차) → Service `TenantBoundary` 호출(tenant 격리, 2차) → (admin-app은 VPD-EXEMPT라 DB 3차 격리 없음). QW-1은 2차 계층의 누락 가능성을 줄이고(self-guard), 그 누락을 테스트 시점에 잡는다(ArchUnit).
- **`TenantBoundary` API** (`admin-app/.../auth/TenantBoundary.java`, `@Component`, 생성자에 `ApplicationEventPublisher` 주입):
  - `void assertCanAccessTenant(UUID tenantId)` — PLATFORM_OPERATOR pass / RP_ADMIN은 자기 tenant만 / 위반 시 `BusinessException(ErrorCode.ACCESS_DENIED)` + `SecurityAlertEvent(TENANT_BOUNDARY_VIOLATION, CRITICAL)`.
  - `Optional<UUID> currentTenantScope()` — list 분기.
  - `void assertPlatformOperator()`.
- **`TenantContextHolder`** (`core/.../vpd/TenantContextHolder.java`): `set(UUID)`/`get()`/`clear()`(내부 `ThreadLocal.remove()`).
- **강제 지점이 균일하지 않다는 알려진 함정**(plan-inputs `QW-1` item "RP_ADMIN 도달가능 tenant-scoped 서비스" note):
  - 다수 서비스(`CredentialAdminService`, `FunnelService`, `TenantAdminService.get/update`, `ApiKeyAdminService.issue/revoke/rotate`)는 메서드 첫 줄 `assertCanAccessTenant`.
  - list 계열(`TenantAdminService.list`, `ApiKeyAdminService.list`)은 `currentTenantScope()`.
  - `WebauthnDiffService.diff()`는 **boundary 미호출**(Task 1이 고침) — controller가 `service.get()`로 검증된 tenantId를 넘김.
  - 따라서 "모든 `(UUID tenantId)` 서비스 메서드 → `assertCanAccessTenant` 필수" 같은 **전수 규칙은 즉시 거짓양성**을 낸다. → Task 3은 규칙을 처음부터 **좁게**(클래스 레벨, "tenant-scoped 서비스는 `TenantBoundary` 타입을 참조한다" 수준) 설계한다.

## Tech Stack

- Java 17 (toolchain 17), Spring Boot 3.5.14, JUnit5 (`useJUnitPlatform()`).
- 신규 테스트 의존성: `com.tngtech.archunit:archunit-junit5:1.3.0` (libs.versions.toml 카탈로그 경유, 기존 testcontainers/wiremock와 동일 패턴).
- 빌드/검증 명령(모두 worktree 루트 `cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins` 기준):
  - 컴파일: `./gradlew :core:compileJava :admin-app:compileJava :passkey-app:compileJava -q` → EXIT 0
  - 단위/슬라이스 테스트: `./gradlew :<module>:test --tests '<pattern>'`
  - **Oracle `*IT` 는 이 환경에서 flaky** → green 단언은 "컴파일 + 변경부의 동작 불변 논증"으로 대체하고, IT 실행 시 env 실패는 결과에 별도 표기.

---

## Task 1 — `WebauthnDiffService.diff()` self-guard (`sec-webauthndiff-no-boundary-fragile`)

`WebauthnDiffService.diff(tenantId, ...)` 첫 줄에 `tenantBoundary.assertCanAccessTenant(tenantId)`를 추가한다. 현재 유일 호출 경로(`TenantAdminController.diff` → `service.get(idOrSlug).id()`)는 이미 `TenantAdminService.get()` 내부에서 boundary를 거치므로 정상 동작 불변이고, **boundary 없이 직접 호출하는 미래 호출자만** 차단된다.

### Files
- `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/WebauthnDiffService.java` (수정)
- (Read 확인용) `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminController.java`

### Steps

**Step 1.1 — 현재 `WebauthnDiffService` 생성자/메서드 시그니처 재확인 (Read)**
`admin-app/.../tenant/WebauthnDiffService.java`를 Read. 확인된 현재 코드(plan 작성 시점, 정확 인용):
```java
package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class WebauthnDiffService {

    private final TenantRepository tenantRepo;

    public WebauthnDiffService(TenantRepository tenantRepo) {
        this.tenantRepo = tenantRepo;
    }

    @Transactional(readOnly = true)
    public WebauthnConfigDiff diff(UUID tenantId, WebauthnDiffRequest proposed) {
        Tenant t = tenantRepo.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("tenant not found " + tenantId));
        ...
```
`TenantBoundary` 의존성이 **없음**을 확인(생성자에 `tenantRepo`만). → 생성자 주입 추가 필요.

**Step 1.2 — `TenantBoundary` import 추가**
`import com.crosscert.passkey.core.repository.TenantRepository;` 다음 줄에 추가:
```java
import com.crosscert.passkey.admin.auth.TenantBoundary;
```

**Step 1.3 — 필드 + 생성자 주입 추가**
필드/생성자 블록을 다음으로 교체:
```java
    private final TenantRepository tenantRepo;
    private final TenantBoundary tenantBoundary;

    public WebauthnDiffService(TenantRepository tenantRepo, TenantBoundary tenantBoundary) {
        this.tenantRepo = tenantRepo;
        this.tenantBoundary = tenantBoundary;
    }
```

**Step 1.4 — `diff()` 첫 줄에 self-guard 추가**
`diff(...)` 본문 첫 줄(`Tenant t = tenantRepo.findById(...)` 위)에 삽입:
```java
    @Transactional(readOnly = true)
    public WebauthnConfigDiff diff(UUID tenantId, WebauthnDiffRequest proposed) {
        // Self-guard (sec-webauthndiff-no-boundary-fragile): this method must
        // enforce tenant isolation itself, not rely on the controller having
        // resolved tenantId via a boundary-checked path. Current sole caller
        // (TenantAdminController.diff → service.get()) already passes a
        // boundary-checked UUID, so this is a no-op for the normal flow and
        // only blocks future callers that pass an arbitrary tenantId.
        tenantBoundary.assertCanAccessTenant(tenantId);

        Tenant t = tenantRepo.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("tenant not found " + tenantId));
        ...
```

**Step 1.5 — 컴파일 검증**
```
./gradlew :admin-app:compileJava -q
```
기대: EXIT 0.

**Step 1.6 — 불변식 검증 (정상 경로 동작 불변 논증 + 호출 경로 확인)**
`TenantAdminController.java`를 Read 하여 diff 호출 경로가 변함없이 `service.get()`를 거치는지 확인. 확인된 현재 코드(정확 인용):
```java
    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    @PostMapping("/{idOrSlug}/webauthn-config/diff")
    public WebauthnConfigDiff diff(@PathVariable String idOrSlug,
                                   @RequestBody WebauthnDiffRequest proposed) {
        UUID tenantId = service.get(idOrSlug).id();
        return webauthnDiffService.diff(tenantId, proposed);
    }
```
불변식 논증을 plan 실행 노트에 기록:
- `service.get(idOrSlug)`(=`TenantAdminService.get`)는 내부에서 `assertCanAccessTenant`를 호출하므로, cross-tenant 요청은 `tenantId` 반환 **전에** 이미 `ACCESS_DENIED`로 throw 된다.
- 따라서 정상 경로에서 `diff()`의 새 첫 줄 `assertCanAccessTenant(tenantId)`는 **항상 PLATFORM pass 또는 자기 tenant(RP_ADMIN) 통과** → 추가 예외/동작 변화 없음. 응답 바이트(`WebauthnConfigDiff`)는 불변.
- 깨지는 유일한 케이스: 미래에 `diff()`를 boundary 미경유로 직접 호출하는 신규 코드 → 의도된 차단.

**Step 1.7 — (best-effort) 기존 boundary IT green 유지 확인**
```
./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.auth.RpAdminBoundaryIT' --tests 'com.crosscert.passkey.admin.auth.PlatformOperatorUnrestrictedIT'
```
기대: green. **Oracle Testcontainers env 실패 시**(Docker 미가용 등) 결과에 `ENV-FAILURE: RpAdminBoundaryIT (Testcontainers Oracle unavailable)`로 표기하고, Step 1.6의 정적 논증으로 동작 불변을 대체 근거로 남긴다. (참고: 현 `RpAdminBoundaryIT`는 grep 결과 diff 엔드포인트를 직접 커버하지 않으므로, 이 변경에 대한 회귀 위험은 컴파일+논증으로 충분히 닫힌다.)

**Step 1.8 — commit**
```
git add admin-app/src/main/java/com/crosscert/passkey/admin/tenant/WebauthnDiffService.java
git commit -m "$(cat <<'EOF'
fix(admin): WebauthnDiffService.diff() self-guard로 tenant 경계 강제

sec-webauthndiff-no-boundary-fragile: diff() 첫 줄에 tenantBoundary.assertCanAccessTenant(tenantId)
추가. 현재 유일 호출 경로(TenantAdminController.diff → service.get())는 이미 boundary 검증된
tenantId 를 넘기므로 정상 동작/응답 불변. boundary 미경유 직접 호출만 차단(defense-in-depth).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2 — `ApiKeyAuthFilter` 진입부 방어적 `TenantContextHolder.clear()` (`sec-apikeyfilter-no-defensive-clear`)

`ApiKeyAuthFilter.doFilterInternal` 진입 직후 `TenantContextHolder.clear()`를 추가해 `DevTenantHeaderFilter`와 대칭으로 만든다. 정상 요청은 인증 성공 시 항상 `set(row.tenantId())`로 덮어쓰므로 동작 불변이고, stale ThreadLocal 누출만 원천 차단한다. 성공 경로 `finally`의 기존 clear(line 195)는 그대로 유지한다.

### Files
- `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java` (수정)
- (참조) `core/src/main/java/com/crosscert/passkey/core/vpd/DevTenantHeaderFilter.java`

### Steps

**Step 2.1 — 현재 진입부 재확인 (Read)**
`ApiKeyAuthFilter.java` line 109-118을 Read. 확인된 현재 코드(정확 인용):
```java
    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader(HEADER);
        if (header == null || header.length() <= PREFIX_LEN
                || !header.startsWith(KEY_PREFIX)) {
            unauthorized(res);
            return;
        }
```
`import com.crosscert.passkey.core.vpd.TenantContextHolder;`는 이미 line 5에 존재(추가 import 불필요).

**Step 2.2 — 진입부에 방어적 clear 추가 (`DevTenantHeaderFilter` line 55-59 주석 패턴 복제)**
`doFilterInternal` 본문 첫 줄(`String header = req.getHeader(HEADER);` 위)에 삽입:
```java
    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        // Defense-in-depth (sec-apikeyfilter-no-defensive-clear): clear any
        // stale ThreadLocal value before we read the request. Reuse of a
        // Tomcat worker thread that exited a prior request via an unusual
        // path should never leak the previous tenant into this one. Mirrors
        // DevTenantHeaderFilter's entry-point clear. The success path still
        // set()s the real tenant (line below) and the finally clears it.
        TenantContextHolder.clear();

        String header = req.getHeader(HEADER);
        if (header == null || header.length() <= PREFIX_LEN
                || !header.startsWith(KEY_PREFIX)) {
            unauthorized(res);
            return;
        }
```

**Step 2.3 — 컴파일 검증**
```
./gradlew :passkey-app:compileJava -q
```
기대: EXIT 0.

**Step 2.4 — 불변식 검증 (동작 불변 논증)**
plan 실행 노트에 기록:
- **정상(인증 성공) 요청**: line 157 `TenantContextHolder.set(row.tenantId())`가 진입부 clear 이후 항상 실행되어 올바른 tenant를 세팅 → `chain.doFilter` 다운스트림이 보는 값 동일. 진입부 clear는 "이전 요청 잔여값"만 제거하므로 정상 요청의 가시 동작·응답 불변.
- **실패 조기반환 경로**(unknown-prefix/inactive/bad-secret/scope-denied): 어차피 `chain.doFilter` 미호출이라 다운스트림 비즈로직에 닿지 않음. 진입부 clear는 이 스레드가 다음 요청에 stale tenant를 남기지 않게 보강만 함.
- **성공 경로 `finally`의 기존 `clear()`(line 195) 유지**: 추가 clear와 중복이지만 무해(idempotent `ThreadLocal.remove()`).
- admin-ui/와이어 응답/해시/서명키 무관.

**Step 2.5 — (best-effort) passkey-app 인증 슬라이스/단위 테스트 회귀 확인**
ApiKeyAuthFilter 관련 단위/슬라이스 테스트를 탐색·실행:
```
./gradlew :passkey-app:test --tests '*ApiKeyAuthFilter*'
```
기대: 통과(또는 해당 테스트 없음 시 no-op). Oracle `*IT`가 섞여 env 실패하면 `ENV-FAILURE` 표기하고 Step 2.4 논증으로 대체. 매칭 테스트가 0건이면 그 사실을 노트에 기록(단위 커버리지 공백 — 컴파일+논증으로 닫음).

**Step 2.6 — commit**
```
git add passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java
git commit -m "$(cat <<'EOF'
fix(rp): ApiKeyAuthFilter 진입부 방어적 TenantContextHolder.clear()

sec-apikeyfilter-no-defensive-clear: doFilterInternal 진입 직후 clear() 추가로
DevTenantHeaderFilter 와 대칭화. 정상 요청은 인증 성공 시 set(tenantId) 으로 항상 덮으므로
동작/응답 불변. stale ThreadLocal(worker 스레드 재사용) 누출만 원천 차단. 성공 경로
finally 의 기존 clear 는 유지.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3 — ArchUnit 의존성 추가 + boundary 강제 규칙 (`sec-admin-vpd-exempt-sole-layer`)

ArchUnit 의존성을 카탈로그 경유로 admin-app `testImplementation`에 추가하고, **처음부터 좁은** boundary 강제 규칙 테스트를 작성한다. 규칙은 "tenant-scoped admin 서비스(=`@Service` 어노테이션이 붙은 admin 서비스 클래스 중 명시적 대상 집합)는 `TenantBoundary` 타입을 참조해야 한다"는 클래스 레벨 불변식으로 시작한다. 전수 메서드 규칙은 강제 지점 불균일(일부 `assertCanAccessTenant`, list 계열 `currentTenantScope`)로 거짓양성을 내므로 채택하지 않는다.

**중요(거짓양성 처리 규약)**: 규칙이 거짓양성을 내면 (a) 화이트리스트 + 주석으로 좁히고, (b) 그래도 규칙이 과도하게 깨져 의미를 잃으면 이 Task를 **DONE_WITH_CONCERNS**로 두고 plan 실행 노트에 "규칙 범위를 더 줄였음/줄여야 함"을 명시한다(억지로 통과시키려 production 코드를 바꾸지 말 것 — production 변경은 Task 1/2로 한정).

### Files
- `gradle/libs.versions.toml` (수정 — 카탈로그 등록)
- `admin-app/build.gradle.kts` (수정 — testImplementation)
- `admin-app/src/test/java/com/crosscert/passkey/admin/arch/TenantBoundaryArchTest.java` (신규)

### Steps

**Step 3.1 — 카탈로그에 ArchUnit 등록 (libs.versions.toml)**
확인된 현재 `[versions]` 마지막 줄은 `node-gradle = "7.0.2"` 다음 `wiremock = "3.10.0"`. `[versions]` 블록의 `wiremock = "3.10.0"` 다음 줄에 추가:
```toml
archunit = "1.3.0"
```
`[libraries]` 블록의 `wiremock-standalone = { module = "org.wiremock:wiremock-standalone", version.ref = "wiremock" }` 다음 줄에 추가:
```toml
archunit-junit5 = { module = "com.tngtech.archunit:archunit-junit5", version.ref = "archunit" }
```

**Step 3.2 — admin-app build.gradle.kts에 testImplementation 추가**
확인된 현재 마지막 testImplementation은 `testImplementation(rootProject.libs.wiremock.standalone)` (line 24). 그 다음 줄(닫는 `}` 위)에 추가:
```kotlin
    // QW-1 (sec-admin-vpd-exempt-sole-layer): ArchUnit enforces that
    // tenant-scoped admin services reference TenantBoundary, so a future
    // refactor that drops the isolation check fails the build (admin-app
    // runs VPD-EXEMPT — TenantBoundary is the sole isolation layer).
    testImplementation(rootProject.libs.archunit.junit5)
```

**Step 3.3 — 의존성 해석 검증**
```
./gradlew :admin-app:dependencies --configuration testRuntimeClasspath -q 2>&1 | grep -i archunit
```
기대: `com.tngtech.archunit:archunit-junit5:1.3.0` (및 transitive `archunit`, `archunit-junit5-api/engine`) 출력. 0건이면 카탈로그 키 오타 의심 → Step 3.1 재확인.

**Step 3.4 — (실패-우선) 좁은 ArchUnit 규칙 테스트 작성**
신규 파일 `admin-app/src/test/java/com/crosscert/passkey/admin/arch/TenantBoundaryArchTest.java`:
```java
package com.crosscert.passkey.admin.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * QW-1 (sec-admin-vpd-exempt-sole-layer) — cross-tenant 회귀 방지.
 *
 * <p>admin-app 은 VPD-EXEMPT(APP_ADMIN_USER)로 DB 에 접속하므로 cross-tenant
 * 격리가 애플리케이션 레벨 {@link com.crosscert.passkey.admin.auth.TenantBoundary}
 * 단일 계층에만 의존한다. 어느 tenant-scoped 서비스가 boundary 참조를 통째로
 * 잃으면 즉시 cross-tenant 노출이므로, 그 회귀를 빌드 시점에 잡는다.
 *
 * <p><b>의도적으로 좁은 규칙</b>: 강제 지점이 균일하지 않다(일부는 메서드 첫 줄
 * assertCanAccessTenant, list 계열은 currentTenantScope). 그래서 "모든 tenantId
 * 인자 메서드 → assert" 같은 전수 규칙은 거짓양성을 낸다. 대신 명시한 tenant-scoped
 * 서비스 클래스 집합이 TenantBoundary 타입을 참조(=주입/호출)하는지만 검사한다.
 * 새 tenant-scoped 서비스를 추가하면서 boundary 참조를 누락하면 이 목록에 추가하기
 * 전에 실패하여 작성자가 의식적으로 boundary 를 연결하도록 강제한다.
 */
class TenantBoundaryArchTest {

    private static final JavaClasses ADMIN_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.crosscert.passkey.admin");

    /**
     * 명시적 대상 집합(allowlist 방식): RP_ADMIN 이 도달 가능하고 tenant-scoped
     * 데이터를 다루는 서비스. 이들은 반드시 TenantBoundary 를 참조해야 한다.
     * 새 서비스를 여기 추가할 때 boundary 연결을 잊지 않게 하는 체크포인트.
     */
    @Test
    void tenantScopedServicesReferenceTenantBoundary() {
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("Service")
                .and().haveSimpleNameStartingWith("Credential")
                .or().haveSimpleName("ApiKeyAdminService")
                .or().haveSimpleName("TenantAdminService")
                .or().haveSimpleName("WebauthnDiffService")
                .or().haveSimpleName("FunnelService")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("com.crosscert.passkey.admin.auth.TenantBoundary")
                .as("tenant-scoped admin services must reference TenantBoundary "
                        + "(admin-app is VPD-EXEMPT; TenantBoundary is the sole "
                        + "cross-tenant isolation layer)")
                .because("sec-admin-vpd-exempt-sole-layer: losing the boundary "
                        + "reference silently re-enables cross-tenant access");

        rule.check(ADMIN_CLASSES);
    }
}
```
이 테스트는 Task 1 적용 **전**이면 `WebauthnDiffService`가 `TenantBoundary`를 참조하지 않아 실패한다(실패-우선 성격). Task 1이 이미 머지되었으므로 **현재는 통과해야 한다** — 즉 이 테스트가 Task 1의 self-guard가 실제로 boundary를 연결했음을 회귀 채널로 고정한다.

**Step 3.5 — ArchUnit 테스트 실행**
```
./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.arch.TenantBoundaryArchTest'
```
기대: **green**(5개 대상 클래스 모두 `TenantBoundary` 참조).
- 만약 `or()`/`and()` 연산자 우선순위 때문에 의도와 다른 클래스 집합이 매칭되어 거짓양성이 나면(ArchUnit의 `and`/`or`는 좌→우 평가라 그룹핑이 어려움), **규칙을 더 단순한 형태로 좁힌다** — 예: 명시 이름 리스트 기반 단일 술어로 교체:
  ```java
  ArchRule rule = classes()
          .that(new com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>(
                  "is a tenant-scoped admin service") {
              private final java.util.Set<String> TARGETS = java.util.Set.of(
                      "CredentialAdminService", "ApiKeyAdminService",
                      "TenantAdminService", "WebauthnDiffService", "FunnelService");
              @Override public boolean test(com.tngtech.archunit.core.domain.JavaClass c) {
                  return TARGETS.contains(c.getSimpleName());
              }
          })
          .should().dependOnClassesThat()
          .haveFullyQualifiedName("com.crosscert.passkey.admin.auth.TenantBoundary");
  ```
  이 명시 집합 술어가 우선순위 모호성을 제거한다. (둘 중 통과하는 형태를 채택하고, 실행 노트에 어느 형태를 썼는지 기록.)

**Step 3.6 — 거짓양성 시 처리 규약 적용 (조건부)**
규칙이 대상 집합 외 클래스까지 잡거나(거짓양성), `FunnelService` 등 일부가 실제로 `TenantBoundary`를 직접 주입하지 않고 다른 검증 경로를 쓴다면:
- 우선 `FunnelService` 등 문제 클래스를 대상 집합에서 **제거 + 주석**으로 좁힌다(그 클래스의 boundary 사용 방식이 타입 참조로 드러나지 않음을 명시).
- 최소 핵심 집합(`CredentialAdminService`, `ApiKeyAdminService`, `TenantAdminService`, `WebauthnDiffService`)만 남겨도 의미가 있다.
- 그래도 green을 못 만들면 production 코드를 건드리지 말고 이 Task를 **DONE_WITH_CONCERNS**로 표기, 실행 노트에 "규칙을 X 집합으로 축소했고 Y는 보류"를 기록.

**Step 3.7 — 불변식 검증 (production/UI/응답 불변)**
plan 실행 노트에 기록: Task 3은 **테스트 코드 + 빌드 의존성만** 추가했다. production 클래스·application.yml·마이그레이션·와이어 응답·UI 모두 무변경. `./gradlew :admin-app:compileJava -q`로 production 컴파일 영향 없음 재확인(EXIT 0). ArchUnit 테스트는 컴파일된 클래스를 정적 분석만 하므로 런타임 동작에 영향 없음.

**Step 3.8 — (best-effort) 기존 boundary IT 회귀 확인**
```
./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.auth.RpAdminBoundaryIT' --tests 'com.crosscert.passkey.admin.auth.PlatformOperatorUnrestrictedIT'
```
green 기대. Oracle env 실패 시 `ENV-FAILURE` 표기(ArchUnit 테스트는 Oracle 불요하므로 Step 3.5는 독립적으로 green 가능 — 이 점을 노트에 명시).

**Step 3.9 — commit**
```
git add gradle/libs.versions.toml admin-app/build.gradle.kts admin-app/src/test/java/com/crosscert/passkey/admin/arch/TenantBoundaryArchTest.java
git commit -m "$(cat <<'EOF'
test(admin): ArchUnit 으로 tenant-scoped 서비스의 TenantBoundary 참조 강제

sec-admin-vpd-exempt-sole-layer: admin-app 은 VPD-EXEMPT 라 TenantBoundary 가 cross-tenant
격리 단일 계층. archunit-junit5 1.3.0 을 카탈로그+testImplementation 으로 추가하고, 명시한
tenant-scoped 서비스 집합이 TenantBoundary 타입을 참조하는지 검사하는 좁은 규칙 추가.
강제 지점 불균일(assertCanAccessTenant vs currentTenantScope)로 인한 거짓양성을 피하려
전수 메서드 규칙이 아닌 클래스-참조 불변식으로 설계. production/응답/UI 불변(테스트+의존성만).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Final Verification (Phase 종료 전체 검증)

**FV.1 — 3모듈 컴파일**
```
./gradlew :core:compileJava :admin-app:compileJava :passkey-app:compileJava -q
```
기대: EXIT 0.

**FV.2 — 신규/변경 테스트 실행**
```
./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.arch.TenantBoundaryArchTest'
```
기대: green. (ArchUnit은 Oracle 불요 — 이 환경에서 신뢰성 있게 green 가능.)

**FV.3 — (best-effort) 회귀 IT**
```
./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.auth.*BoundaryIT' --tests 'com.crosscert.passkey.admin.auth.PlatformOperatorUnrestrictedIT'
./gradlew :passkey-app:test --tests '*ApiKeyAuthFilter*'
```
green 기대. Oracle/Testcontainers env 실패는 `ENV-FAILURE`로 표기하고 각 Task의 정적 불변식 논증을 대체 근거로 명시.

**FV.4 — 커밋 전 메모리 지침**
spec §의 메모리 지침(`feedback_codex_review_before_commit`)대로 누적 staged diff에 대해 `/codex review`를 돌리고, 보안 경계 변경(Task 1/2)에 대해 code-quality subagent 리뷰를 받는다. 단 본 plan은 작성 산출물이며, 실제 codex review는 실행 단계에서 각 commit 직전에 수행한다.

---

## Self-Review

### Spec coverage (이 Phase의 모든 finding id → Task 매핑)
- `sec-webauthndiff-no-boundary-fragile` → **Task 1** (`WebauthnDiffService.diff()` 첫 줄 `assertCanAccessTenant` + 생성자 주입 + 호출경로 불변 논증). ✅
- `sec-apikeyfilter-no-defensive-clear` → **Task 2** (`ApiKeyAuthFilter.doFilterInternal` 진입부 `TenantContextHolder.clear()`, `DevTenantHeaderFilter` 패턴 복제, 성공 경로 finally clear 유지). ✅
- `sec-admin-vpd-exempt-sole-layer` → **Task 3** (ArchUnit 의존성 카탈로그+testImplementation 추가 + 좁은 boundary 강제 규칙 + 거짓양성 처리 규약/DONE_WITH_CONCERNS). ✅
- spec §3.1/§3.2/§3.3 전부 Task 1/2/3에 1:1 대응. 검증 요구(`RpAdminBoundaryIT`/`PlatformOperatorUnrestrictedIT` green 유지 + 신규 ArchUnit green)는 FV.2/FV.3에 반영. ✅

### Placeholder scan
- "적절히 처리"/"TBD"/"테스트 추가"류 placeholder 없음. 모든 코드 Step에 실제 코드 블록(import/필드/생성자/메서드 본문/규칙 술어/카탈로그 toml/kts/commit 메시지)과 정확한 gradle 명령·기대 출력 포함. ArchUnit 규칙은 1차 형태 + 우선순위 모호 시 대체 술어(2차 형태)까지 제시해 실행자가 placeholder 없이 선택 가능. ✅

### 불변식 검증 커버리지 (각 Task가 "동작/UI/응답/해시 불변"을 증명하는가)
- Task 1: Step 1.6 — 호출 경로(`service.get()` 선행 boundary) 정적 논증으로 정상 경로 예외/응답 불변 증명. ✅
- Task 2: Step 2.4 — 성공 시 line 157 `set()`이 진입부 clear를 덮어쓰고 실패 경로는 `chain.doFilter` 미호출임을 논증. ✅
- Task 3: Step 3.7 — 테스트+의존성만 변경, production 컴파일 무영향(EXIT 0)으로 응답/UI/해시/서명키 불변 증명. ✅
- 해시체인(hex)/서명키/와이어 응답/UI 표시값을 바꾸는 코드 경로 없음(QW-1은 해시·서명·직렬화 코드를 일절 건드리지 않음 — 해당 변경은 QW-4 영역). ✅

### Type 일관성
- `assertCanAccessTenant(UUID)`: `WebauthnDiffService.diff(UUID tenantId, ...)`의 `tenantId`(UUID)를 그대로 전달 — 타입 일치. ✅
- `TenantBoundary`는 `@Component`라 `WebauthnDiffService`(`@Service`) 생성자 주입 가능, 패키지 `com.crosscert.passkey.admin.auth` import 정확. ✅
- `TenantContextHolder.clear()`: 인자 없는 static void, import 이미 존재(line 5) — 시그니처 일치. ✅
- 카탈로그 참조 `rootProject.libs.archunit.junit5` ↔ toml 키 `archunit-junit5`(kebab→dot 변환 규칙) 일치, 기존 `libs.wiremock.standalone`↔`wiremock-standalone` 패턴과 동형. ✅
- ArchUnit 1.3.0 API(`ClassFileImporter`, `ImportOption.Predefined.DO_NOT_INCLUDE_TESTS`, `ArchRuleDefinition.classes()`, `DescribedPredicate`): JUnit5 platform과 호환(루트 `useJUnitPlatform()`), Java 17 toolchain 호환. ✅

### 리스크/주의 (실행자 인지용)
- **R1 — ArchUnit 규칙 우선순위 모호성**: `.and()/.or()` 체인은 ArchUnit에서 좌→우 평가라 의도한 그룹핑이 안 될 수 있음. Step 3.5에서 green 안 되면 Step 3.5의 `DescribedPredicate` 명시-집합 형태로 즉시 전환(plan에 코드 제공). 
- **R2 — `FunnelService` 등 일부 대상의 boundary 참조가 타입 의존으로 안 드러날 가능성**: Step 3.6 규약대로 화이트리스트 축소 → 최소 핵심 4클래스 유지 → 그래도 안 되면 DONE_WITH_CONCERNS(production 변경 금지).
- **R3 — Oracle `*IT` flaky**: FV.3/각 Task의 IT Step은 best-effort. env 실패 시 `ENV-FAILURE` 표기 + 정적 불변식 논증으로 대체(ArchUnit 테스트는 Oracle 불요라 신뢰성 있게 green 가능 — 회귀 방지 핵심 채널은 살아있음).