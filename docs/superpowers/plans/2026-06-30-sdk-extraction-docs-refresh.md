# SDK 전환 반영 문서·주석 갱신 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** rp-app→sdk 보안 프리미티브 추출 전환에 맞춰 rp-app 소스 주석 2곳을 정밀 보정하고 sdk-java README 진입부를 새 프리미티브(relay 코덱·3-인자 verifyIdToken) 기준으로 보강한다.

**Architecture:** 전부 주석·문서 변경(코드 로직 0). TDD 사이클 대신 각 task는 "변경 → 검증(컴파일/API 시그니처 대조/grep) → 커밋"으로 구성한다. rp-app README와 SDK README §6/§7/§10은 이미 정확하므로 건드리지 않는다.

**Tech Stack:** Markdown(README), Java Javadoc 주석, Gradle(컴파일 가드), git grep(잔재 스캔).

## Global Constraints

- **동작 영향 0:** 모든 변경은 주석/문서. 코드 로직·시그니처 무변경.
- **범위 한정(사용자 확정):** rp-app=소스 주석 정밀 보정 2곳 + README 재검토(변경 없음), sdk-java=README만(SDK 소스 주석 범위 밖).
- **SDK README 유지 섹션:** §6(ID Token 검증)·§7(RegistrationRelayCodec)·§10(참조 예제)은 이미 정확 — 변경 금지.
- **삭제 심볼 잔재 0:** rp-app 코드/문서에 `RegRelayCodec`/`normalizeTenantId`/`HEX32` 가 새로 들어가면 안 됨(SDK `IdTokenVerifier` 내부 private `normalizeTenantId` 는 정상, 본 작업 대상 아님).
- **API 시그니처 정확값(README 예제가 일치해야 함):**
  - `new RegistrationRelayCodec(byte[] secret, Duration ttl, Clock clock)`
  - `String encode(String registrationToken, String userHandle, String username, String displayName)`
  - `RegistrationRelayCodec.RegistrationRelay decode(String token)` — 실패 시 `IllegalArgumentException`. accessor: `registrationToken()/userHandle()/username()/displayName()`
  - `IdTokenClaims verifyIdToken(String idToken)` 및 `verifyIdToken(String idToken, String expectedIssuer, String expectedAudience)`
- **rp-app 책임 경계 표준 문구(README line 24 기준):** "iss·aud 검증은 SDK, rp-app 은 expectedIssuer 조립·sub 조회·예외 변환(오케스트레이션) 담당."

---

### Task 1: rp-app 소스 주석 정밀 보정 (2곳)

전환으로 부정확해진 "rp-app 이 iss/aud 검증" 단정 2곳을 SDK 위임 표현으로 보정한다. 다른 주석은 이미 정확하므로 건드리지 않는다.

**Files:**
- Modify: `rp-app/src/main/java/com/crosscert/passkey/rpapp/web/WebAuthnController.java:47`
- Modify: `rp-app/src/main/java/com/crosscert/passkey/rpapp/RpAppApplication.java:9`

**Interfaces:**
- Consumes: 없음(독립 주석 변경).
- Produces: 없음.

- [ ] **Step 1: `WebAuthnController` 클래스 Javadoc 보정**

`WebAuthnController.java` 의 클래스 Javadoc 마지막 문장(현재 line 47)을 교체.

찾을 문자열(정확히 이 한 줄):
```
 * passkey-app 이 맡고, rp-app 은 SDK 호출·사용자 매핑·ID Token(iss/aud/sub) 검증을 담당한다.
```
바꿀 내용:
```
 * passkey-app 이 맡고, rp-app 은 SDK 호출·사용자 매핑·sub 조회·ID Token 검증 오케스트레이션을
 * 담당한다(서명·iss·aud 검증은 SDK 의 verifyIdToken 에 위임, 실패는 P004 로 변환).
```

- [ ] **Step 2: `RpAppApplication` 진입점 Javadoc 보정**

`RpAppApplication.java` 의 클래스 Javadoc(현재 line 9)을 교체.

