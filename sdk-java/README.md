# Passkey RP SDK (sdk-java)

Passkey2 RP(Relying Party) API 를 호출하는 순수 Java 클라이언트 라이브러리.
등록/인증 4종 호출 + ID Token 검증(서명·exp·iss·aud) + 무상태 등록 릴레이 코덱을 제공한다.

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

## 2. 설치

Maven local 또는 사내 레포에 publish 후 좌표로 의존:

```kotlin
// build.gradle.kts
implementation("com.crosscert.passkey:sdk-java:1.0.0")
```

로컬 publish:

```bash
./gradlew :sdk-java:publishToMavenLocal
```

## 3. 빠른 시작

```java
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

`PasskeyClientConfig.defaults(baseUrl, apiKeySupplier)` 는 모든 선택값에 기본값을 적용한
지름길이다(= `builder(...).build()`).

## 4. 설정 레퍼런스

`PasskeyClientConfig.builder(baseUrl, apiKeySupplier)` — 필수 2개는 빌더 인자로 강제.

| 옵션 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| baseUrl (필수) | `URI` | — | Passkey2 백엔드 베이스 URL |
| apiKeySupplier (필수) | `Supplier<String>` | — | **매 요청마다** 호출되는 API Key 소스 |
| connectTimeout | `Duration` | 3s | 연결 타임아웃 |
| readTimeout | `Duration` | 10s | 응답 읽기 타임아웃 |
| jwksCacheTtl | `Duration` | 5m | JWKS 캐시 TTL |
| clock | `Clock` | systemUTC | (테스트용) 시계 |
| traceIdProvider | `Supplier<String>` | `MDC.get("traceId")` | X-Trace-Id 전파 소스 |

**동적 API Key:** `apiKeySupplier` 는 부팅 시 1회가 아니라 **매 요청 시점**에 호출된다. 따라서
Supplier 뒤편(파일/시크릿 매니저)에서 키를 교체하면 재기동 없이 다음 요청부터 반영된다. 반환값이
null/blank 면 그 요청은 `PasskeyConfigurationException` 으로 fail-fast.

**RegistrationRelayCodec 생성자 인자** — `new RegistrationRelayCodec(secret, ttl, clock)`:

| 인자 | 타입 | 설명 |
|---|---|---|
| secret (필수) | `byte[]` | HMAC 키 raw bytes. 강한 키 주입(출처·보호는 RP 책임). null 금지(fail-fast). |
| ttl (필수) | `Duration` | 릴레이 토큰 만료. passkey-app challenge 만료(기본 5분)에 맞춘다. |
| clock (필수) | `Clock` | 만료 기준 시계. 운영은 `Clock.systemUTC()`. |

## 5. API 사용

등록(2-step):

```java
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

인증(2-step):

```java
AuthenticationStartResponse aStart = client.authenticationStart(
    new AuthenticationStartRequest(null)); // userHandle 생략 가능(discoverable)
AuthenticationFinishResponse aFin = client.authenticationFinish(
    new AuthenticationFinishRequest(aStart.authenticationToken(), publicKeyCredentialJson));
String idToken = aFin.idToken(); // 다음 섹션에서 검증
```

## 6. ID Token 검증

```java
IdTokenClaims claims = client.verifyIdToken(idToken);
String subject = claims.sub();   // 불투명 userHandle
String issuer  = claims.iss();
List<String> amr = claims.amr();
```

`verifyIdToken(idToken)` 은 ① alg=RS256 핀(alg-confusion/none/HS\* 다운그레이드 거부) ② JWKS(`/.well-known/jwks.json`,
TTL 캐시) 로 서명 검증 ③ exp 만료 검사를 수행한다. 실패 시 `PasskeyIdTokenException`.

**시맨틱 검증까지 한 번에(권장):** iss/aud 까지 검증하려면 3-인자 오버로드를 쓴다. RP 가 직접 iss/aud 를
비교하다 실수하는 것을 막아준다.

```java
// expectedIssuer = "<issuer-base>/<tenantId>", expectedAudience = "<tenantId>"
IdTokenClaims claims = client.verifyIdToken(
        idToken, issuerBase + "/" + tenantId, tenantId);
```

