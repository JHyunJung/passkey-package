# rp-app → sdk-java: 연동 필수 보안 프리미티브 추출 설계

작성일: 2026-06-30

## 1. 배경과 목표

`rp-app`은 고객사(RP)가 참고하는 **샘플 RP 서버**이고, `sdk-java`는 고객사가 자기 RP
서버에 임베드하는 **Java SDK**다. 현재 "passkey-app 연동에 본질적으로 필요하고, 직접
구현하면 보안 구멍이 나는 로직"의 일부가 `rp-app` 쪽에 흩어져 있다. 고객사가 rp-app을
복붙하지 않고 SDK만 임베드해도 이 핵심 보안 로직을 검증된 형태로 얻도록, 해당 로직을
SDK로 추출한다.

**원칙**
- **SDK** = 모든 RP가 동일하게 필요하고, 직접 짜면 보안 구멍이 나는 **순수 프리미티브**.
  Spring 비의존, 부수효과 없는 함수/클래스.
- **rp-app** = RP 서버의 기본 골격(엔드포인트 라우팅, 사용자 DB, 세션/설정 전략) —
  고객사가 자기 것으로 교체하는 샘플.

**범위 결정(브레인스토밍에서 확정)**
- SDK 범위는 "순수 프리미티브만". 오케스트레이션 파사드(UserStore 인터페이스를 받아
  begin/finish 전체를 SDK가 도는 방식)는 **하지 않는다** — SDK가 RP의 DB/세션/라우팅
  모델을 가정하게 되어 고객사 유연성을 해치기 때문.

## 2. 현재 책임 분배 (탐색·검증 결과)

### SDK가 이미 가진 것 (얇은 HTTP 클라이언트)
- 4개 ceremony 호출: `registrationStart/Finish`, `authenticationStart/Finish`
  (`PasskeyClient`, passkey-app `/api/v1/rp/*`).
- ID Token **서명** 검증: `verifyIdToken(jwt)` — RS256 pin, JWKS, exp
  (`IdTokenVerifier.verify`).
- 동적 API Key Supplier, 에러 envelope 처리, trace-id 전파.

### rp-app에만 있고 "연동 필수"인 것
1. **Relay 코덱** — `rpapp.web.relay.RegRelayCodec`. HMAC-SHA256으로
   `{registrationToken, userHandle, username, displayName, exp}`를 서명한 무상태
   relay 토큰. 등록 begin에서 만들어 브라우저로 보내고, finish에서 디코드·검증해
   rp-app 자신의 사용자 매핑(`confirmRegistration`)을 채운다.
2. **ID Token 시맨틱 검증** — `WebAuthnController.loginComplete()` 인라인
   (현행 199–220줄). iss = `issuerBase/tenantId` prefix + tenant 정규화 비교,
   aud = tenantId 정규화 비교.
3. **UUID 테넌트 정규화** — `WebAuthnController.normalizeTenantId()`
   (현행 78–91줄). hex32 ↔ 대시 UUID 호환.

### relay의 보안 성격 (코드로 확정)
passkey-app의 finish는 `RegistrationFinishRequest(registrationToken,
publicKeyCredential)` **2필드만** 받고 userHandle을 받지 않는다
(`passkey-app/.../rp/dto/RegistrationFinishRequest.java`). passkey-app은
registrationToken으로 Redis에서 start 때 저장한 원본 userHandle을 복원해
credential에 귀속시킨다(`RegistrationStartService.start` →
`RegistrationFinishService.finish:55,124`). 따라서:
- **passkey-app ↔ rp-app 간 userHandle 귀속의 무결성은 registrationToken이 보장**한다
  (rp-app이 변조 불가).
- **relay 토큰의 역할**은 begin에서 만든 userHandle을 finish 시점에 **rp-app 자신의 DB
  매핑**(`confirmRegistration`)에 무상태로 되돌려받기 위한 무결성 보호다. 서버 세션을
  쓰지 않는 한 모든 RP가 동일한 보호가 필요하므로 SDK 프리미티브로 적합하다.

### rp-app에 남는 것 (RP 고유 / 교체 대상)
`WebAuthnController`(엔드포인트 4개·오케스트레이션·sub 조회), `InMemoryUserStore`,
`WellKnown*`, 로깅/마스킹, CORS/보안 설정, `PasskeyProperties`, `RelayProperties`,
`RelayKeyGuard`.

## 3. 이동 대상 요약

| # | 대상 | 현재 위치 | SDK에서의 형태 |
|---|---|---|---|
| 1 | Relay 코덱 | `rpapp.web.relay.RegRelayCodec` (`@Component`, `RelayProperties` 의존) | `sdk.relay.RegistrationRelayCodec` — Spring 비의존 순수 클래스 |
| 2 | ID Token iss/aud 검증 | `WebAuthnController.loginComplete()` 인라인 | `verifyIdToken(jwt, expectedIssuer, expectedAudience)` 오버로드 |
| 3 | UUID 테넌트 정규화 | `WebAuthnController.normalizeTenantId()` | #2 내부로 흡수 (별도 public API로 노출 안 함) |