찾을 문자열:
```
/** rp-app 진입점. RP 데모 서버를 기동한다(패스키 등록/인증 릴레이 + ID Token 검증 + 네이티브 앱 well-known 호스팅). */
```
바꿀 내용:
```
/** rp-app 진입점. RP 데모 서버를 기동한다(패스키 등록/인증 릴레이 + SDK 기반 ID Token 검증 + 네이티브 앱 well-known 호스팅). */
```

- [ ] **Step 3: 컴파일 가드 — 주석이 코드를 깨지 않았는지**

Run: `./gradlew :rp-app:compileJava`
Expected: `BUILD SUCCESSFUL`. (주석만 바꿨으므로 통과해야 한다. 실패 시 Javadoc 블록을 깨뜨렸는지 확인.)

- [ ] **Step 4: 낡은 단정 사라졌는지 확인**

Run: `grep -rn "iss/aud/sub" rp-app/src/main/java --include="*.java"`
Expected: 결과 없음(보정 전 line 47 의 "ID Token(iss/aud/sub) 검증" 단정이 사라졌으므로).

추가로 삭제 심볼 미유입 확인:
Run: `git grep -n "RegRelayCodec\|normalizeTenantId\|HEX32" -- rp-app`
Expected: 결과 없음.

- [ ] **Step 5: 커밋**

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/web/WebAuthnController.java \
        rp-app/src/main/java/com/crosscert/passkey/rpapp/RpAppApplication.java
git commit -m "docs(rp-app): 클래스 주석을 SDK 위임 책임 경계로 정밀 보정"
```

---

### Task 2: rp-app README 재검토 (변경 없음 확인)

Task 6(직전 추출 작업)에서 이미 갱신된 rp-app README 가 모순 없이 정확한지 재확인만 한다. 변경은 하지 않는 것이 기본이며, 명백한 불일치 발견 시에만 최소 수정한다.

**Files:**
- Review (변경 없음 기대): `rp-app/README.md`

**Interfaces:**
- Consumes: 없음.
- Produces: 없음.

- [ ] **Step 1: 책임 경계·삭제 심볼 재확인**

Run: `git grep -n "RegRelayCodec\|normalizeTenantId\|iss/aud 를 검증\|iss/aud 검증한다\|rp-app 이 검증" -- rp-app/README.md`
Expected: 결과 없음(낡은 자체검증 단정·삭제 심볼이 없어야 함).

Run: `grep -n "SDK 검증\|SDK 가 한 번에\|expectedIssuer\|sub.*조회\|RegistrationRelayCodec" rp-app/README.md`
Expected: 책임 경계가 "iss·aud=SDK, sub=rp-app 조회" 로 서술된 줄이 보임(line 20·97 부근). 이미 정확.

- [ ] **Step 2: 판정**

- 모순 없음 → 변경 없이 다음 task 로. (이 task 는 커밋을 만들지 않을 수 있다 — 그 경우 "변경 없음, 재검토 완료" 를 리뷰 보고에 명시.)
- 불일치 발견 시 → 해당 줄만 최소 수정 후:
```bash
git add rp-app/README.md
git commit -m "docs(rp-app): README 책임 경계 미세 불일치 정정"
```

---

### Task 3: sdk-java README — §1 개요 + 토큰 플로우 다이어그램

진입부에서 새 프리미티브를 안내한다. §1 한 줄 특징을 보강하고 relay 코덱 불릿을 추가하며, §1 끝에 토큰 플로우 ASCII 다이어그램을 넣는다(섹션 번호는 그대로 유지).

**Files:**
- Modify: `sdk-java/README.md:4` (서두 한 줄)
- Modify: `sdk-java/README.md:6-12` (§1 개요)

**Interfaces:**
- Consumes: 없음.
- Produces: README §1 이 relay 코덱·3-인자 검증을 언급(이후 §3/§5 와 일관).

- [ ] **Step 1: 서두 한 줄(line 4) 교체**

찾을 문자열:
```
등록/인증 4종 + ID Token 검증을 얇게 감싼다.
```
바꿀 내용:
```
등록/인증 4종 호출 + ID Token 검증(서명·exp·iss·aud) + 무상태 등록 릴레이 코덱을 제공한다.
```

- [ ] **Step 2: §1 개요 불릿 보강 + 토큰 플로우 다이어그램 삽입**

찾을 문자열(§1 개요 블록 전체, line 6-12):
```
## 1. 개요

