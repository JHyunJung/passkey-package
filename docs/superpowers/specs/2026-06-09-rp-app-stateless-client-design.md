# rp-app 무상태 외부 클라이언트 연동 설계

**작성일**: 2026-06-09
**목표**: rp-app 의 WebAuthn begin→finish 흐름을 세션 의존에서 **무상태 토큰 릴레이**로 전환해, 네이티브 앱과 cross-origin 웹 SPA 가 동일한 `/passkey/**` API 를 쓸 수 있게 한다. 그 결과 CSRF 보호가 불필요해진다.

## 배경 / 문제

현재 rp-app(`/passkey/**`)은 register/authenticate 를 begin→finish 2단계로 처리하며, begin 이 passkey-app 의 단명 토큰(registrationToken/authenticationToken)을 **HttpSession 에 저장**하고 finish 가 세션에서 꺼낸다. 로그인 성공 시 `RpAppUser` 를 세션(`SessionKeys.USER`)에 저장한다. CSRF 는 `CookieCsrfTokenRepository.withHttpOnlyFalse()` 로 보호하고, 서버 렌더 웹의 `helpers.js` 가 메타태그/쿠키에서 토큰을 읽어 `X-XSRF-TOKEN` 헤더로 보낸다.

이 구조는 **rp-app 과 클라이언트가 같은 origin·같은 브라우저 세션**일 때만 동작한다. 외부 클라이언트는 이 흐름을 못 쓴다:
- **네이티브 앱**: 쿠키·세션 저장이 없거나 번거로워 begin↔finish 사이 세션이 안 이어진다.
- **cross-origin 웹 SPA**: cross-site 라 `SameSite` 때문에 세션 쿠키가 기본적으로 안 실려 begin↔finish 가 끊긴다.

"CSRF 토큰 발급 엔드포인트를 추가하면 되나?"가 출발 질문이었으나, 적대적 검증 결과 그것은 표면적 증상이고 **진짜 원인은 begin↔finish 상태를 세션 쿠키로 묶은 것**이다. 무상태로 전환하면 보호할 세션 상태 자체가 사라져 CSRF 가 불필요해진다.

## 적대적 검증 요약 (3개 독립 리뷰 + OWASP/WebAuthn 교차확인)

**확인된 안전 사실** (passkey-app 측, 코드 확인):
- begin 토큰 = `SecureRandom` 32바이트(256bit) base64url (`ChallengeIssuer`) — 예측·무차별 불가.
- `ChallengeStore`: Redis, TTL 5분, `takeXxx` 가 `getAndDelete`(GETDEL, 원자적 일회성) — replay·동시요청 1회만 성공.
- 토큰값 바인딩: tenantId, challenge(32B), userHandle, issuedAt (reg 는 displayName/username 추가).
- finish 서명검증의 origin 은 **서버측 `tenant.getAllowedOriginValues()`(DB)** 로 결정 — 클라이언트가 못 속임. origins 비면 거부.
- reg↔auth 토큰 교차사용 불가(Redis prefix `challenge:reg:` vs `challenge:auth:` 분리). cross-tenant 주입 차단(challenge tenant ↔ API-key tenant 강제).
- 진짜 인증은 finish 의 **사용자 개인키 서명**(challenge/origin/rpId 바인딩). begin 은 공개 challenge 발급일 뿐 — **begin 에 검증값이 없어도 안전**하다.
- CSRF 제거: 세션 쿠키를 진짜로 안 쓰면 정당(OWASP — 비-쿠키 헤더 토큰이면 CSRF 토큰 불필요; SameSite 는 defense-in-depth). 단 "XSS 는 모든 CSRF 완화책을 무력화한다"는 경고 동반.

**검증이 드러낸 신규 위험과 본 설계의 대응:**
- **P0-1 "절반만 무상태" 함정**: 현 코드는 finish 성공 시 `s.setAttribute(USER)` 로 서버 세션을 만든다. begin/finish 만 무상태화하고 이 세션을 남긴 채 CSRF 만 끄면 진짜 CSRF 구멍이 된다. → **로그인 세션까지 완전히 제거**(아래 §3).
- **P0-2 id-token 을 클라이언트 bearer 로 승격**: id-token 은 nonce/azp/jti 가 없어 같은 tenant 내 다른 서비스로 replay 가능하고, 15분 TTL 동안 무한 재사용·XSS 유출(HttpOnly 쿠키 대비 후퇴) 위험. → **id-token 을 클라이언트에 노출하지 않는다**(rp-app 내부에서만 검증·소비; 아래 §4). 근본 원인("장기 bearer 를 클라이언트에 쥐여줌")을 제거하므로 nonce/jti 땜질이 불필요하고 passkey-app 무수정.
- **P1 CORS reflected-origin**: 게으른 CORS 가 요청 Origin 을 반사하면 모든 사이트가 응답 본문을 읽는다. → **정확한 화이트리스트, reflected 금지, Allow-Credentials off**(아래 §3).
- **P1 무상태 begin 남용**: 세션이 없어 challenge 발급(Redis 적재) 남용을 막을 per-client 상관관계가 사라진다. rp-app 자체 rate limit 없음. → 데모 범위 수용 + 프로덕션 경고 문서화.

