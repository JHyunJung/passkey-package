# Passkey RP SDK (sdk-java)

Passkey2 RP(Relying Party) API 를 호출하는 순수 Java 클라이언트 라이브러리.
등록/인증 4종 + ID Token 검증을 얇게 감싼다.

## 1. 개요

- **무엇을 하나:** RP 서버가 Passkey2 백엔드의 `/api/v1/rp/*` 엔드포인트(등록 start/finish,
  인증 start/finish)를 호출하고, 발급된 ID Token(RS256 JWT)을 JWKS 로 검증한다.
- **요구 사항:** Java 17+, Spring Web(`RestClient`) 런타임. (transitive 로 spring-web,
  jackson-databind/jsr310, nimbus-jose-jwt, slf4j-api 를 끌어온다.)
- **순수 Java:** Kotlin 런타임 의존성 없음.

## 2. 설치

Maven local 또는 사내 레포에 publish 후 좌표로 의존:

```kotlin
// build.gradle.kts
implementation("com.crosscert.passkey:sdk-java:0.0.1-SNAPSHOT")
```

로컬 publish:

```bash
./gradlew :sdk-java:publishToMavenLocal
```

## 3. 빠른 시작

```java
import com.crosscert.passkey.sdk.PasskeyClient;
import com.crosscert.passkey.sdk.PasskeyClientConfig;
import java.net.URI;

PasskeyClient client = PasskeyClient.of(
    PasskeyClientConfig.builder(
        URI.create("https://passkey.example.com"),
        () -> System.getenv("PASSKEY_API_KEY")   // Supplier<String>: 매 요청마다 호출됨
    ).build());
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

`verifyIdToken` 은 ① alg=RS256 핀(alg-confusion/none/HS\* 다운그레이드 거부) ② JWKS(`/.well-known/jwks.json`,
TTL 캐시) 로 서명 검증 ③ exp 만료 검사를 수행한다. 실패 시 `PasskeyIdTokenException`.

## 7. 에러 처리

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

## 8. 관측성

- **Trace 전파:** `traceIdProvider`(기본 `MDC.get("traceId")`)가 반환한 값이 `X-Trace-Id` 헤더로
  전파되어 백엔드 로그와 한 trace 로 묶인다. MDC 키 상수: `PasskeyClientConfig.MDC_TRACE_ID_KEY`.
- **로그 마스킹:** DEBUG 로그에서 `idToken`, `clientDataJSON`/`attestationObject`/`authenticatorData`/`signature`,
  `X-API-Key` secret 꼬리는 자동 마스킹된다. 운영 환경은 `logging.level` 을 INFO 이하로 둘 것.

## 9. 참조 통합 예제

`rp-app` 모듈이 이 SDK 의 레퍼런스 소비자다:
- `rp-app/.../config/PasskeyClientConfiguration.kt` — Spring `@Bean` 으로 `PasskeyClient` 구성
  (동적 API Key Supplier 핫리로드 포함).
- `rp-app/.../web/WebAuthnController.kt` — 등록/인증 4종 + ID Token 검증 호출 흐름.