## 4. SDK 측 상세 설계

### 4-A. `RegistrationRelayCodec` (신규, `com.crosscert.passkey.sdk.relay`)

현행 `RegRelayCodec`에서 Spring/rp-app 의존을 벗긴 순수 클래스.

```java
package com.crosscert.passkey.sdk.relay;

public final class RegistrationRelayCodec {
    public record RegistrationRelay(
        String registrationToken, String userHandle,
        String username, String displayName) {}

    // secret: HMAC 키 raw bytes, ttl: 토큰 유효기간, clock: 테스트 가능 시계
    public RegistrationRelayCodec(byte[] secret, Duration ttl, Clock clock) { ... }

    public String encode(String registrationToken, String userHandle,
                         String username, String displayName) { ... }

    public RegistrationRelay decode(String token) { ... }
}
```

- 알고리즘은 **현행과 100% 동일**: HMAC-SHA256, `base64url(payloadJson).base64url(hmac)`
  포맷, `MessageDigest.isEqual` 상수시간 비교, exp 검사, 4필드 완전성 검사, 짧은 필드명
  (rt/uh/un/dn/exp).
- `Instant.now()` 직접 호출 대신 주입형 `Clock` 사용(SDK 표준 — `PasskeyClient`도
  `config.clock()` 사용). 만료를 결정적으로 테스트 가능.
- 내부 `ObjectMapper`는 SDK가 자체 생성(외부 주입 불필요). SDK는 이미 jackson 의존.
- **예외 정책:** `decode` 실패(서명 불일치/만료/형식오류/불완전)는 현행대로
  `IllegalArgumentException`을 유지한다. rp-app이 이미 이를 잡아
  `BusinessException(PENDING_REG_MISSING)`으로 변환하므로 호출부 변경이 최소화된다.

### 4-B. `verifyIdToken` 오버로드 (`PasskeyClient` + `IdTokenVerifier`)

```java
// 기존 — 하위호환 유지 (서명 + exp 만)
IdTokenClaims verifyIdToken(String compactJwt);

// 신규 — 서명 + exp + iss/aud 시맨틱 검증
IdTokenClaims verifyIdToken(String compactJwt, String expectedIssuer, String expectedAudience);
```

- 신규 메서드는 기존 검증(RS256 pin, JWKS, exp)을 모두 수행한 뒤 **iss/aud 시맨틱
  검증**을 추가하고, 통과한 `IdTokenClaims`를 반환한다. 실패 시 `PasskeyIdTokenException`.
- **iss 검증 — 현행 방식 보존:** rp-app이 `expectedIssuer = issuerBase + "/" + tenantId`를
  통째로 넘긴다. SDK는 이를 마지막 `/` 기준으로 issuerBase prefix와 tenant로 분해한 뒤
  (현행 `WebAuthnController`의 prefix + tenant 정규화 로직과 동일):
  - **prefix 정확 일치:** 토큰 iss가 `<issuerBase>/`로 시작해야 한다.
  - **tenant 정규화 일치:** 토큰 iss에서 prefix를 뗀 나머지(tenant)를 expectedIssuer의
    tenant 부분과 양쪽 UUID 정규화하여 비교한다.
  - 둘 중 하나라도 실패하면 `PasskeyIdTokenException`.
- **aud 검증:** `expectedAudience`(tenantId)와 토큰 aud를 UUID 정규화 비교.
- **정규화 내장:** hex32 ↔ 대시 UUID 동치 비교를 verifyIdToken 내부에서 처리한다.
  `normalizeTenantId`는 SDK 내부 유틸로 흡수하며 **public API로 노출하지 않는다**(노출
  표면 최소화 — rp-app이 직접 부를 일이 사라진다).
- **sub 미검증:** sub는 검증 대상이 아니며 `IdTokenClaims.sub()`로 그대로 반환된다.
  rp-app이 이를 자기 DB 조회 키로 쓴다.
- `expectedIssuer`/`expectedAudience`가 null/blank면 `PasskeyIdTokenException`.

## 5. rp-app 측 변경 (호출부 재배선)

### 5-A. Relay 코덱 — `@Bean`으로 SDK 코덱 생성
```java
@Bean
RegistrationRelayCodec registrationRelayCodec(RelayProperties props) {
    return new RegistrationRelayCodec(
        props.secret().getBytes(StandardCharsets.UTF_8),
        props.ttl(),
        Clock.systemUTC());
}
```
- `RelayProperties`(secret, ttl)·`RelayKeyGuard`(운영 데모키 거부)는 **rp-app에 유지** —
  "키를 어디서 읽고 어떻게 보호하는가"는 RP 인프라 책임. SDK는 raw secret만 받는다.
- 기존 `rpapp.web.relay.RegRelayCodec` 클래스 + 내부 `ObjectNodePayload`는 **삭제**.
  관련 테스트는 SDK로 이동(4-A 테스트).