## 범위

**대상**: rp-app 만 수정. passkey-app(SaaS 백엔드)·SDK·core 는 **무수정**. passkey-app 은 이미 정석 무상태이고 토큰 안전성을 보장한다.

**범위 밖**:
- passkey-app `IdTokenIssuer` 에 nonce/jti/azp 추가 (id-token 을 클라이언트에 노출하지 않으므로 불필요).
- rp-app 자체 rate limit / 봇 방어 구현 (데모 범위 — 문서 경고로 대체).
- same-origin 웹용 BFF 패턴 구현 (문서에 대안으로 안내만).

## 아키텍처

### §1 무상태 begin/finish 흐름

```
register/begin   → 응답 {options, registrationToken}        (세션 저장 X)
register/finish  ← 요청 body {publicKeyCredential, registrationToken}
                 → 응답 {등록 결과}                          (세션 저장 X)

authenticate/begin  → 응답 {options, authenticationToken}    (세션 저장 X)
authenticate/finish ← 요청 body {publicKeyCredential, authenticationToken}
                    → rp-app 내부에서 id-token 검증·소비(클라이언트 노출 X)
                    → 응답 {rp-app 자체 인증 결과}            (세션 저장 X)
```

클라이언트가 begin↔finish 사이 토큰을 들고 있는다. rp-app 은 토큰을 검증 없이 패스스루(중계)한다 — 토큰의 단명·일회성·바인딩은 passkey-app 의 `ChallengeStore` 가 보장한다.

### §2 DTO / 컨트롤러 변경 (rp-app)

**DTO** (`rp-app/.../web/dto/`):
- `RegisterOptionsResp(JsonNode publicKeyCredentialCreationOptions, String registrationToken)` — 토큰 필드 추가.
- `LoginOptionsResp(JsonNode publicKeyCredentialRequestOptions, String authenticationToken)` — 토큰 필드 추가.
- `RegisterCompleteReq(@NotNull JsonNode publicKeyCredential, @NotBlank String registrationToken)` — 토큰 필드 추가.
- `LoginCompleteReq(@NotNull JsonNode publicKeyCredential, @NotBlank String authenticationToken)` — 토큰 필드 추가.

**컨트롤러** (`WebAuthnController`):
- 모든 `s.setAttribute(...)` / `s.getAttribute(...)` / `s.removeAttribute(...)` 제거. `HttpSession s` 파라미터 제거.
- `register/begin`: SDK `registrationStart` 결과의 `registrationToken()` 을 `RegisterOptionsResp` 에 담아 반환.
- `register/finish`: `req.registrationToken()` 을 SDK `registrationFinish` 로 패스스루. 토큰이 null/blank 면 `@Valid` 가 400.
- `authenticate/begin`: `authenticationToken()` 을 `LoginOptionsResp` 에 담아 반환.
- `authenticate/finish`: `req.authenticationToken()` 패스스루 → `fin.idToken()` 을 **rp-app 내부에서** `verifyIdToken` + iss/aud/sub 검증(기존 로직 유지) → 성공 시 rp-app 자체 결과 반환(§4). id-token 원본은 응답에 싣지 않음.

### §3 로그인 세션 제거 + CSRF 제거 (P0-1)

- `authenticate/finish` 의 `s.setAttribute(SessionKeys.USER, user)` **제거**. 로그인 결과를 세션 쿠키에 저장하지 않는다.
- `PageController`(`/` 가 세션의 USER 를 읽음), `/logout`, `helpers.js` 의 세션 의존 부분을 무상태 흐름에 맞게 정리. rp-app 데모 웹은 finish 응답 결과를 **클라이언트 측(메모리)** 에서 다룬다(데모도 무상태 패턴을 시연).
- `WebSecurityConfig`: `CookieCsrfTokenRepository` 제거하고 `csrf(c -> c.disable())`. 세션 쿠키가 어디에도 안 쓰이므로 정당.
- **불변식**: 어떤 토큰(begin 토큰·rp-app 결과)도 **쿠키에 담지 않는다**. 담으면 ambient credential 부활 = CSRF 재무장.

