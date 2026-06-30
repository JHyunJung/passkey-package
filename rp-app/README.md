# rp-app — Passkey RP 레퍼런스 구현

자사 서비스(RP, Relying Party)에 패스키 로그인을 붙이려는 개발자를 위한 **샘플 RP 서버**입니다.
passkey-app(패스키 서버) 뒤에 두고, 브라우저↔passkey-app 사이의 중계와 ID Token 검증을 담당합니다.

## 1. 개요

- **무엇인가**: passkey-app 의 SDK(`sdk-java`)를 사용해 패스키 등록/인증을 중계하는 RP 서버 예제.
- **누가 보는가**: 자사 RP 를 구축하려는 고객사 개발자.
- **무엇을 보여주는가**: 무상태 토큰 릴레이 기반 등록/인증 2-step 흐름, ID Token(iss/aud/sub) 검증,
  네이티브 앱용 well-known(assetlinks.json / apple-app-site-association) 호스팅, 로그 비밀값 마스킹.
- **요구 사항**: Java 17, Spring Boot 3. 실행 시 passkey-app(기본 :8080)이 떠 있어야 한다.

## 2. 아키텍처

```
Browser ──► rp-app(:9090) ──(SDK, X-API-Key)──► passkey-app(:8080)
            │
            ├─ 사용자 매핑 보관(InMemoryUserStore — 데모용, 교체 대상)
            ├─ ID Token 검증(iss/aud/sub)
            └─ /.well-known/* 호스팅
```

rp-app 의 책임: SDK 호출, username↔userHandle↔credential 매핑, ID Token 검증, well-known 정적 응답.
passkey-app 에 위임: WebAuthn 의식 처리, challenge/credential 저장, ID Token 발급.

## 3. 빠른 시작

```bash
./gradlew :rp-app:bootRun --args="--spring.profiles.active=local"
```

`local` 프로필은 데모 값으로 채워져 있다(passkey-app :8080, demo-rp 테넌트).

⚠️ **issuer-base 정렬 필수.** rp-app 은 ID Token 의 `iss` 를 `<passkey.issuer-base>/<tenantId>` 로 검증한다.
따라서 passkey-app 을 rp-app 의 `passkey.issuer-base` 와 같은 값으로 띄워야 한다 — local 데모에서는
passkey-app 을 `-Dpasskey.id-token.issuer-base=http://localhost:8080` 으로 기동한다. 정렬이 어긋나면
인증 마지막 단계(`/passkey/authenticate/finish`)에서 iss mismatch(P004)로 실패한다.

자사 환경에서는 아래 설정을 주입한다:
`passkey.base-url`, `passkey.api-key`(또는 `api-key-file`), `passkey.tenant-id`, `passkey.issuer-base`
(이때도 passkey-app 의 issuer-base 와 일치시킬 것).

## 4. 요청 흐름

등록(2-step):
1. `POST /passkey/register/begin` `{ username, displayName }` → `{ publicKeyCredentialCreationOptions, regRelayToken }`
2. 브라우저가 `navigator.credentials.create(...)` 실행
3. `POST /passkey/register/finish` `{ publicKeyCredential, regRelayToken }` → 등록 결과

인증(2-step):
1. `POST /passkey/authenticate/begin` `{ username? }` → `{ publicKeyCredentialRequestOptions, authenticationToken }`
   (username 없으면 discoverable 로그인)
2. 브라우저가 `navigator.credentials.get(...)` 실행
3. `POST /passkey/authenticate/finish` `{ publicKeyCredential, authenticationToken }` → `{ authenticated, userHandle, displayName }`

**무상태 릴레이 토큰**: begin 이 돌려준 `regRelayToken`/`authenticationToken` 을 finish 에 다시 보낸다.
서버 세션 없이 두 단계를 잇고, regRelayToken 은 HMAC 서명이라 userHandle 조작을 막는다.

## 5. 고객사가 반드시 손봐야 할 곳