- **무엇을 하나:** RP 서버가 Passkey2 백엔드의 `/api/v1/rp/*` 엔드포인트(등록 start/finish,
  인증 start/finish)를 호출하고, 발급된 ID Token(RS256 JWT)을 JWKS 로 검증한다.
- **요구 사항:** Java 17+, Spring Web(`RestClient`) 런타임. (transitive 로 spring-web,
  jackson-databind/jsr310, nimbus-jose-jwt, slf4j-api 를 끌어온다.)
- **순수 Java:** Kotlin 런타임 의존성 없음.
```
바꿀 내용:
```
## 1. 개요

- **무엇을 하나:** RP 서버가 Passkey2 백엔드의 `/api/v1/rp/*` 엔드포인트(등록 start/finish,
  인증 start/finish)를 호출하고, 발급된 ID Token(RS256 JWT)을 JWKS 로 검증한다.
- **무상태 릴레이:** 무상태 RP 가 등록 begin↔finish 사이 userHandle 을 서버 세션 없이 잇도록
  HMAC 서명 릴레이 토큰 코덱(`RegistrationRelayCodec`)을 제공한다.
- **ID Token 시맨틱 검증:** 서명·exp 뿐 아니라 iss/aud 까지 한 번에 검증하는 3-인자
  `verifyIdToken` 을 제공해, RP 가 직접 iss/aud 를 비교하다 실수하는 것을 막는다.
- **요구 사항:** Java 17+, Spring Web(`RestClient`) 런타임. (transitive 로 spring-web,
  jackson-databind/jsr310, nimbus-jose-jwt, slf4j-api 를 끌어온다.)
- **순수 Java:** Kotlin 런타임 의존성 없음.

### 토큰 플로우

RP 가 다루는 4개 토큰의 출처·이동:

```text
[등록]
  RP ──registrationStart()──► SDK ──► registrationToken
  RP : regRelayToken = relay.encode(registrationToken, userHandle, …)        ──► 브라우저
  브라우저 ──(navigator.credentials.create)──► regRelayToken + credential    ──► RP
  RP : registrationToken = relay.decode(regRelayToken).registrationToken()
  RP ──registrationFinish(registrationToken, credential)──► SDK ──► credentialId

[인증]
  RP ──authenticationStart()──► SDK ──► authenticationToken                  ──► 브라우저
  브라우저 ──(navigator.credentials.get)──► authenticationToken + credential ──► RP
  RP ──authenticationFinish(authenticationToken, credential)──► SDK ──► idToken
  RP : claims = verifyIdToken(idToken, issuerBase + "/" + tenantId, tenantId)
```

릴레이 코덱은 **등록에만** 쓰인다 — 인증은 `authenticationToken` 을 그대로 왕복하므로 별도 릴레이가
필요 없다.
```

- [ ] **Step 3: 마크다운 구조 가드**

Run: `grep -n "^## \|^### " sdk-java/README.md`
Expected: `## 1. 개요` 다음에 `### 토큰 플로우` 가 보이고, 그 뒤 `## 2. 설치` 부터 `## 10. 참조 통합 예제` 까지 번호가 1~10 으로 유지(새 최상위 섹션을 만들지 않았으므로). 번호 중복/누락 없음.

- [ ] **Step 4: 커밋**

```bash
git add sdk-java/README.md
git commit -m "docs(sdk): README §1 개요 보강 + 토큰 플로우 다이어그램"
```

---

### Task 4: sdk-java README — §3 빠른 시작 + §4 설정 레퍼런스

빠른 시작에 relay 코덱 초기화를 추가하고, 설정 레퍼런스에 relay ctor 인자 표를 추가한다.

**Files:**
- Modify: `sdk-java/README.md` (§3 빠른 시작, §4 설정 레퍼런스)

