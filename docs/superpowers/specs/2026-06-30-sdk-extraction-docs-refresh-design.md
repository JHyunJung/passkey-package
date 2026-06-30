# SDK 전환 반영 — 문서·주석 갱신 설계

작성일: 2026-06-30

## 1. 배경과 목표

직전에 "연동 필수 보안 프리미티브"(HMAC relay 코덱·ID Token iss/aud 검증·UUID 정규화)를
rp-app → sdk-java 로 추출하는 작업을 머지했다(main `e0b9a37`). 그 전환에 맞춰 남은 문서·주석을
현실과 정렬한다.

**범위(사용자 확정):**
- **rp-app**: 소스코드 주석(정밀 보정) + 사용자 가이드(README 재검토).
- **sdk-java**: 사용자 가이드(README)만. SDK 소스 주석은 범위 밖.

**탐색으로 확인된 현황:**
- rp-app README·소스 주석은 추출 작업(Task 5/6)에서 대부분 이미 정확. 전수 grep 결과 삭제 심볼
  (`RegRelayCodec`/`normalizeTenantId`/`HEX32`) 잔재 0건.
- 단, 전환 전의 "rp-app 이 iss/aud 검증" 단정이 **두 클래스 주석에 남아 있음**(아래 §2).
- sdk-java README 는 §6(ID Token 3-인자)·§7(RegistrationRelayCodec)·§10(참조 예제)이 추출
  작업에서 갱신돼 정확하나, 진입부 §1/§3/§4/§5/§8 이 새 프리미티브를 안내하지 못함.

## 2. rp-app 소스 주석 — 정밀 보정 2곳

전환으로 부정확해진 단정만 고친다. 나머지(loginComplete 인라인 주석 line 169/172,
`PasskeyClientConfiguration` @Bean 주석, `RelayProperties`/`RelayKeyGuard`/`PasskeyProperties`
주석)는 이미 정확하므로 손대지 않는다.

### 2-A. `WebAuthnController.java:47` (클래스 Javadoc)
- **현재:** "실제 WebAuthn 동작(ceremony)과 ID Token 발급은 passkey-app 이 맡고, rp-app 은
  SDK 호출·사용자 매핑·ID Token(iss/aud/sub) 검증을 담당한다."
- **문제:** 전환 후 iss/aud 검증은 SDK 책임. rp-app 은 expectedIssuer 조립·sub 조회·예외 변환만.
- **수정 방향:** "rp-app 은 SDK 호출·사용자 매핑·sub 조회·ID Token 검증 오케스트레이션(서명·iss·aud
  검증은 SDK 의 `verifyIdToken` 에 위임)을 담당한다." (README line 24 표현과 정렬)

### 2-B. `RpAppApplication.java:9` (진입점 Javadoc)
- **현재:** "RP 데모 서버를 기동한다(패스키 등록/인증 릴레이 + ID Token 검증 + 네이티브 앱
  well-known 호스팅)."
- **문제:** "ID Token 검증"이 rp-app 자체 검증처럼 읽힘. 릴레이/검증 모두 이제 SDK 프리미티브 사용.
- **수정 방향:** "...(패스키 등록/인증 릴레이 + SDK 기반 ID Token 검증 + 네이티브 앱 well-known
  호스팅)." — "SDK 기반"을 붙여 위임을 명시. 과한 재서술은 하지 않는다.

**비대상(이미 정확, 변경 금지):** `PasskeyProperties.java:35`("ID Token 검증용 JWKS 캐시" — SDK 가
JWKS 로 검증하므로 캐시 설정은 정확), line 169/172 인라인 주석(SDK 위임을 이미 정확히 서술).

## 3. rp-app README — 재검토만 (변경 없음)

Task 6 에서 책임 경계(line 20: "iss·aud 는 SDK 검증, sub 는 rp-app DB 조회", line 97~98)가 이미
정확히 갱신됨. 본 작업에서는 **읽어서 모순이 없는지 재확인만** 하고 변경하지 않는다. 만약 정밀 보정
중 발견되는 불일치가 있으면 그때 최소 수정.

## 4. sdk-java README — 진입부 보강 (주 작업)

§6/§7/§10 은 정확하므로 유지. 진입부에서 새 프리미티브(relay 코덱·3-인자 검증)를 안내하도록 보강.

### 4-A. §1 개요
한 줄 특징에 무상태 relay 코덱·iss/aud 검증을 추가.
- line 4 "등록/인증 4종 + ID Token 검증을 얇게 감싼다." →
  "등록/인증 4종 호출 + ID Token 검증(서명·exp·iss·aud) + 무상태 등록 릴레이 코덱을 제공한다."
- §1 불릿에 relay 코덱 한 줄 추가: "무상태 RP 가 begin↔finish 사이 userHandle 을 세션 없이 잇도록
  HMAC 서명 relay 토큰 코덱(`RegistrationRelayCodec`)을 제공한다."

### 4-B. §1 개요 끝 — 토큰 플로우 ASCII 다이어그램 (신규)
4개 토큰(registrationToken / regRelayToken / authenticationToken / idToken)의 출처·이동을
ASCII 로 시각화한다. **배치는 §1 개요의 마지막 불릿 뒤**(설치 전에 큰 그림을 먼저 보여줌). 새 섹션
번호를 만들지 않으므로 이후 §2~§10 번호는 그대로 유지된다.

