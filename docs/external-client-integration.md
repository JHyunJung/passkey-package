# 외부 클라이언트(네이티브 앱 / cross-origin 웹) 연동 가이드

rp-app 의 `/passkey/**` 는 무상태 토큰 릴레이 방식이다. 세션 쿠키를 쓰지 않으므로
네이티브 앱과 cross-origin 웹 SPA 가 동일하게 연동할 수 있다. (서버는 CSRF 토큰을
요구하지 않는다 — 쿠키 기반 인증이 없기 때문.)

> **응답 형식:** 모든 `/passkey/**` 응답은 `ApiResponse` envelope 다 — 실제 값은 `data`
> 아래에 들어간다(예: `data.publicKeyCredentialCreationOptions`, `data.regRelayToken`,
> `data.authenticationToken`, `data.authenticated`). 아래 "응답"은 그 `data` 안의 형태를 가리킨다.

## 시퀀스

### 등록
1. `POST /passkey/register/begin` `{username, displayName}`
   → 응답 `data: {publicKeyCredentialCreationOptions, regRelayToken}`
2. 클라이언트가 `regRelayToken` 을 **메모리에 보관**(localStorage/쿠키 금지).
   - regRelayToken 은 rp-app 이 HMAC 서명한 단일 불투명 토큰으로, 내부에 등록 토큰과
     userHandle·username·displayName 바인딩이 들어있다. base64url payload 라 **디코드(열람)는
     가능**하지만, HMAC 키가 없으면 **수정(위조)이 불가**하다. 민감정보를 담지 않으며 무결성만
     서명으로 보장한다.
   - 즉 클라이언트가 userHandle 을 임의로 바꿔치기할 수 없도록 rp-app 이 서명으로
     바인딩을 보장한다(begin 에서 발급한 등록 토큰 ↔ userHandle 의 결합을 서버가 강제).
3. WebAuthn `navigator.credentials.create(publicKeyCredentialCreationOptions)` 실행.
4. `POST /passkey/register/finish` `{publicKeyCredential, regRelayToken}`
   - begin 에서 받은 `regRelayToken` 을 그대로 다시 실어 보낸다.

### 인증
1. `POST /passkey/authenticate/begin` `{username?}`
   → 응답 `data: {publicKeyCredentialRequestOptions, authenticationToken}`
   - `username` 은 생략 가능하다(usernameless / discoverable credential 흐름).
2. `authenticationToken` 메모리 보관.
3. WebAuthn `navigator.credentials.get(publicKeyCredentialRequestOptions)` 실행.
4. `POST /passkey/authenticate/finish` `{publicKeyCredential, authenticationToken}`
   → 응답 `data: {authenticated, userHandle, displayName}`
   - id-token 은 반환하지 않는다 — rp-app 내부에서만 검증·소비한다. 클라이언트는 이
     결과로 "인증됨"을 알고 자기 UX 를 진행한다.

## 보안 요구사항 (반드시 지킬 것)
- **토큰은 POST body 로만.** URL 쿼리/프래그먼트/딥링크 쿼리/Referer 금지(로그·history 유출).
- **웹 SPA 는 토큰을 localStorage/sessionStorage 금지 — 메모리 변수만.** XSS 유출·지속성 차단.
- 토큰을 쿠키에 담지 말 것 — 담으면 CSRF 가 부활한다.
- (참고) regRelayToken·authenticationToken 은 단명(5분)·일회성이라 노출돼도 사용자 개인키
  서명 없이는 무력하지만, 그래도 비밀로 다룬다.

## CORS
- 서버는 `rp.cors.allowed-origins` 의 **정확한 origin 목록만** 허용(반사·와일드카드 금지).
  환경변수 `RP_CORS_ALLOWED_ORIGINS`(콤마 구분)로 주입한다.
  - 목록이 비어 있으면 cross-origin 요청은 매칭 origin 이 없어 막힌다(같은-origin 데모만 동작).
- 쿠키를 안 쓰므로 클라이언트는 `credentials:'include'` 불필요(서버 `Allow-Credentials` off).
  fetch 는 `credentials:'omit'` 또는 기본값을 쓴다.
- 이 목록은 passkey-app tenant 의 allowed-origins 와 일치시킬 것(드리프트 방지).

## 프로덕션 주의
- rp-app 자체엔 rate limit 이 없다. begin 남용(challenge 발급) 방지를 위해 **앞단 게이트웨이/
  per-client rate limit 을 둘 것.** passkey-app 의 공유 키·공유 IP rate limit 만으로는 부족하다.
- **relay 서명 키**: `RP_RELAY_SECRET` 환경변수로 강한 키를 주입할 것. 미설정 시 데모 기본값으로
  떨어지므로 운영 배포 파이프라인에서 필수 검증을 권장한다.
- **장기 세션이 필요하면**: authenticate/finish 성공 후 RP 가 자체 세션/토큰을 발급하라(데모는
  인증 결과 반환까지만 한다).

## 대안: same-origin 웹이면 BFF
cross-origin/네이티브가 아니라 **같은 origin 웹 전용**이면, 세션+CSRF(또는 BFF — 서버가 토큰을
보관하고 브라우저엔 HttpOnly 쿠키만)가 더 안전하다. 무상태 릴레이는 cross-origin/네이티브
요구가 있을 때의 패턴이다. 무상태 릴레이를 same-origin 웹에 그대로 쓰면 토큰을 JS 가 다루게 되어
XSS 노출 표면이 커진다.
