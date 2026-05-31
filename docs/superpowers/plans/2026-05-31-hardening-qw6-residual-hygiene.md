# QW-6 — 잔여 위생 묶음 (security + quality) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 감사 risk=none 중 QW-1~5에 들어가지 않은 효과 있는 5건(SDK alg 핀, dev 키 위생, sample-rp 거부값 echo, gradle SHA 핀, API 패턴 통일)을 동작·UI·응답 불변으로 닫는다.

**Architecture:** 서로 독립적인 5개 소규모 변경. sdk-java(JWT alg 단언) / admin-app yml(dev 키) / sample-rp(검증 응답) / gradle wrapper(체크섬) / admin-ui(API 헬퍼 통일). 공통 모듈/순서 의존 없음.

**Tech Stack:** Java 17, Spring Boot 3.5.14, nimbus-jose-jwt, JUnit5; React/TS(admin-ui); Gradle 8.10.

**작업 디렉토리:** worktree 루트 `/Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins`.
- sdk 컴파일: `./gradlew :sdk-java:compileJava :sdk-java:compileTestJava -q`
- sample-rp 컴파일: `./gradlew :sample-rp:compileJava -q`
- admin-app 컴파일: `./gradlew :admin-app:compileJava -q`
- admin-ui: `cd admin-ui && npx tsc -b && npm test`

---

## Task 1 — SDK IdTokenVerifier alg 핀 (`sec-idtoken-alg-not-pinned`)

**Files:**
- Modify: `sdk-java/src/main/java/com/crosscert/passkey/sdk/idtoken/IdTokenVerifier.java`
- Test: `sdk-java/src/test/java/com/crosscert/passkey/sdk/idtoken/IdTokenVerifierAlgTest.java` (신규)

JWT 헤더 `alg`를 RS256으로 명시 고정해 alg confusion 표면을 제거한다. 발급 측이 RS256 단일이라 정상 토큰 검증은 불변.

- [ ] **Step 1: import 추가**

`IdTokenVerifier.java`의 import 블록(`import com.nimbusds.jose.JWSVerifier;` 다음 줄)에 추가:
```java
import com.nimbusds.jose.JWSAlgorithm;
```

- [ ] **Step 2: verify() 시작부에 alg 단언 추가**

현재 `verify()` 본문 시작:
```java
    public IdTokenClaims verify(String compactJwt) {
        Instant started = clock.instant();
        try {
            SignedJWT jwt = SignedJWT.parse(compactJwt);
            String kid = jwt.getHeader().getKeyID();
```
을 다음으로 교체 (`SignedJWT.parse` 직후, kid 추출 전에 alg 단언):
```java
    public IdTokenClaims verify(String compactJwt) {
        Instant started = clock.instant();
        try {
            SignedJWT jwt = SignedJWT.parse(compactJwt);
            // Pin alg to RS256 — the issuer signs exclusively with RS256, so this
            // rejects alg-confusion / "none" / HS* downgrade attempts without
            // affecting any legitimate token (sec-idtoken-alg-not-pinned).
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
                log.warn("id-token verify failed: reason=unexpected-alg alg={}",
                        jwt.getHeader().getAlgorithm());
                throw new PasskeyIdTokenException(
                        "Unexpected JWS algorithm: " + jwt.getHeader().getAlgorithm());
            }
            String kid = jwt.getHeader().getKeyID();
```

- [ ] **Step 3: 신규 테스트 작성**

기존 `PasskeyClientContractIT`가 RS256 토큰 생성 헬퍼를 가질 수 있으므로 먼저 확인: `sdk-java/src/test/java/com/crosscert/passkey/sdk/PasskeyClientContractIT.java`를 Read 하여 (a) RSA 키/JWKS/SignedJWT 생성 패턴, (b) `JwksCache` 구성 방법을 파악한다. 그 패턴을 재사용해 아래 테스트를 작성:

```java
package com.crosscert.passkey.sdk.idtoken;

import com.crosscert.passkey.sdk.exception.PasskeyIdTokenException;
// 나머지 import 는 ContractIT 의 JWKS/SignedJWT 생성 패턴을 그대로 따른다.

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdTokenVerifierAlgTest {

    // ContractIT 의 헬퍼(RSA keypair → JWKS, SignedJWT 빌더)를 복제/재사용해
    // verifier 를 만든다. 아래는 의도(assertion)만 고정 — 구체 setup 은
    // ContractIT 패턴을 따른다.

    @Test
    void rejectsNonRs256Token() {
        // given: 같은 kid/키로 서명하되 alg 가 RS256 이 아닌(또는 변조된 헤더) 토큰
        // when/then: verify(...) 가 PasskeyIdTokenException 을 던진다
        // (메시지에 "Unexpected JWS algorithm" 포함)
        // assertThrows(PasskeyIdTokenException.class, () -> verifier.verify(badAlgJwt));
    }

    @Test
    void acceptsRs256Token() {
        // given: 정상 RS256 토큰(ContractIT 와 동일 발급 경로)
        // when/then: verify(...) 가 예외 없이 IdTokenClaims 를 반환
        // assertNotNull(verifier.verify(goodJwt));
    }
}
```

**중요**: 위 테스트 본문은 `PasskeyClientContractIT`의 실제 토큰/JWKS 생성 헬퍼를 Read 한 뒤 그 코드로 채운다. ContractIT가 재사용 가능한 헬퍼를 제공하지 않으면, 이 Task는 `acceptsRs256Token`(정상 토큰 통과 = 회귀 없음)만이라도 구현하고 `rejectsNonRs256Token`은 ContractIT의 키 생성 코드를 복제해 작성한다. 토큰 생성이 과도하게 복잡하면 DONE_WITH_CONCERNS로 보고하고 단언 로직(Step 2)만 컴파일+기존 ContractIT green으로 게이트.

- [ ] **Step 4: 컴파일 + 테스트**

```
./gradlew :sdk-java:compileJava :sdk-java:test --tests '*IdTokenVerifier*' --tests '*PasskeyClientContractIT*'
```
기대: 컴파일 EXIT 0, 신규 테스트 + 기존 ContractIT green. **불변식**: 기존 ContractIT(정상 RS256 토큰)가 green = 정상 토큰 검증 동작 불변.

- [ ] **Step 5: commit**

```bash
git add sdk-java/src/main/java/com/crosscert/passkey/sdk/idtoken/IdTokenVerifier.java sdk-java/src/test/java/com/crosscert/passkey/sdk/idtoken/IdTokenVerifierAlgTest.java
git commit -m "fix(sdk): IdTokenVerifier 에 RS256 alg 핀 (alg confusion 차단)"
```

---

## Task 2 — dev/test master-key 0키 교체 (`sec-dev-master-key-all-zero`)

**Files:**
- Modify: `admin-app/src/main/resources/application-dev.yml:27`
- Modify: `admin-app/src/test/resources/application-test.yml:31`

0바이트 키(`AAAA...=` = base64 of 32 zero bytes)를 0이 아닌 무작위 32바이트 공개 상수로 교체. dev/test 전용이라 prod 무영향.

- [ ] **Step 1: 무작위 32바이트 base64 상수 생성**

다음 명령으로 0이 아닌 32바이트 base64를 1회 생성(이 값을 두 파일에 동일 사용):
```bash
openssl rand -base64 32
```
예시 출력(실제로는 위 명령의 결과를 쓸 것): `7Qm2vK8xNpR4tWz...=` (44자 base64, `=` 1개로 끝남). **0으로만 된 값이 아닌지 확인.**

- [ ] **Step 2: application-dev.yml 교체**

`admin-app/src/main/resources/application-dev.yml`의 `key-envelope` 블록:
```yaml
  key-envelope:
    master-key: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
```
를 (Step 1 생성값으로) 교체 + 주석:
```yaml
  key-envelope:
    # NON-SECRET, dev only — 공개 상수이지만 '0키' 신호를 제거해 실수로
    # prod 에서 약한 키가 쓰이는 것을 식별하기 쉽게 한다. prod 는 환경변수로 주입.
    master-key: "<Step1 에서 생성한 base64 32바이트>"
```