**Interfaces:**
- Consumes: Task 3 의 §1(relay 코덱 소개).
- Produces: §3 에 `RegistrationRelayCodec` 초기화 예제, §4 에 ctor 인자 표.

- [ ] **Step 1: §3 빠른 시작에 relay 코덱 초기화 추가**

찾을 문자열(§3 의 코드 블록과 import):
```
import com.crosscert.passkey.sdk.PasskeyClient;
import com.crosscert.passkey.sdk.PasskeyClientConfig;
import java.net.URI;

PasskeyClient client = PasskeyClient.of(
    PasskeyClientConfig.builder(
        URI.create("https://passkey.example.com"),
        () -> System.getenv("PASSKEY_API_KEY")   // Supplier<String>: 매 요청마다 호출됨
    ).build());
```
바꿀 내용:
```
import com.crosscert.passkey.sdk.PasskeyClient;
import com.crosscert.passkey.sdk.PasskeyClientConfig;
import com.crosscert.passkey.sdk.relay.RegistrationRelayCodec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;

PasskeyClient client = PasskeyClient.of(
    PasskeyClientConfig.builder(
        URI.create("https://passkey.example.com"),
        () -> System.getenv("PASSKEY_API_KEY")   // Supplier<String>: 매 요청마다 호출됨
    ).build());

// 등록 릴레이 코덱(무상태 RP 필수). secret 은 강한 키로 주입한다(출처·보호는 RP 책임).
RegistrationRelayCodec relay = new RegistrationRelayCodec(
    System.getenv("RELAY_SECRET").getBytes(StandardCharsets.UTF_8),
    Duration.ofMinutes(5), Clock.systemUTC());
```

- [ ] **Step 2: §4 설정 레퍼런스에 relay ctor 인자 표 추가**

찾을 문자열(§4 의 동적 API Key 문단 끝 — 이 문단 바로 뒤에 표를 삽입):
```
**동적 API Key:** `apiKeySupplier` 는 부팅 시 1회가 아니라 **매 요청 시점**에 호출된다. 따라서
Supplier 뒤편(파일/시크릿 매니저)에서 키를 교체하면 재기동 없이 다음 요청부터 반영된다. 반환값이
null/blank 면 그 요청은 `PasskeyConfigurationException` 으로 fail-fast.
```
바꿀 내용(원문 유지 + 아래 블록을 그 뒤에 붙임):
```
**동적 API Key:** `apiKeySupplier` 는 부팅 시 1회가 아니라 **매 요청 시점**에 호출된다. 따라서
Supplier 뒤편(파일/시크릿 매니저)에서 키를 교체하면 재기동 없이 다음 요청부터 반영된다. 반환값이
null/blank 면 그 요청은 `PasskeyConfigurationException` 으로 fail-fast.

**RegistrationRelayCodec 생성자 인자** — `new RegistrationRelayCodec(secret, ttl, clock)`:

| 인자 | 타입 | 설명 |
|---|---|---|
| secret (필수) | `byte[]` | HMAC 키 raw bytes. 강한 키 주입(출처·보호는 RP 책임). null 금지(fail-fast). |
| ttl (필수) | `Duration` | 릴레이 토큰 만료. passkey-app challenge 만료(기본 5분)에 맞춘다. |
| clock (필수) | `Clock` | 만료 기준 시계. 운영은 `Clock.systemUTC()`. |
```

- [ ] **Step 3: 마크다운 구조 가드**

Run: `grep -n "RegistrationRelayCodec\|^## " sdk-java/README.md`
Expected: §3·§4 에 `RegistrationRelayCodec` 가 등장하고, 최상위 섹션 번호 1~10 유지.

- [ ] **Step 4: 커밋**

```bash
git add sdk-java/README.md
git commit -m "docs(sdk): README §3 빠른시작 relay 코덱 + §4 ctor 인자 표"
```

---

### Task 5: sdk-java README — §5 API 사용 + §8 에러 처리

등록 예제에 relay encode/decode 흐름을 반영하고 인증 예제를 3-인자 검증으로 연결한다. 에러 표에 relay decode 예외를 추가한다.

**Files:**
- Modify: `sdk-java/README.md` (§5 API 사용, §8 에러 처리)