### 5-B. `WebAuthnController` 변경
- 주입 타입 `RegRelayCodec` → `RegistrationRelayCodec`(SDK). `encode/decode` 호출 형태
  동일, 반환 타입 `RegRelay` → `RegistrationRelay`로 import만 교체.
- `loginComplete()`의 iss/aud 인라인 블록(현행 199–220줄, 약 22줄)을 삭제하고 대체:
```java
String expectedIssuer = props.issuerBase() + "/" + props.tenantId();
IdTokenClaims claims = passkey.verifyIdToken(
        fin.idToken(), expectedIssuer, props.tenantId());
RpAppUser user = users.findByUserHandle(claims.sub())
        .orElseThrow(() -> new BusinessException(ErrorCode.PASSKEY_ID_TOKEN, "unknown sub"));
```
- `normalizeTenantId()`(78–91줄)·`HEX32` 패턴·`Pattern`/`UUID` import 정리(삭제).
- `issuerBase == null` 가드는 유지(설정 누락 시 명확한 조기 실패 메시지).

### 5-C. 에러 매핑 유지 (현행 응답 회귀 방지)
- relay decode 실패 → `IllegalArgumentException` → 기존 catch가
  `BusinessException(PENDING_REG_MISSING)`으로 변환. **변경 없음**.
- iss/aud mismatch: SDK가 `PasskeyIdTokenException`을 던진다. `loginComplete`에서
  try-catch로 이를 **`BusinessException(PASSKEY_ID_TOKEN)`으로 변환**해 기존 HTTP
  응답·에러코드를 보존한다.

## 6. 테스트 · 검증 전략

### 6-A. SDK 테스트
- **Relay 코덱(이동·개작):** round-trip(encode→decode), 서명 변조 거부(base64url 문자
  치환이 아니라 **디코드 바이트 레벨** 변조 — `jws_tamper_test_flaky` 교훈), exp 만료
  거부(주입 Clock으로 결정적), 4필드 불완전 거부, 잘못된 서명 인코딩 거부.
- **verifyIdToken 오버로드(신규):** iss 정확 일치/불일치, aud 일치/불일치,
  hex32↔대시 정규화 동치(`7F00DEAD...` ≡ `7f00dead-...`), issuerBase prefix 불일치 거부,
  expectedIssuer/Audience null·blank 거부. 기존 1-인자 메서드 회귀 없음 확인.

### 6-B. rp-app 테스트 (회귀 보존)
- `WebAuthnController` 등록/인증 플로우가 SDK 코덱·신규 verifyIdToken으로 갈아끼운 뒤에도
  동일 결과:
  - 등록 finish의 relay 변조 → `PENDING_REG_MISSING`.
  - 로그인 iss/aud mismatch → `PASSKEY_ID_TOKEN`(5-C 변환 검증).
  - 정상 플로우 green.
- `RegRelay`→`RegistrationRelay`는 record 컴포넌트명 동일이라 accessor 깨짐 위험 낮음.
  `compileTestJava` 포함 빌드로 확인(메모리 교훈).

### 6-C. 빌드 게이트
- `./gradlew :sdk-java:test :rp-app:test` 두 모듈 그린.
- `./gradlew build` 전체는 SliceConfig/Oracle 경합으로 항상 실패하므로 머지 게이트로
  쓰지 않고, **base worktree 대조**로 회귀 여부를 확정한다(pre-existing 실패와 구분).
- 실증: rp-app 로컬 부팅 + 등록·인증 1회 왕복(dogfooding) — Docker/passkey-app 가용성에
  따라 CI 검증 대기일 수 있음.

## 7. 변경 영향 요약

| 모듈 | 추가 | 삭제 | 수정 |
|---|---|---|---|
| sdk-java | `relay.RegistrationRelayCodec`, `verifyIdToken` 오버로드, 내부 정규화 유틸 | — | `IdTokenVerifier`, `PasskeyClient` |
| rp-app | relay 코덱 `@Bean` | `web.relay.RegRelayCodec`+payload, `normalizeTenantId()`, iss/aud 인라인 블록 | `WebAuthnController`, config 클래스 |

**순 효과:** rp-app `WebAuthnController`에서 보안 검증 로직(relay HMAC, iss/aud 정규화
약 35줄)이 빠지고 "엔드포인트 + DB 매핑 + sub 조회"라는 RP 본연의 골격만 남는다. SDK는
passkey-app 연동에 필수인 보안 프리미티브를 검증된 형태로 보유한다.

## 8. 비범위 (YAGNI)
- 오케스트레이션 파사드(UserStore 인터페이스 + begin/finish 자동화) — 하지 않음.
- API Key 핫리로드(`ReloadableApiKeySupplier`)의 SDK 이동 — "파일에서 읽기"는 RP 인프라
  선택이므로 rp-app에 유지. SDK는 이미 `Supplier<String>`을 받는다.
- `WellKnown*`, 로깅/마스킹, CORS, 보안 설정 — RP 고유로 rp-app 유지.