위 ①②③ 에 더해 ④ iss 가 `<issuerBase>/<tenantId>` 와 일치(issuerBase prefix 정확 일치 + tenant 비교)
⑤ aud 가 tenantId 와 일치를 검증한다. tenantId 표기는 hex32 ↔ 대시 UUID 가 동치로 정규화된다.
`expectedIssuer` 가 tenant segment 없이 들어오면(오설정) fail-closed 로 거부한다. `sub` 는 검증하지 않고
claims 에 담아 반환하므로, RP 가 자사 사용자 저장소 조회 키로 쓴다. 실패 시 `PasskeyIdTokenException`.

## 7. 등록 릴레이 토큰 (RegistrationRelayCodec)

무상태 RP 는 등록 begin 에서 발급한 userHandle 을 finish 까지 서버 세션 없이 이어야 한다.
`RegistrationRelayCodec` 은 `{registrationToken, userHandle, username, displayName, exp}` 를 HMAC-SHA256
으로 서명한 불투명 토큰을 만들고 검증한다 — 클라이언트가 userHandle 을 변조하면 서명이 깨져 거부된다.
서명 비교는 상수시간(`MessageDigest.isEqual`)이다.

```java
RegistrationRelayCodec codec = new RegistrationRelayCodec(
        relaySecret.getBytes(StandardCharsets.UTF_8), Duration.ofMinutes(5), Clock.systemUTC());

// begin: 서명 토큰을 만들어 브라우저로 내려보냄
String relayToken = codec.encode(registrationToken, userHandle, username, displayName);

// finish: 브라우저가 돌려준 토큰을 검증·복원 (변조/만료/형식오류 시 IllegalArgumentException)
RegistrationRelayCodec.RegistrationRelay r = codec.decode(relayToken);
String uh = r.userHandle();
```

secret 은 운영에서 반드시 강한 키로 주입한다(키의 출처·보호는 RP 책임). ttl 은 passkey-app 의 challenge
만료(기본 5분)에 맞춘다. `decode` 실패는 `IllegalArgumentException` 이다.

## 8. 에러 처리

| 예외 | 발생 조건 | 주요 필드 |
|---|---|---|
| `PasskeyApiException` | 4xx/5xx, 또는 HTTP 200 + `success=false` | `getHttpStatus()`, `getCode()`, `getTraceId()`, `getFieldErrors()` |
| `PasskeyAuthException` (extends 위) | HTTP 401 | (httpStatus=401) |
| `PasskeyRateLimitException` (extends 위) | HTTP 429 | `getRetryAfterSeconds()` |
| `PasskeyConfigurationException` | API Key Supplier 가 null/blank 반환 (요청 전 fail-fast) | message |
| `PasskeyIdTokenException` | ID Token 검증 실패(alg/서명/만료/파싱) | message |

envelope 파싱 실패나 비-envelope 에러 본문은 code `C999` 로 정규화된다.

```java
try {
    client.registrationStart(req);
} catch (PasskeyRateLimitException e) {
    sleep(e.getRetryAfterSeconds());
} catch (PasskeyApiException e) {
    log.warn("RP API 실패 status={} code={} trace={}", e.getHttpStatus(), e.getCode(), e.getTraceId());
}
```

## 9. 관측성

- **Trace 전파:** `traceIdProvider`(기본 `MDC.get("traceId")`)가 반환한 값이 `X-Trace-Id` 헤더로
  전파되어 백엔드 로그와 한 trace 로 묶인다. MDC 키 상수: `PasskeyClientConfig.MDC_TRACE_ID_KEY`.
- **로그 마스킹:** DEBUG 로그에서 `idToken`, `clientDataJSON`/`attestationObject`/`authenticatorData`/`signature`,
  `X-API-Key` secret 꼬리는 자동 마스킹된다. 운영 환경은 `logging.level` 을 INFO 이하로 둘 것.

## 10. 참조 통합 예제

`rp-app` 모듈이 이 SDK 의 레퍼런스 소비자다:
- `rp-app/.../config/PasskeyClientConfiguration.java` — Spring `@Bean` 으로 `PasskeyClient` 와
  `RegistrationRelayCodec` 구성(동적 API Key Supplier 핫리로드 포함).
- `rp-app/.../web/WebAuthnController.java` — 등록/인증 4종 + 3-인자 `verifyIdToken`(iss/aud) 검증 호출 흐름.