**Interfaces:**
- Consumes: Task 4 의 `relay` 변수(§3 에서 생성), Task 3 의 토큰 플로우.
- Produces: §5 가 relay 흐름 + 3-인자 검증으로의 다리를 보여줌.

- [ ] **Step 1: §5 등록 예제를 relay 흐름 포함으로 교체**

찾을 문자열(§5 등록 2-step 블록):
```
// 1) start
RegistrationStartResponse start = client.registrationStart(
    new RegistrationStartRequest("userHandle", "홍길동", "gildong"));
JsonNode creationOptions = start.publicKeyCredentialCreationOptions(); // 브라우저로 전달
String regToken = start.registrationToken();

// 2) (브라우저에서 navigator.credentials.create 수행 후) finish
RegistrationFinishResponse fin = client.registrationFinish(
    new RegistrationFinishRequest(regToken, publicKeyCredentialJson));
String credentialId = fin.credentialId();
```
바꿀 내용:
```
// 1) start
RegistrationStartResponse start = client.registrationStart(
    new RegistrationStartRequest("userHandle", "홍길동", "gildong"));
JsonNode creationOptions = start.publicKeyCredentialCreationOptions(); // 브라우저로 전달

// 2) begin 응답을 relay 로 서명해 브라우저로 (서버 세션 불필요)
String regRelayToken = relay.encode(
    start.registrationToken(), "userHandle", "gildong", "홍길동");
// → creationOptions 와 regRelayToken 을 브라우저로 내려보낸다.

// 3) (브라우저에서 navigator.credentials.create 수행 후) finish
//    relay 검증으로 원본 registrationToken 을 복원한다(변조/만료 시 IllegalArgumentException).
RegistrationRelayCodec.RegistrationRelay r = relay.decode(regRelayToken);
RegistrationFinishResponse fin = client.registrationFinish(
    new RegistrationFinishRequest(r.registrationToken(), publicKeyCredentialJson));
String credentialId = fin.credentialId();
```

- [ ] **Step 2: §5 인증 예제에 3-인자 검증 다리 추가**

찾을 문자열(§5 인증 2-step 블록의 마지막 줄):
```
String idToken = aFin.idToken(); // 다음 섹션에서 검증
```
바꿀 내용:
```
String idToken = aFin.idToken();
// 검증(권장): client.verifyIdToken(idToken, issuerBase + "/" + tenantId, tenantId) — §6 참조.
```

- [ ] **Step 3: §8 에러 표에 relay decode 행 추가**

찾을 문자열(§8 표의 마지막 행):
```
| `PasskeyIdTokenException` | ID Token 검증 실패(alg/서명/만료/파싱) | message |
```
바꿀 내용:
```
| `PasskeyIdTokenException` | ID Token 검증 실패(alg/서명/만료/파싱/iss/aud) | message |
| `IllegalArgumentException` | `RegistrationRelayCodec.decode()` 실패(서명 변조/만료/형식오류/불완전 payload) | message |
```

- [ ] **Step 4: API 시그니처 정확성 대조**

§5 예제가 실제 SDK 와 일치하는지 확인:
Run: `grep -rn "public String encode\|public RegistrationRelay decode\|public record RegistrationRelay" sdk-java/src/main/java/com/crosscert/passkey/sdk/relay/RegistrationRelayCodec.java`
Expected: `encode(String registrationToken, String userHandle, String username, String displayName)`, `decode(String token)`, `record RegistrationRelay(String registrationToken, String userHandle, String username, String displayName)` 가 보임 — README 예제의 `r.registrationToken()` 호출과 인자 순서가 일치.

Run: `grep -n "RegRelayCodec\|RegRelay\b" sdk-java/README.md`
Expected: 결과 없음(옛 이름 `RegRelayCodec`·`RegRelay` 가 README 예제에 유입되지 않음 — 정확한 신규 이름 `RegistrationRelayCodec`/`RegistrationRelay` 만 사용).

- [ ] **Step 5: 커밋**

```bash
git add sdk-java/README.md
git commit -m "docs(sdk): README §5 API relay 흐름·3-인자 검증 + §8 relay 예외"
```

