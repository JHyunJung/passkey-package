# Lombok Code Quality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lombok을 도입해 Logger 선언(49개 파일)과 순수 의존성 주입 생성자(약 28개 클래스) 보일러플레이트를 `@Slf4j` / `@RequiredArgsConstructor`로 대체하되, 바이트코드 동등성으로 런타임 동작 무변경을 증명한다.

**Architecture:** Lombok은 compile-time annotation processor이며 `compileOnly`로만 추가되어 런타임 classpath에 포함되지 않는다. 변경 전후 `.class` 바이트코드를 정규화 비교(상수풀 인덱스/공백/메서드순서/`@Generated` 무시)해 동작 동등성을 기계적으로 증명한다. 모듈별로 Phase를 나누고, 각 모듈마다 capture-before → 변환 → verify-after → 테스트 → 커밋의 게이트를 통과한다.

**Tech Stack:** Java 17, Spring Boot 3.5, Gradle 8.10 (Kotlin DSL), Lombok 1.18.34, javap (JDK 17).

**작업 위치:** 이 계획은 워크트리 `/Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/lombok-code-quality` (브랜치 `worktree-lombok-code-quality`)에서 실행한다. 모든 명령은 이 경로를 cwd로 가정한다. 메인 repo에서 작업하지 말 것.

**참고 스펙:** `docs/superpowers/specs/2026-06-14-lombok-code-quality-design.md`

---

## 사전 확인된 사실 (탐색·PoC로 실증 완료)

- Logger 선언은 49개 파일에서 **예외 없이** `private static final Logger log = LoggerFactory.getLogger(본인클래스.class);` (변수명 `log`, 인자 `본인.class`). 모듈별: core 13, admin-app 19, passkey-app 8, rp-app 6, sdk-java 3, webauthn 0.
- 이 중 **4개 파일은 `org.slf4j.MDC`/`Marker`/`MarkerFactory`를 추가로 import** → `Logger`/`LoggerFactory` import만 지우고 나머지 slf4j import는 보존해야 함:
  - `core/.../alert/LogAlertChannel.java` (Marker, MarkerFactory)
  - `core/.../api/RequestLoggingFilter.java` (MDC)
  - `passkey-app/.../security/ApiKeyAuthFilter.java` (MDC)
  - `rp-app/.../web/RequestLoggingFilter.java` (MDC)
- `@RequiredArgsConstructor` 안전 후보는 부록 A에 모듈별로 명시(직접 생성자 본문 확인). 그 외 클래스는 **건드리지 않는다.**
- 검증 스크립트 `scripts/verify-lombok-bytecode.sh`는 JwksAssembler PoC로 core 114개 클래스 전체에 대해 PASS 실증됨.

---

## File Structure

빌드 구성 (Phase 0 — Task 1):
- Modify: `gradle/libs.versions.toml` — lombok 버전/라이브러리 추가
- Modify: `build.gradle.kts` — subprojects에 compileOnly/annotationProcessor 추가
- Create: `lombok.config` — 루트 가드레일 (stopBubbling, log.fieldName, addLombokGeneratedAnnotation)
- Create: `scripts/verify-lombok-bytecode.sh` — 바이트코드 동등성 검증 도구

코드 변경 (Phase 1 = @Slf4j, Phase 2 = @RequiredArgsConstructor):
- Modify: 부록 A·B에 나열된 모듈별 소스 파일들. 각 파일은 단일 책임(자기 클래스의 Logger 선언/생성자) 변경만.

> **참고:** Task 1의 빌드 구성 4개 파일은 이미 워크트리에 적용되어 있을 수 있다(브레인스토밍 중 PoC로 생성됨). Task 1 Step들은 "현재 상태가 아래와 일치하는지 검증"으로 동작하며, 일치하면 그대로 커밋한다.

---

## 검증 도구 사용법 (모든 코드 Task가 의존)

`scripts/verify-lombok-bytecode.sh <module> <phase>`:
- `capture-before`: 변경 전 트리에서 모듈 컴파일 후 정규화 javap 덤프를 `/tmp/lombok-verify/<module>/before/`에 저장.
- `verify-after`: 변경 후 재컴파일 + 덤프 + before와 diff. **PASS = 모든 클래스 바이트코드가 `@Generated` 부착을 제외하고 완전 동일.**