- [ ] **Step 3: application-test.yml 교체**

`admin-app/src/test/resources/application-test.yml`의 동일 블록을 **같은 값**으로 교체(테스트 결정성 위해 dev와 동일 상수). 주석 동일.

- [ ] **Step 4: 검증 — MfaSecretCipher/KeyEnvelope 테스트 green**

키 길이/형식이 동일(32바이트 base64)하므로 복호화 동작 불변. 검증:
```
./gradlew :admin-app:test --tests '*MfaSecretCipher*' --tests '*KeyEnvelope*'
```
기대: green. **불변식**: 32바이트 키 길이 불변 → seal/open round-trip 동작 동일. (기존 0키로 암호화된 영속 데이터가 test/dev DB에 없으므로 키 교체가 기존 데이터 복호화를 깨지 않음 — dev/test는 매번 새 부트스트랩.)

- [ ] **Step 5: commit**

```bash
git add admin-app/src/main/resources/application-dev.yml admin-app/src/test/resources/application-test.yml
git commit -m "chore(admin): dev/test key-envelope master-key 를 0키→무작위 공개상수 (위생)"
```

---

## Task 3 — sample-rp 검증 응답 rejectedValue 제거 (`sec-samplerp-validation-rejected-value-echo`)

**Files:**
- Modify: `sample-rp/src/main/java/com/crosscert/passkey/samplerp/common/exception/GlobalExceptionHandler.java:41`

거부된 입력값 echo를 core 핸들러와 동일하게 null로. (core `GlobalExceptionHandler.java:40`이 `new FieldError(fe.getField(), null, fe.getDefaultMessage())` 패턴.)

- [ ] **Step 1: rejectedValue → null**

현재:
```java
        List<FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getRejectedValue(), fe.getDefaultMessage()))
                .toList();
```
교체:
```java
        List<FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                // rejectedValue 를 echo 하지 않는다(core GlobalExceptionHandler 와 동일) —
                // 필드명 + 메시지로 충분하고, 입력값 반사를 피한다.
                .map(fe -> new FieldError(fe.getField(), null, fe.getDefaultMessage()))
                .toList();
```

- [ ] **Step 2: 컴파일**

```
./gradlew :sample-rp:compileJava -q
```
기대: EXIT 0. **불변식**: sample-rp 데모 응답 형태 유지(FieldError 구조 동일, rejectedValue 필드만 null). 필드명+메시지로 검증 피드백 충분.

- [ ] **Step 3: commit**

```bash
git add sample-rp/src/main/java/com/crosscert/passkey/samplerp/common/exception/GlobalExceptionHandler.java
git commit -m "fix(sample-rp): 검증 응답에서 거부 입력값 echo 제거 (core 와 정합)"
```

---

## Task 4 — gradle-wrapper SHA256 핀 (`qual-gradle-wrapper-no-sha-pin`)

**Files:**
- Modify: `gradle/wrapper/gradle-wrapper.properties`

배포본 무결성 핀 추가. **잘못된 체크섬은 빌드를 깨므로 공식 값을 정확히 확인.**

- [ ] **Step 1: Gradle 8.10 공식 체크섬 확인**

Gradle 공식 체크섬 페이지(`https://gradle.org/release-checksums/`)에서 **gradle-8.10-bin.zip의 SHA-256**을 확인한다. WebFetch로 해당 페이지를 받아 `8.10` 행의 `-bin.zip` 체크섬을 추출한다. (배포 URL이 `gradle-8.10-bin.zip`이므로 `-bin.zip` 체크섬을 써야 함 — `-all.zip` 아님.)

검증 대안: 로컬에 받은 배포본이 있으면 `shasum -a 256 ~/.gradle/wrapper/dists/gradle-8.10-bin/*/gradle-8.10-bin.zip`로 대조.

- [ ] **Step 2: distributionSha256Sum 추가**