**CORS** (`WebSecurityConfig` 또는 `CorsConfigurationSource`):
- `/passkey/**` 에 CORS 허용. `allowedOrigins` 는 설정값 `rp.cors.allowed-origins`(정확한 origin 목록)만. **`allowedOriginPatterns` 의 `*` / reflected-origin 금지.**
- `allowCredentials = false` (쿠키 미사용).
- `allowedMethods = POST, OPTIONS`, `allowedHeaders = Content-Type`.
- 주석으로 "이 목록은 passkey-app tenant 의 allowed-origins 와 일치시킬 것(드리프트 방지)" 명기.

### §4 id-token 비노출 (P0-2, A 방식)

- `authenticate/finish` 는 id-token 을 rp-app 내부에서만 검증(iss/aud/sub)하고 **소비 후 폐기**. 응답 body 에 id-token/JWT 를 싣지 않는다.
- 성공 응답: rp-app 자체 결과 `LoginResultResp(boolean authenticated, String userHandle, String displayName)` (신규 DTO). 클라이언트는 이걸로 "인증됨"을 알고 자기 UX 를 진행한다. (장기 세션이 필요한 프로덕션 RP 는 여기서 자체 세션/토큰을 발급하면 됨 — 데모는 결과만 반환.)
- 이로써 cross-service id-token replay·15분 무한재사용·XSS 유출 표면이 **구조적으로 제거**된다(클라이언트가 id-token 을 받지 않으므로).

## 에러 처리

- finish 토큰 누락/blank → `@Valid` 400 (Bean Validation).
- finish 토큰 만료/이미 소비 → passkey-app 이 "token missing or expired" 반환 → rp-app 은 기존 `PENDING_REG_MISSING`/`PENDING_AUTH_MISSING` ErrorCode 재활용하되 메시지를 "토큰 누락 또는 만료"로 조정(세션이 아니라 body 토큰 기준). HTTP 400/401 매핑 유지.
- id-token iss/aud/sub 검증 실패 → 기존 `PASSKEY_ID_TOKEN`(P004) 그대로.
- CORS 위반 → 브라우저 preflight 에서 차단(서버가 Allow-Origin 미발급). 별도 200 응답 없음.

## 테스트

- **무상태 흐름(슬라이스/단위)**: begin 응답에 토큰 포함; finish 가 body 토큰을 SDK 로 패스스루; 컨트롤러가 `HttpSession` 을 주입받지 않고 `setAttribute` 호출이 0 임을 검증.
- **id-token 비노출 회귀 가드**: `authenticate/finish` 응답 body 에 `idToken`·JWT 패턴(`eyJ`)이 **없음**을 단언.
- **CSRF/쿠키 부활 가드**: 응답에 `Set-Cookie: XSRF-TOKEN` 및 세션 쿠키가 **없음**을 단언.
- **CORS**: 화이트리스트 origin 은 `Access-Control-Allow-Origin` 발급·통과; 화이트리스트 밖(reflected 시도) origin 은 Allow-Origin **미발급**; `Access-Control-Allow-Credentials` 가 `true` 가 **아님**을 단언.
- **에러**: finish 토큰 누락 → 400; 만료 토큰 → 적절 ErrorCode.

## 레퍼런스 문서 (rp-app 은 고객사 예제)

`docs/` 에 "외부 클라이언트(네이티브/cross-origin 웹) 연동 가이드" 추가:
- 무상태 begin→finish 시퀀스 다이어그램, 토큰을 **POST body 로만** 운반(URL 쿼리/프래그먼트/딥링크 쿼리 금지 — Referer/로그/history 유출 방지).
- CORS 정확한 화이트리스트 설정법, reflected-origin 금지 경고.
- **웹 SPA 는 토큰/결과를 localStorage 금지(메모리 한정)** — XSS 유출·지속성 차단.
- 프로덕션 RP 는 begin 남용 방지를 위해 **앞단 게이트웨이/per-client rate limit 필요**(rp-app 자체엔 없음). passkey-app 의 공유 키·공유 IP rate limit 만으로는 부족함을 명시.
- **대안 안내**: same-origin 웹 전용이면 기존 세션+CSRF(또는 BFF — 서버가 토큰 보관, 브라우저엔 HttpOnly 쿠키)가 더 안전. 무상태 릴레이는 네이티브/cross-origin 요구가 있을 때의 패턴임을 명확히.

## 범위 밖 (재확인)

- passkey-app/SDK/core 수정.
- nonce/jti/azp id-token 클레임 추가.
- rp-app rate limit·BFF 구현.
- prod 세션/토큰 발급 정책(데모는 인증 결과 반환까지).