| 대상 | 무엇을 | 이유 |
|---|---|---|
| `user/InMemoryUserStore` | 자사 DB/영속 계층으로 교체 | 데모용 인메모리·단일 인스턴스 전제 |
| `rp.relay.secret` | 운영용 강한 키 주입 | 미설정 시 데모 키 → dev/local 외 프로필에선 RelayKeyGuard 가 기동 차단 |
| `rp.cors.allowed-origins` | 자사 웹 origin 정확 목록 | 와일드카드·반사 금지 |
| `rp-app.well-known.*` | 자사 앱 패키지/지문/App ID | 네이티브 앱 패스키 동작 조건 |
| `passkey.api-key` / `api-key-file` | 발급받은 API Key | passkey-app 인증 |
| `passkey.tenant-id` / `issuer-base` | 자사 테넌트 값 | ID Token iss/aud 검증 |

## 6. 설정 레퍼런스

`application.yml` 의 실제 키·기본값(환경변수 오버라이드 가능).

| 키 | 기본값 | 의미 |
|---|---|---|
| `passkey.base-url` | `http://localhost:8080` | passkey-app 주소 |
| `passkey.api-key` | (빈 값) | X-API-Key |
| `passkey.api-key-file` | (빈 값) | 키 파일 핫리로드 경로(설정 시 `api-key` 보다 우선) |
| `passkey.api-key-reload` | `10s` | 키 파일 mtime 폴링 주기 |
| `passkey.tenant-id` | (빈 값) | 테넌트 UUID |
| `passkey.issuer-base` | `http://localhost:8080` | ID Token iss prefix |
| `passkey.connect-timeout` | `3s` | SDK connect 타임아웃 |
| `passkey.read-timeout` | `10s` | SDK read 타임아웃 |
| `passkey.jwks-cache-ttl` | `5m` | JWKS 캐시 TTL |
| `rp.relay.secret` | (빈 값 → 데모 키 폴백) | 릴레이 HMAC 키 — 운영 필수 교체(미설정 시 데모 키가 쓰이고 운영 프로필에서 기동 차단) |
| `rp.relay.ttl` | `5m` | 릴레이 토큰 만료 |
| `rp.cors.allowed-origins` | (빈 값=비활성) | 허용 origin 목록 |
| `rp-app.user-store.file` | `./data/rp-app-users.json` | 데모 저장소 파일(교체 시 무의미) |
| `rp-app.well-known.android[].package-name` | `com.crosscert.sample.passkey` | Android 패키지명 |
| `rp-app.well-known.android[].sha256-fingerprints` | 샘플 앱 서명 지문 | Android 서명키 SHA-256 |
| `rp-app.well-known.ios.app-ids` | `LTPC88ZFE8.com.crosscert.sample.passkey` | iOS App ID |

## 7. 보안 노트

고객사가 자사 RP 에서 그대로 따라야 할 패턴:
- **ID Token 검증**: iss(=issuer-base/tenant), aud(=tenant), sub(=userHandle)를 매 인증마다 검증한다. (`WebAuthnController.loginComplete`)
- **릴레이 토큰 HMAC 바인딩**: registrationToken↔userHandle 을 서명해 조작을 막는다. 서명 비교는 상수시간(`RegRelayCodec`).
- **CORS**: 정확한 origin 목록만. 와일드카드·요청 Origin 반사 금지(`CorsProperties`).
- **로그 마스킹**: API Key·JWT·password 등을 출력 시점에 가린다(`SecretRedactor`).
- **무상태/CSRF**: 서버 세션을 두지 않으므로 STATELESS + CSRF 비활성(`WebSecurityConfig`). 토큰 릴레이로 단계를 잇는다.

## 8. SDK 연동

SDK(`PasskeyClient`) 자체의 설정·API·ID Token 검증 사용법은 **[sdk-java/README.md](../sdk-java/README.md)** 를 참고한다.
rp-app 의 `config/PasskeyClientConfiguration` 이 SDK 연동 레퍼런스 예제다.