---

### Task 6: 최종 통합 검증

전체 변경이 일관되고 잔재가 없는지 마지막으로 확인한다.

**Files:** 없음(검증 전용).

- [ ] **Step 1: rp-app 컴파일 그린**

Run: `./gradlew :rp-app:compileJava`
Expected: `BUILD SUCCESSFUL`(주석 변경이 코드를 깨지 않음).

- [ ] **Step 2: 삭제 심볼 잔재 전수 스캔**

Run: `git grep -n "RegRelayCodec" -- rp-app sdk-java/README.md`
Expected: 결과 없음(옛 이름이 어디에도 새로 들어가지 않음).

Run: `git grep -n "normalizeTenantId\|HEX32" -- rp-app`
Expected: 결과 없음.

- [ ] **Step 3: SDK README 섹션 번호 무결성**

Run: `grep -n "^## [0-9]" sdk-java/README.md`
Expected: `## 1.` ~ `## 10.` 이 순서대로 한 번씩(중복/누락 없음). §6/§7/§10 제목이 그대로 유지(변경 금지 섹션).

- [ ] **Step 4: README 예제 import 일관성(스모크)**

Run: `grep -n "import com.crosscert.passkey.sdk.relay.RegistrationRelayCodec\|StandardCharsets\|java.time.Clock\|java.time.Duration" sdk-java/README.md`
Expected: §3 빠른시작에 relay 코덱 import 4종이 모두 존재(예제 코드가 컴파일 가능한 import 세트를 갖춤).

- [ ] **Step 5: (커밋 불필요)** 검증 전용 task. 이상 발견 시 해당 Task 로 돌아가 수정 후 재검증.

---

## Self-Review

**Spec coverage:**
- §2-A WebAuthnController:47 보정 → Task 1 Step 1. ✓
- §2-B RpAppApplication:9 보정 → Task 1 Step 2. ✓
- §2 비대상(PasskeyProperties:35, line 169/172) 변경 금지 → Task 1 은 그 줄을 건드리지 않음(찾을 문자열이 line 47/9 한정). ✓
- §3 rp-app README 재검토만 → Task 2. ✓
- §4-A §1 개요 보강 → Task 3 Step 1·2. ✓
- §4-B 토큰 플로우 다이어그램(§1 끝, 번호 유지) → Task 3 Step 2. ✓
- §4-C §3 빠른시작 relay 초기화 → Task 4 Step 1. ✓
- §4-D §4 relay ctor 표 → Task 4 Step 2. ✓
- §4-E §5 relay 흐름 + 3-인자 다리 → Task 5 Step 1·2. ✓
- §4-F §8 relay decode 예외 행 → Task 5 Step 3. ✓
- §5 작업 범위표 전부 task 매핑됨. ✓
- §6 검증 전략(컴파일·시그니처 대조·grep 잔재 0) → Task 1 Step 3-4, Task 5 Step 4, Task 6. ✓
- §7 비범위(SDK 소스 주석·README 전면재서술·전체 스윕) → plan 에 미포함(준수). ✓

**Placeholder scan:** 모든 변경 step 에 찾을 문자열·바꿀 내용을 verbatim 제공. "TBD"/"적절히"/"유사" 없음. Task 2 는 의도적으로 "변경 없을 수 있음"(재검토 task) — placeholder 아니라 조건부 결과이며 grep 명령으로 판정 기준을 명시함.

**Type/이름 consistency:** README 예제 전반에서 신규 이름 `RegistrationRelayCodec`/`RegistrationRelay`/`r.registrationToken()` 사용, 옛 이름 `RegRelayCodec`/`RegRelay` 는 Task 5 Step 4·Task 6 Step 2 의 grep 가드로 유입 차단. ctor 인자 순서(secret, ttl, clock)와 encode 인자 순서(registrationToken, userHandle, username, displayName)가 §3/§4/§5 에서 일관. verifyIdToken 3-인자(idToken, issuerBase+"/"+tenantId, tenantId)가 §1 다이어그램·§5·§6 에서 일관. ✓