`gradle/wrapper/gradle-wrapper.properties`의 `distributionUrl` 줄 다음에 추가:
```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.10-bin.zip
distributionSha256Sum=<Step1 에서 확인한 공식 SHA-256>
```

- [ ] **Step 3: 검증 — wrapper 가 핀 후에도 동작**

```
./gradlew --version
```
기대: Gradle 8.10 정보 출력, 체크섬 불일치 에러 없음. (체크섬이 틀리면 "Verification of Gradle distribution failed" → Step 1 재확인.) **불변식**: 빌드 동작 불변, 무결성 검증만 추가.

- [ ] **Step 4: commit**

```bash
git add gradle/wrapper/gradle-wrapper.properties
git commit -m "chore(build): gradle-wrapper distributionSha256Sum 핀 (공급망 무결성)"
```

---

## Task 5 — admin-ui API 패턴 통일 (`cq-inconsistent-api-pattern`, 가치 낮음 — 생략 가능)

**Files:**
- Modify: `admin-ui/src/pages/LoginPage.tsx:54`

`LoginPage`의 `/admin/api/me` 직접 호출을 기존 `getMe()` 헬퍼로 통일. **이 Task는 가치가 낮으므로 시간이 부족하면 생략하고 followups에 기록.**

- [ ] **Step 1: getMe import 확인 + 교체**

`admin-ui/src/api/client.ts`에 `export const getMe = () => api.get<Me>('/admin/api/me');`가 있는지 Read로 확인. 있으면 `LoginPage.tsx`에서:
```tsx
      const me = await api.get<Me>('/admin/api/me');
```
를:
```tsx
      const me = await getMe();
```
로 교체하고, import에 `getMe` 추가(`import { api } from '@/api/client';` → `import { api, getMe } from '@/api/client';`). `/admin/api/profile` 직접 호출(line 22)은 전용 헬퍼가 없으므로 그대로 둔다(YAGNI — profile 헬퍼 신설은 범위 밖).

- [ ] **Step 2: 검증**

```
cd admin-ui && npx tsc -b && npm test
```
기대: tsc EXIT 0, vitest 22 green. **불변식**: `getMe()`는 `api.get<Me>('/admin/api/me')`와 동일 호출 → 로그인 동작·응답 불변.

- [ ] **Step 3: commit**

```bash
git add admin-ui/src/pages/LoginPage.tsx
git commit -m "refactor(admin-ui): LoginPage 의 /me 호출을 getMe() 헬퍼로 통일"
```

---

## Self-Review

**Spec coverage (QW-6 5 findings):**
- `sec-idtoken-alg-not-pinned` → Task 1 ✓
- `sec-dev-master-key-all-zero` → Task 2 ✓
- `sec-samplerp-validation-rejected-value-echo` → Task 3 ✓
- `qual-gradle-wrapper-no-sha-pin` → Task 4 ✓
- `cq-inconsistent-api-pattern` → Task 5 ✓ (생략 허용 명시)

**Placeholder scan:** Task 1 테스트 본문은 ContractIT 헬퍼 의존이라 "Read 후 채움"으로 명시(불가피 — 토큰 생성 헬퍼가 ContractIT에 있음). Task 2/4는 런타임 생성값(openssl/공식 체크섬)이라 명령으로 생성 지정. 나머지는 실제 코드.

**Type/일관성:** core 핸들러 `FieldError(field, null, message)` 패턴(Task 3)이 실제 core 코드와 일치(`GlobalExceptionHandler.java:40` 확인됨). `JWSAlgorithm.RS256`(Task 1)은 nimbus-jose-jwt API. `getMe()`(Task 5)는 client.ts export 확인 Step 포함.

**불변식:** 5 Task 모두 정상 동작/응답/UI 불변 — alg 핀(정상 RS256 통과), dev 키(32바이트 길이 동일), sample-rp(필드 구조 동일 rejectedValue만 null), gradle(빌드 동작 동일), getMe(동일 호출). 각 Task에 불변식 검증 Step 포함.
