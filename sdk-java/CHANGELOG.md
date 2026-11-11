# sdk-java 변경 이력

형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 [SemVer](https://semver.org/lang/ko/) 를 따른다.

## [1.1.0] — 2026-11-11

1.0.0 이후 누적된 변경. 기존 공개 API 는 그대로 유지되며(제거·시그니처 변경 없음), 아래 **동작 변화**만 확인하면 된다.

### 추가
- `RegistrationRelayCodec` — Spring 비의존 HMAC 등록 relay 인코딩/검증 프리미티브.
- `verifyIdToken(jwt, expectedIssuer, expectedAudience)` 3-인자 오버로드 — 서명·exp 와 함께 iss/aud 를
  한 번에 검증하고 UUID 표기를 정규화한다. 기존 1-인자 `verifyIdToken(jwt)` 는 그대로다.

### 보안
- ID Token 의 `nbf`(not-before)·`iat`(issued-at) 검증 추가 — 아직 유효하지 않거나 비현실적 미래에 발급된 토큰을 거부한다.
- `expectedIssuer` 에 테넌트 경로가 없으면 거부(fail-closed) — 스킴만 남은 issuer 로 검증이 통과하던 문제.
- JWKS 장애 시 직전 키 사용(stale)을 `TTL + 10분` 이내로 제한 — 폐기된 키를 무기한 신뢰하지 않는다.
- 토큰의 `kid` 가 캐시에 없으면 JWKS 를 강제로 다시 가져온다(쿨다운·단일 요청) — 키 회전 직후 유효 토큰 거부와
  폐기 키 지연 신뢰를 함께 해소.
- `amr` 클레임의 비-String 원소를 문자열로 정규화 — 나중에 `ClassCastException` 이 나던 문제.

### 수정
- **Spring Boot 4 (Spring 7 · Jackson 3) RP 호환** — 요청 바디를 SDK 자체 Jackson 2 매퍼로 직렬화하고,
  Spring 7 이 호출하는 3-인자 `handleError` 로 4xx/5xx 를 `PasskeyApiException` 계열로 매핑한다.
  (Spring 7 에서는 오류 응답이 예외로 바뀌지 않던 문제.)
- `Retry-After` 가 HTTP-date 형식이면 429 가 `PasskeyRateLimitException` 대신 `NumberFormatException`(→ RP 500)이
  되던 문제.
- JWKS 조회에 connect/read timeout 적용(`PasskeyClientConfig` 의 값) — 응답 없는 엔드포인트에서 검증 스레드가
  무기한 멈추던 문제.
- JWKS 갱신을 단일 요청(single-flight)으로 묶고 실패 시 백오프 — 만료 시점의 동시 재조회 폭주 차단.

### 동작 변화 (업그레이드 시 확인)
- 요청이 항상 `Content-Type: application/json` + 명시적 `Content-Length` 로 나간다(이전엔 chunked 가능).
  중간 프록시가 chunked 에 의존하지 않았다면 영향 없다.
- 시계가 크게 어긋난 서버에서는 `nbf`/`iat` 검사로 토큰이 거부될 수 있다 — RP 서버 시각을 NTP 로 맞춘다.
- JWKS 서버 장애가 `TTL + 10분` 을 넘기면 ID Token 검증이 실패한다(이전엔 마지막 키로 무기한 통과).

## [1.0.0] — 2026-06-29

첫 stable 릴리스.