```
[등록]
  RP ──registrationStart()──► SDK ──► registrationToken
  RP : regRelayToken = relay.encode(registrationToken, userHandle, …)   ──► 브라우저
  브라우저 ──(navigator.credentials.create)──► regRelayToken + credential ──► RP
  RP : registrationToken = relay.decode(regRelayToken).registrationToken()
  RP ──registrationFinish(registrationToken, credential)──► SDK ──► credentialId

[인증]
  RP ──authenticationStart()──► SDK ──► authenticationToken          ──► 브라우저
  브라우저 ──(navigator.credentials.get)──► authenticationToken + credential ──► RP
  RP ──authenticationFinish(authenticationToken, credential)──► SDK ──► idToken
  RP : claims = verifyIdToken(idToken, issuerBase+"/"+tenantId, tenantId)
```

(relay 토큰은 등록에만 쓰인다 — 인증은 authenticationToken 을 그대로 왕복하므로 별도 relay 불필요.
이 사실을 다이어그램 아래 한 줄로 명시.)

### 4-C. §3 빠른 시작
`PasskeyClient` 만 만들던 예제에 `RegistrationRelayCodec` 초기화를 추가.
```java
// 등록 릴레이 코덱(무상태 RP 필수). secret 은 강한 키로 주입.
RegistrationRelayCodec relay = new RegistrationRelayCodec(
    System.getenv("RELAY_SECRET").getBytes(StandardCharsets.UTF_8),
    Duration.ofMinutes(5), Clock.systemUTC());
```
import 도 함께 보강.

### 4-D. §4 설정 레퍼런스
기존 `PasskeyClientConfig` 표 아래에 "**RegistrationRelayCodec 생성자 인자**" 미니 표 추가:
| 인자 | 타입 | 설명 |
|---|---|---|
| secret (필수) | `byte[]` | HMAC 키 raw bytes. 강한 키 주입(출처·보호는 RP 책임). null 금지(fail-fast). |
| ttl (필수) | `Duration` | relay 토큰 만료. passkey-app challenge 만료(기본 5분)에 맞춘다. |
| clock (필수) | `Clock` | 만료 기준 시계. 운영은 `Clock.systemUTC()`. |

### 4-E. §5 API 사용
등록 finish 예제를 SDK-API-only 에서 **relay 흐름 포함**으로 교체.
```java
// 2) begin 응답을 relay 로 서명해 브라우저로 (서버 세션 불필요)
String regRelayToken = relay.encode(
    start.registrationToken(), "userHandle", "gildong", "홍길동");

// 3) (브라우저 create 후) finish — relay 검증으로 원본 registrationToken 복원
RegistrationRelayCodec.RegistrationRelay r = relay.decode(regRelayToken); // 변조/만료 시 IllegalArgumentException
RegistrationFinishResponse fin = client.registrationFinish(
    new RegistrationFinishRequest(r.registrationToken(), publicKeyCredentialJson));
```
인증 예제는 `idToken` 획득 후 **3-인자 검증**으로 자연스럽게 연결(§6 으로의 다리):
`// 검증: client.verifyIdToken(idToken, issuerBase + "/" + tenantId, tenantId) — §6 참조`

### 4-F. §8 에러 처리
예외 표에 relay decode 행 추가:
| 예외 | 발생 조건 | 주요 필드 |
| `IllegalArgumentException` | `RegistrationRelayCodec.decode()` 실패(서명 변조/만료/형식오류/불완전 payload) | message |

## 5. 작업 범위 요약

| 파일 | 작업 | 비고 |
|---|---|---|
| `rp-app/.../web/WebAuthnController.java` | 클래스 Javadoc 1줄 보정(line 47) | 정밀 보정 |
| `rp-app/.../RpAppApplication.java` | 진입점 Javadoc 1줄 보정(line 9) | 정밀 보정 |
| `rp-app/README.md` | 재검토만 | 변경 없음(불일치 발견 시 최소 수정) |
| `sdk-java/README.md` | §1 보강, 토큰 플로우 다이어그램 신규, §3/§4/§5/§8 보강 | 주 작업, §6/§7/§10 유지 |

## 6. 검증 전략

- **동작 영향 0:** 전부 주석·문서 변경. 코드 로직 무변경.
- **컴파일 가드:** rp-app 주석만 바꾸므로 `./gradlew :rp-app:compileJava` 통과 확인(주석 깨짐 방지).
- **정확성 가드:** README 예제 코드의 API 시그니처가 실제 SDK 와 일치하는지 대조
  (`RegistrationRelayCodec` ctor, `verifyIdToken` 3-인자, `RegistrationRelay` accessor).
- **잔재 0 재확인:** 작업 후 `git grep "RegRelayCodec\|normalizeTenantId"` 가 rp-app 코드에서 0건
  (SDK `IdTokenVerifier` 내부 private `normalizeTenantId` 는 정상 — 제외).
- **삭제 심볼 미참조:** README 예제가 삭제된 옛 이름을 쓰지 않는지 확인.

## 7. 비범위 (YAGNI)
- SDK 소스 주석 변경 — 사용자가 명시적으로 범위에서 제외.
- rp-app README 전면 재서술 — 이미 정확, 재검토만.
- rp-app 소스 전체 주석 스윕 — "정밀 보정만" 으로 한정(사용자 확정).
- 새 기능/리팩터링 — 없음.