정규화가 무시하는 정상 차이: 상수풀 인덱스 재번호(`#13`→`#N`), 공백 정렬, 메서드 선언 순서, `@lombok.Generated` 라인(RetentionPolicy.CLASS, 런타임 무관).

---

## Task 1: 빌드 구성 (Phase 0)

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`
- Create: `lombok.config`
- Create: `scripts/verify-lombok-bytecode.sh`

- [ ] **Step 1: `gradle/libs.versions.toml`에 lombok 추가**

`[versions]` 섹션 끝(archunit 줄 다음)에 추가:

```toml
lombok = "1.18.34"
```

`[libraries]` 섹션 첫 줄에 추가:

```toml
lombok = { module = "org.projectlombok:lombok", version.ref = "lombok" }
```

- [ ] **Step 2: `build.gradle.kts` subprojects `dependencies` 블록 수정**

`dependencies {` 블록 맨 앞(`"testImplementation"(...)` 줄 위)에 추가:

```kotlin
        // Lombok is a compile-time annotation processor only; it is NOT on the
        // runtime classpath (compileOnly), so production artifacts carry zero
        // Lombok dependency. Used solely for @Slf4j and @RequiredArgsConstructor.
        "compileOnly"(rootProject.libs.lombok)
        "annotationProcessor"(rootProject.libs.lombok)
        "testCompileOnly"(rootProject.libs.lombok)
        "testAnnotationProcessor"(rootProject.libs.lombok)
```

- [ ] **Step 3: `lombok.config` 생성 (루트)**

```properties
# Root Lombok configuration — inherited by all modules.
# stopBubbling marks this as the top-level config (no parent lookup above).
config.stopBubbling = true

# Pin the @Slf4j field name to 'log' to match the existing hand-written
# convention (private static final Logger log = ...). This is Lombok's
# default but is made explicit so the generated field is byte-identical
# to the code it replaces.
lombok.log.fieldName = log

# Attach @lombok.Generated to every generated member. Coverage tools then
# exclude them, and the bytecode-equivalence verification can filter these
# annotation lines out when diffing before/after .class dumps.
lombok.addLombokGeneratedAnnotation = true
```

- [ ] **Step 4: `scripts/verify-lombok-bytecode.sh` 생성**

(파일 본문은 워크트리에 이미 작성됨. 없으면 부록 C의 전체 스크립트를 사용. 생성 후 `chmod +x scripts/verify-lombok-bytecode.sh`.)

- [ ] **Step 5: 전체 모듈 컴파일 검증 (코드 변경 0이므로 그대로 성공해야 함)**

Run:
```bash
./gradlew :core:compileJava :webauthn:compileJava :admin-app:compileJava :passkey-app:compileJava :rp-app:compileJava :sdk-java:compileJava -q
```
Expected: EXIT 0 (모든 모듈 컴파일 성공). Lombok 의존성이 추가됐을 뿐 코드는 그대로이므로 빌드가 깨지면 안 된다.

- [ ] **Step 6: 검증 스크립트 동작 확인 (core로 self-test)**

Run:
```bash
scripts/verify-lombok-bytecode.sh core capture-before
scripts/verify-lombok-bytecode.sh core verify-after
```
Expected: 두 번째 명령이 `PASS: core bytecode is behavior-identical` 출력(변경 0이므로 당연히 PASS — 스크립트가 정상 동작함을 확인).

- [ ] **Step 7: 커밋**

```bash
git add gradle/libs.versions.toml build.gradle.kts lombok.config scripts/verify-lombok-bytecode.sh
git commit -m "build(lombok): compileOnly Lombok 1.18.34 + lombok.config 가드레일 + 바이트코드 검증 스크립트 (Phase 0)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: @Slf4j — core 모듈 (13개 파일)

**Files (Modify):** 부록 B-core의 13개 파일.

각 파일에 대한 **변환 규칙** (기계적, 모든 @Slf4j Task에 공통):
1. `import lombok.extern.slf4j.Slf4j;` 추가 (import 블록의 lombok/org.springframework 알파벳 순서에 맞춰).
2. `import org.slf4j.Logger;` 삭제.
3. `import org.slf4j.LoggerFactory;` 삭제.
4. **단, `org.slf4j.MDC`/`Marker`/`MarkerFactory` 등 다른 slf4j import는 절대 삭제하지 않는다** (core에서는 `LogAlertChannel`=Marker/MarkerFactory, `RequestLoggingFilter`=MDC 해당).
5. `private static final Logger log = LoggerFactory.getLogger(본인클래스.class);` 라인 삭제(앞뒤 빈 줄 정리는 하되 다른 멤버 간격 유지).
6. 클래스 선언(`public class X` / `public final class X` 등) 바로 위 줄에 `@Slf4j` 추가. 기존 클래스 어노테이션(`@Component` 등)이 있으면 그 위/아래 어디든 무방하나 일관되게 `@Slf4j`를 클래스 어노테이션 그룹 맨 위에 둔다.

- [ ] **Step 1: baseline 캡처**

Run: `scripts/verify-lombok-bytecode.sh core capture-before`
Expected: `captured baseline: ... (114 classes)`

- [ ] **Step 2: 13개 파일에 변환 규칙 적용**

부록 B-core의 각 파일에 위 변환 규칙 1~6을 적용. 예시 (`core/.../jwt/IdTokenIssuer.java`):

```java
// before
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
...
public class IdTokenIssuer {
    private static final Logger log = LoggerFactory.getLogger(IdTokenIssuer.class);

// after
import lombok.extern.slf4j.Slf4j;
...
@Slf4j
public class IdTokenIssuer {
```

MDC 보존 예시 (`core/.../api/RequestLoggingFilter.java`):

```java
// before
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;          // 보존!
...
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

// after
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;          // 그대로 유지
...
@Slf4j
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
```

- [ ] **Step 3: 컴파일 + 바이트코드 동등성 검증**

Run:
```bash
scripts/verify-lombok-bytecode.sh core verify-after
```
Expected: `PASS: core bytecode is behavior-identical (Lombok added only @Generated)`
FAIL이면: 출력된 diff로 어느 클래스가 다른지 확인. @Slf4j는 정적 logger 필드만 추가하므로 다른 메서드 바이트코드가 바뀌었다면 실수(잘못된 파일 수정 등) — 해당 변경 되돌리고 재시도.

- [ ] **Step 4: core 테스트 실행**

Run:
```bash
./gradlew :core:test
```
Expected: BUILD SUCCESSFUL (또는 base와 동일한 pre-existing 실패만). 신규 실패가 있으면 base worktree에서 같은 테스트를 돌려 pre-existing인지 확정(스펙 5.5 참고). 신규 실패면 변경 되돌리고 원인 분석.

- [ ] **Step 5: 잔존 import 누수 점검**

Run:
```bash
grep -rn "org.slf4j.Logger;\|org.slf4j.LoggerFactory;" core/src/main --include="*.java"
```
Expected: 출력 없음(전부 제거됨). MDC/Marker import는 grep에 안 걸리므로 보존 확인은 별도 — `grep -rn "MDC\|MarkerFactory" core/src/main --include="*.java"`로 해당 파일에 여전히 존재하는지 확인.

- [ ] **Step 6: 커밋**

```bash
git add core/src/main
git commit -m "refactor(log): @Slf4j 전환 — core 13개 파일 (Phase 1)

바이트코드 동등성 검증 PASS. MDC/Marker import 보존.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: @Slf4j — admin-app (19개 파일)

**Files (Modify):** 부록 B-admin의 19개 파일. MDC/Marker 추가 import 파일 없음(전부 Logger/LoggerFactory만).

- [ ] **Step 1: baseline 캡처**

Run: `scripts/verify-lombok-bytecode.sh admin-app capture-before`
Expected: `captured baseline: ...`

- [ ] **Step 2: 19개 파일에 Task 2의 변환 규칙 1~6 적용** (admin-app엔 MDC 보존 케이스 없음).

- [ ] **Step 3: 바이트코드 검증**

Run: `scripts/verify-lombok-bytecode.sh admin-app verify-after`
Expected: `PASS: admin-app bytecode is behavior-identical`

- [ ] **Step 4: admin-app 테스트**

Run: `./gradlew :admin-app:test`
Expected: BUILD SUCCESSFUL 또는 base 대비 신규 실패 없음. (메모리 주의: admin-app @WebMvcTest 슬라이스가 pre-existing 컨텍스트 이슈가 있을 수 있음 → base와 비교해 신규 여부 확정.)

- [ ] **Step 5: import 누수 점검**

Run: `grep -rn "org.slf4j.Logger;\|org.slf4j.LoggerFactory;" admin-app/src/main --include="*.java"`
Expected: 출력 없음.

- [ ] **Step 6: 커밋**

```bash
git add admin-app/src/main
git commit -m "refactor(log): @Slf4j 전환 — admin-app 19개 파일 (Phase 1)

바이트코드 동등성 검증 PASS.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: @Slf4j — passkey-app (8개 파일)

**Files (Modify):** 부록 B-passkey의 8개 파일. **MDC 보존 대상: `app/security/ApiKeyAuthFilter.java`** (Logger/LoggerFactory만 삭제, MDC 유지).

- [ ] **Step 1:** `scripts/verify-lombok-bytecode.sh passkey-app capture-before`
- [ ] **Step 2:** 8개 파일에 변환 규칙 적용. ApiKeyAuthFilter는 MDC import 보존.
- [ ] **Step 3:** `scripts/verify-lombok-bytecode.sh passkey-app verify-after` → Expected: PASS
- [ ] **Step 4:** `./gradlew :passkey-app:test` → BUILD SUCCESSFUL 또는 base 대비 신규 실패 없음
- [ ] **Step 5:** `grep -rn "org.slf4j.Logger;\|org.slf4j.LoggerFactory;" passkey-app/src/main --include="*.java"` → 출력 없음. `grep -rn "MDC" passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java` → 여전히 존재 확인.
- [ ] **Step 6: 커밋**

```bash
git add passkey-app/src/main
git commit -m "refactor(log): @Slf4j 전환 — passkey-app 8개 파일 (Phase 1)

바이트코드 동등성 검증 PASS. ApiKeyAuthFilter MDC import 보존.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: @Slf4j — rp-app (6개 파일)

**Files (Modify):** 부록 B-rp의 6개 파일. **MDC 보존 대상: `rpapp/web/RequestLoggingFilter.java`**.

- [ ] **Step 1:** `scripts/verify-lombok-bytecode.sh rp-app capture-before`
- [ ] **Step 2:** 6개 파일에 변환 규칙 적용. RequestLoggingFilter는 MDC import 보존.
- [ ] **Step 3:** `scripts/verify-lombok-bytecode.sh rp-app verify-after` → Expected: PASS
- [ ] **Step 4:** `./gradlew :rp-app:test` → BUILD SUCCESSFUL 또는 base 대비 신규 실패 없음
- [ ] **Step 5:** `grep -rn "org.slf4j.Logger;\|org.slf4j.LoggerFactory;" rp-app/src/main --include="*.java"` → 출력 없음.
- [ ] **Step 6: 커밋**

```bash
git add rp-app/src/main
git commit -m "refactor(log): @Slf4j 전환 — rp-app 6개 파일 (Phase 1)

바이트코드 동등성 검증 PASS. RequestLoggingFilter MDC import 보존.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: @Slf4j — sdk-java (3개 파일)

**Files (Modify):** 부록 B-sdk의 3개 파일. MDC/Marker 보존 케이스 없음.

- [ ] **Step 1:** `scripts/verify-lombok-bytecode.sh sdk-java capture-before`
- [ ] **Step 2:** 3개 파일에 변환 규칙 적용.
- [ ] **Step 3:** `scripts/verify-lombok-bytecode.sh sdk-java verify-after` → Expected: PASS
- [ ] **Step 4:** `./gradlew :sdk-java:test` → BUILD SUCCESSFUL 또는 base 대비 신규 실패 없음
- [ ] **Step 5:** `grep -rn "org.slf4j.Logger;\|org.slf4j.LoggerFactory;" sdk-java/src/main --include="*.java"` → 출력 없음.
- [ ] **Step 6: 커밋**

```bash
git add sdk-java/src/main
git commit -m "refactor(log): @Slf4j 전환 — sdk-java 3개 파일 (Phase 1)

바이트코드 동등성 검증 PASS.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: @RequiredArgsConstructor — core (8개 클래스)

**Files (Modify):** 부록 A-core.

각 클래스 **변환 규칙** (모든 @RequiredArgsConstructor Task 공통):
1. 적용 직전 **5개 안전 기준 재확인**(부록 D 체크리스트). 하나라도 어긋나면 그 클래스는 **건너뛰고 그대로 둔다.**
2. `import lombok.RequiredArgsConstructor;` 추가.
3. 클래스 선언 위에 `@RequiredArgsConstructor` 추가(기존 어노테이션 그룹 맨 위).
4. 명시적 생성자(`public X(...) { this.a=a; ... }`) **전체 삭제**.
5. 주입 필드 선언(`private final ...`)은 **그대로 유지**(순서도 유지).

- [ ] **Step 1: baseline 캡처**

Run: `scripts/verify-lombok-bytecode.sh core capture-before`

- [ ] **Step 2: 부록 A-core의 각 클래스에 변환 규칙 적용**

예시 (`core/.../jwt/JwksAssembler.java`):

```java
// before
import org.springframework.stereotype.Component;
...
@Component
public class JwksAssembler {
    private final SigningKeyRepository repo;
    public JwksAssembler(SigningKeyRepository repo) {
        this.repo = repo;
    }

// after
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
...
@Component
@RequiredArgsConstructor
public class JwksAssembler {
    private final SigningKeyRepository repo;
```

- [ ] **Step 3: 바이트코드 검증**

Run: `scripts/verify-lombok-bytecode.sh core verify-after`
Expected: `PASS`. FAIL이면 생성자 파라미터 순서 ≠ 필드 선언 순서일 가능성(부록 D 기준 4 위반) — 해당 클래스 변경 되돌리고 부록 D로 재판정. 정렬/인덱스 외 명령어 차이가 보이면 그 클래스는 후보 부적격이므로 제외.

- [ ] **Step 4: core 테스트**

Run: `./gradlew :core:test`
Expected: BUILD SUCCESSFUL 또는 base 대비 신규 실패 없음.

- [ ] **Step 5: 커밋**

```bash
git add core/src/main
git commit -m "refactor(di): @RequiredArgsConstructor 전환 — core 순수 주입 클래스 (Phase 2)

바이트코드 동등성 검증 PASS. 주입 외 로직 있는 생성자는 제외.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: @RequiredArgsConstructor — admin-app (9개 클래스)

**Files (Modify):** 부록 A-admin.

- [ ] **Step 1:** `scripts/verify-lombok-bytecode.sh admin-app capture-before`
- [ ] **Step 2:** 부록 A-admin의 각 클래스에 Task 7 변환 규칙 적용(적용 전 부록 D 재확인).
- [ ] **Step 3:** `scripts/verify-lombok-bytecode.sh admin-app verify-after` → Expected: PASS
- [ ] **Step 4:** `./gradlew :admin-app:test` → BUILD SUCCESSFUL 또는 base 대비 신규 실패 없음
- [ ] **Step 5: 커밋**

```bash
git add admin-app/src/main
git commit -m "refactor(di): @RequiredArgsConstructor 전환 — admin-app 순수 주입 클래스 (Phase 2)

바이트코드 동등성 검증 PASS.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: @RequiredArgsConstructor — passkey-app (9개 클래스)

**Files (Modify):** 부록 A-passkey.

- [ ] **Step 1:** `scripts/verify-lombok-bytecode.sh passkey-app capture-before`
- [ ] **Step 2:** 부록 A-passkey의 각 클래스에 변환 규칙 적용(부록 D 재확인). **주의: `app/security/ApiKeyAuthFilter`는 제외**(생성자에 meterRegistry.counter() 초기화 — 부록 D 기준 2 위반).
- [ ] **Step 3:** `scripts/verify-lombok-bytecode.sh passkey-app verify-after` → Expected: PASS
- [ ] **Step 4:** `./gradlew :passkey-app:test` → BUILD SUCCESSFUL 또는 base 대비 신규 실패 없음
- [ ] **Step 5: 커밋**

```bash
git add passkey-app/src/main
git commit -m "refactor(di): @RequiredArgsConstructor 전환 — passkey-app 순수 주입 클래스 (Phase 2)

바이트코드 동등성 검증 PASS. ApiKeyAuthFilter 등 복잡 생성자 제외.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: @RequiredArgsConstructor — rp-app (2개 클래스)

**Files (Modify):** 부록 A-rp (`web/WebAuthnController.java`, `web/WellKnownController.java`).
**제외: `user/InMemoryUserStore.java`** — 생성자에 `this.file = Path.of(file)` 변환 로직 있음(부록 D 기준 2 위반).

- [ ] **Step 1:** `scripts/verify-lombok-bytecode.sh rp-app capture-before`
- [ ] **Step 2:** WebAuthnController(4 final 필드, 순수 할당, 순서 일치 확인됨), WellKnownController(1 필드)에 변환 규칙 적용. InMemoryUserStore는 건드리지 않음.
- [ ] **Step 3:** `scripts/verify-lombok-bytecode.sh rp-app verify-after` → Expected: PASS
- [ ] **Step 4:** `./gradlew :rp-app:test` → BUILD SUCCESSFUL 또는 base 대비 신규 실패 없음
- [ ] **Step 5: 커밋**

```bash
git add rp-app/src/main
git commit -m "refactor(di): @RequiredArgsConstructor 전환 — rp-app WebAuthn/WellKnown 컨트롤러 (Phase 2)

바이트코드 동등성 검증 PASS. InMemoryUserStore(변환 로직) 제외.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: 최종 통합 검증 & 마무리

- [ ] **Step 1: 전 모듈 컴파일**

Run:
```bash
./gradlew :core:compileJava :webauthn:compileJava :admin-app:compileJava :passkey-app:compileJava :rp-app:compileJava :sdk-java:compileJava -q
```
Expected: EXIT 0.

- [ ] **Step 2: production classpath에 lombok 부재 확인**

Run:
```bash
./gradlew :core:dependencies --configuration runtimeClasspath 2>/dev/null | grep -i lombok || echo "OK: lombok absent from runtime"
```
Expected: `OK: lombok absent from runtime` (compileOnly이므로 런타임 의존성 0).

- [ ] **Step 3: 잔존 수동 Logger 선언 전수 점검**

Run:
```bash
grep -rn "LoggerFactory.getLogger" --include="*.java" */src/main | grep -v "/build/" || echo "OK: no manual LoggerFactory.getLogger remains"
```
Expected: `OK: ...` (49개 전부 @Slf4j로 전환됨).

- [ ] **Step 4: 모듈별 테스트 재확인 (이미 Task별로 통과했으나 회귀 없음 최종 확인)**

Run: `./gradlew :core:test :admin-app:test :passkey-app:test :rp-app:test :sdk-java:test`
Expected: 각 모듈 base 대비 신규 실패 없음. (전체 `build`는 메모리상 pre-existing 빨강이므로 사용하지 않음 — 모듈 test로 한정.)

- [ ] **Step 5: codex 독립 리뷰 (메모리 사용자 선호)**

전체 staged/committed diff에 대해 `/codex:review` 실행. 지적 사항 있으면 반영 후 재검증.

- [ ] **Step 6: 마무리**

`superpowers:finishing-a-development-branch` 스킬로 머지(main에 `--no-ff`) 또는 PR 옵션 진행. 머지 전 main 최신과 충돌(특히 AdminSecurityConfig/SecurityTest/ErrorCode 등 — 메모리 worktree stale base 주의) 확인.

---

## Self-Review (작성자 점검 완료)

- **스펙 커버리지:** 스펙 §2 범위(@Slf4j 49개 / @RequiredArgsConstructor 순수주입) = Task 2~6 / 7~10. §3 빌드구성 = Task 1. §4 Phase순서·모듈순서·커밋입도 = Task 배치. §5 바이트코드 검증 = 검증 스크립트 + 각 Task Step 3. §7 비범위(@Data/@Builder/Entity getter) = 어느 Task에도 없음. ✅
- **placeholder:** TBD/TODO 없음. 모든 변환에 실제 코드 예시. 후보 목록은 부록 A·B에 실측 경로로 명시. ✅
- **타입 일관성:** 검증 스크립트 함수명/인자(`capture-before`/`verify-after`)가 Task 전반에서 일관. ✅
- **모호성:** @RequiredArgsConstructor 적용 기준이 부록 D 5개 불리언 + "애매하면 제외" 명시. ✅

---

## 부록 A: @RequiredArgsConstructor 안전 후보 (실측)

> 적용 전 반드시 부록 D 체크리스트로 재확인. 검증 스크립트 PASS가 최종 게이트.

**A-core (8):**
- `core/src/main/java/com/crosscert/passkey/core/jwt/JwksAssembler.java` (1 field)
- `core/src/main/java/com/crosscert/passkey/core/license/LicenseHealthIndicator.java` (1)
- `core/src/main/java/com/crosscert/passkey/core/license/LicenseGuardFilter.java` (2)
- `core/src/main/java/com/crosscert/passkey/core/license/OnpremTenantPinFilter.java` (1)
- `core/src/main/java/com/crosscert/passkey/core/license/FeatureGateAspect.java` (1)
- `core/src/main/java/com/crosscert/passkey/core/license/LicenseVerifier.java` (3)
- `core/src/main/java/com/crosscert/passkey/core/alert/MailAlertChannel.java` (2)
- `core/src/main/java/com/crosscert/passkey/core/alert/AlertDispatcher.java` (1)

**A-admin (9):**
- `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java` (11)
- `admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityService.java` (3)
- `admin-app/src/main/java/com/crosscert/passkey/admin/auth/TenantBoundary.java` (1)
- `admin-app/src/main/java/com/crosscert/passkey/admin/auth/AdminUserDetailsService.java` (2)
- `admin-app/src/main/java/com/crosscert/passkey/admin/auth/RecoveryCodeService.java` (2)
- `admin-app/src/main/java/com/crosscert/passkey/admin/auth/MfaSecretCipher.java` (1)
- `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantLifecycleService.java` (4)
- `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/WebauthnDiffService.java` (2)
- `admin-app/src/main/java/com/crosscert/passkey/admin/retention/RetentionPurgeService.java` (7)

**A-passkey (9):**
- `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java` (11)
- `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java` (10)
- `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/CeremonyMetrics.java` (1)
- `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/challenge/ChallengeStore.java` (2)
- `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/credential/CredentialSelfService.java` (1)
- `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/mds/MdsVerifier.java` (1)
- `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationStartService.java` (8)
- `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationStartService.java` (8)
- `passkey-app/src/main/java/com/crosscert/passkey/app/security/RateLimitFilter.java` (1)

**A-rp (2):**
- `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/WebAuthnController.java` (4)
- `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/WellKnownController.java` (1)

**명시적 제외 (생성자에 주입 외 로직 / 파라미터 어노테이션):**
- `passkey-app/.../security/ApiKeyAuthFilter.java` — meterRegistry.counter() 초기화
- `core/.../license/LicenseHeartbeatScheduler.java` — RestClient.builder() 호출
- `core/.../ceremony/CeremonyEventRecorder.java` — TransactionTemplate 생성·설정
- `core/.../ceremony/CredentialAuthEventRecorder.java` — TransactionTemplate 생성·설정
- `admin-app/.../retention/RetentionPurgeJob.java` — @Value 파라미터
- `core/.../jwt/IdTokenIssuer.java` — @Value 파라미터
- `rp-app/.../user/InMemoryUserStore.java` — this.file = Path.of(file) 변환

> webauthn/sdk-java의 생성자 주입 클래스(예: IdTokenVerifier, NativeWebAuthnVerifier)는 본 범위에서 제외. 필요 시 후속 작업에서 부록 D로 판정 후 추가 가능. 단 @RequiredArgsConstructor는 Spring 빈이 아니어도 동작하므로, 추가하려면 동일 게이트(부록 D + 검증 스크립트)를 적용한다.

## 부록 B: @Slf4j 후보 (49개, 실측)

**B-core (13):** alert/AlertDispatcher, alert/LogAlertChannel(Marker 보존), api/GlobalExceptionHandler, api/RequestLoggingFilter(MDC 보존), ceremony/CeremonyEventRecorder, ceremony/CredentialAuthEventRecorder, jwt/IdTokenIssuer, license/LicenseBootstrap, license/LicenseCache, license/LicenseHeartbeatScheduler, license/LicenseStateMachine, mail/LogMailSender, policy/DefaultAaguidPolicyChecker
(전체 경로: `core/src/main/java/com/crosscert/passkey/core/<위 경로>.java`)

**B-admin (19):** apikey/ApiKeyAdminService, auth/MfaController, auth/TenantBoundary, config/AdminSecurityConfig, credential/CredentialAdminService, keymgmt/KeyExpirationJob, keymgmt/KeyRotationService, mds/MdsBlobClient, mds/MdsSchedulerService, mds/MdsSyncJob, operator/AdminUserService, operator/InvitationService, operator/PasswordResetService, policy/AaguidPolicyService, policy/DynamicCorsConfigurationSource, policy/SecurityPolicyService, retention/RetentionPurgeJob, tenant/TenantAdminService, tenant/TenantLifecycleService
(전체 경로: `admin-app/src/main/java/com/crosscert/passkey/admin/<위 경로>.java`)

**B-passkey (8):** api/v1/rp/JwksController, fido2/authentication/AuthenticationFinishService, fido2/credential/CredentialSelfService, fido2/mds/MdsVerifier, fido2/registration/RegistrationFinishService, security/ApiKeyAuthFilter(MDC 보존), security/ApiKeyLookupService, security/RateLimitFilter
(전체 경로: `passkey-app/src/main/java/com/crosscert/passkey/app/<위 경로>.java`)

**B-rp (6):** common/exception/GlobalExceptionHandler, config/ReloadableApiKeySupplier, user/InMemoryUserStore, web/RequestLoggingFilter(MDC 보존), web/WebAuthnController, web/WellKnownController
(전체 경로: `rp-app/src/main/java/com/crosscert/passkey/rpapp/<위 경로>.java`)

**B-sdk (3):** idtoken/IdTokenVerifier, internal/RedactingRequestInterceptor, PasskeyClient
(전체 경로: `sdk-java/src/main/java/com/crosscert/passkey/sdk/<위 경로>.java`)

> 일부 클래스는 @Slf4j(부록 B)와 @RequiredArgsConstructor(부록 A) 둘 다 후보다(예: TenantAdminService, TenantLifecycleService, TenantBoundary, AlertDispatcher, MailAlertChannel, RateLimitFilter, MdsVerifier, CredentialSelfService, RegistrationFinishService, AuthenticationFinishService). 이 경우 Phase 1에서 @Slf4j를 먼저 적용해 커밋한 뒤, Phase 2에서 같은 파일에 @RequiredArgsConstructor를 추가한다. 두 변경은 독립적이며 각 Phase의 검증 게이트를 개별 통과한다. (단순 교집합 판정은 부록 A∩B 경로로 확인할 것 — 위 목록은 예시.)

## 부록 C: 검증 스크립트 전체 본문

(워크트리 `scripts/verify-lombok-bytecode.sh`에 이미 존재. 분실 시 동일 내용으로 재생성 — 핵심 normalize 함수:)

```bash
norm() {
  sed -E 's/#[0-9]+/#N/g' \
    | sed -E 's/[[:space:]]+/ /g' \
    | sed -E 's/[[:space:]]+$//' \
    | grep -vE "lombok\.Generated|RuntimeInvisibleAnnotations|Compiled from|^Classfile|Last modified|SHA-256:|MD5:" \
    | sort
}
```
사용: `capture-before`로 변경 전 정규화 덤프 저장 → `verify-after`로 변경 후 덤프 + `diff -rq`. PASS = 전 클래스 동일.

## 부록 D: @RequiredArgsConstructor 안전 판정 체크리스트 (5개 전부 충족 시에만 적용)

1. [ ] 생성자가 **정확히 1개**다(오버로딩 없음).
2. [ ] 생성자 본문이 **`this.field = param;` 단순 할당문으로만** 구성(메서드 호출/조건/검증/계산/Objects.requireNonNull/변환 일절 없음).
3. [ ] 주입되는 모든 필드가 **`final`**이다.
4. [ ] **생성자 파라미터 순서 = (선언 시 초기화되지 않은) final 필드의 선언 순서**다. 선언과 동시에 초기화된 final 필드(`= new ...`)는 @RequiredArgsConstructor가 무시하므로 비교 대상에서 제외.
5. [ ] 생성자 파라미터에 `@Qualifier`/`@Value`/`@Lazy`/파라미터-레벨 `@Autowired` 등 어노테이션이 **없다**.

→ 하나라도 NO면 **그 클래스는 건드리지 않는다.** 적용 후에도 검증 스크립트가 인덱스/정렬 외 명령어 차이를 보이면 그 클래스를 되돌리고 제외한다.
