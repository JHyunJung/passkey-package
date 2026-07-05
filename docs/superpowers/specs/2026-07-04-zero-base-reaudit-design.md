# 제로베이스 3관점 재감사 + 개선 실행 설계

- 날짜: 2026-07-04
- 상태: 설계 승인됨 (감사 미실행)
- 선행: 2026-07-01 심층감사(6모듈×3관점, 신규 30 confirmed, A그룹 11건 머지 32affd85),
  2026-06 코드베이스 감사+하드닝(94 confirmed, risk=none 35건 완료)

## 목적

Code quality / Performance / Security 3관점 제로베이스 재감사.
기존 감사와 **직교하는 렌즈**로 구조적 맹점(모듈 경계·동시성·횡단 관심사)을 교차검증하고,
confirmed된 개선 중 동작불변 + 실버그를 즉시 구현한다.

## 확정된 정책 (사용자 승인)

1. **출발점**: 제로베이스 재감사 (B그룹 백로그 실행이 아님 — 단, 재발견되면 실행 대상 포함)
2. **실행 경계**: 동작불변 개선 + 실버그(틀린 동작→올바른 동작)는 바로 수정.
   순수 정책·설계 변경(예: MFA 강제화 같은 로그인 흐름 변경)은 백로그 기록 + 승인 대기.
3. **방법론**: 접근 B — 렌즈 직교 재설계 (아래).

## 감사 대상

모노레포 6모듈 전체 (~523 Java 파일):
core(150) / admin-app(157) / passkey-app(48) / rp-app(44) / sdk-java(31) / webauthn(93)

## Phase 1 — 제로베이스 감사: 렌즈 직교 fan-out (finder 14개 병렬)

각 finder는 기존 감사 결과를 모른 채(blind) 탐색. 구조화된 발견
(파일:라인, 실패 시나리오, 심각도 high/medium/low, 관점 태그 security/quality/perf)을 반환.

### E2E 흐름 렌즈 (4) — 모듈 경계를 넘는 신뢰·데이터 전파

| # | 렌즈 | 추적 경로 |
|---|---|---|
| L1 | 등록 flow | rp-app/sdk-java → passkey-app API → webauthn verifier → core 저장 |
| L2 | 인증 flow | assertion 검증 → signCount·uv/up → 세션/토큰 발급 → relay |
| L3 | admin 운영 flow | admin-app 로그인/MFA/정책 → core 감사로그·테넌트 격리 |
| L4 | SDK↔서버 계약 | sdk-java HTTP 계약, id-token 검증, relay 코덱, 에러 매핑 |

근거: 지난 감사들이 놓친 결함(issuer split fail-open, signCount lost-update)은 전부
모듈 경계·흐름 교차 지점에서 발생.

### 횡단 렌즈 (4) — 모듈 소유자가 없는 관심사

| # | 렌즈 | 초점 |
|---|---|---|
| L5 | 동시성·트랜잭션·락 | dirty-checking 전체컬럼 UPDATE, @Version 부재, 락 순서, ThreadLocal, single-flight |
| L6 | 설정·배포 드리프트 | 프로필별 yml 분기, env 폴백, Flyway↔엔티티 정합, 프로필 간 보안 설정 차이 |
| L7 | 입력 경계 | 수동 파서(DER/CBOR/JWS), 인코딩(base64url/UTF-8), 크기 제한, canonical 강제 |
| L8 | 시간·TZ | KST(+09:00) 전환 잔재, Instant↔OffsetDateTime 혼용, JVM TZ 의존, 절단/비교 |

### 모듈 압축 렌즈 (6) — 기존 유형 재발견 담당

L9~L14: 모듈당 1개, 3관점 통합 프롬프트 (core / admin-app / passkey-app / rp-app / sdk-java / webauthn).

## Phase 2 — 판정: dedup → 적대적 검증 → 기존 대조

1. 전체 발견 수집 후 파일·라인 기준 dedup (유일한 barrier 지점)
2. 발견별 **반박 전담 검증자**: "이 발견을 반박하라" 프롬프트 고정, 재현 경로 확인 후
   confirmed/refuted 판정. 불확실 시 refuted(보수적).
3. confirmed만 기존 154건(124+30)과 대조 → **신규(G번호) / 재발견(교차확인) / 미재발견** 3분류.
   미재발견 목록 = 이번 렌즈 구성의 맹점 기록.

## Phase 3 — 분류·실행

confirmed를 3버킷으로:

- **실행-불변**: 동작불변 개선 → 즉시 구현
- **실행-실버그**: 동작 변경이지만 틀린→올바른 방향 → 즉시 구현.
  재발견된 B그룹 실버그(F06 Funnel KST off-by-one, F25 429→500, F26 JWKS kid-miss,
  F07 before 커서, F11 MdsHistory TZ, F14 grace 절단 등)도 이번 confirmed 시 실행 대상.
- **백로그-정책**: 순수 정책·설계 변경 → 백로그 기록 + 사후 승인 대기.
  예: F01 mfaRequired 강제화 — 버그(no-op)이지만 수정이 곧 로그인 흐름 정책 변경이므로 백로그.

## Phase 4 — 구현·검증 게이트 (기존 체계 유지)

- per-phase worktree에서 작업 → 완료 후 main `--no-ff` 머지
- 수정 건별 3-gate 리뷰: spec 정합 / quality / codex (codex가 지난 2회 롤백 적발한 체계)
- 회귀 판정 = **base worktree 대조** (전체 `./gradlew build`는 SliceConfig 충돌·Oracle
  컨테이너 경합으로 항상 빨감 — 머지 게이트 부적격)
- 신규 거부 경로가 생기는 수정은 negative test 필수
- 서명 변조류 테스트는 base64url 문자치환이 아닌 디코드 바이트 레벨 변조로 작성

## 산출물

1. 본 spec + 감사 결과표(실행 후 갱신)
2. 신규/재발견/미재발견 분류표 + 맹점 분석
3. 건별 커밋 (G번호 체계, F번호 이어서)
4. 백로그 갱신 (미실행 정책 변경 건)

## 알려진 리스크

- 재발견율이 높으면 신규 가치가 낮을 수 있음 — 사용자가 교차검증 가치를 인지하고 선택.
- finder 14 + 검증자 N 병렬은 토큰 비용 큼 — 접근 C(반박 패널·dry-run 반복)는 배제로 상한 관리.
- Docker 가용 여부에 따라 Testcontainers 검증이 CI 대기로 밀릴 수 있음(선례: rp-admin-multi-tenant).

---

## 재감사 결과 (2026-07-05 실행)

Workflow(wf_893807e1-01c)로 14 finder blind fan-out → dedup → 반박 전담 검증.
**raw 45 → dedup 41 → confirmed 33 / refuted 8.** 기존 154건 대조: **재발견 12 / 신규 21(G01~G21) / 미재발견 8**.

실행 경계: 경계선 4건(G03 suspend no-op, F01 mfaRequired no-op, F02 TOTP replay,
G05 MFA lockout 리셋)까지 포함해 **confirmed 33건 전부 즉시 수정** 결정(사용자 승인 2026-07-05).
백로그로 남기는 것은 미재발견 8건(맹점)뿐.

### 신규 21건 (G01~G21)

| G | 파일:라인 | 심각도/관점 | 증상 |
|---|---|---|---|
| G01 | passkey-app RegistrationFinishService:144 | M/quality | 중복 credentialId 등록 unique 위반 500(사전검사·409 부재) |
| G02 | passkey-app RegistrationFinishService:88 | L/security | publicKeyCredential 크기상한 전 완전 JsonNode 파싱(DoS 표면) |
| G03 | admin AdminUserService:108 | H/security | suspend가 STATUS만 바꾸고 ENABLED 미변경 — 로그인 차단 no-op |
| G04 | admin AdminUserService:104 | M/security | 운영자 lifecycle 변경이 tamper-evident 감사체인 미기록 |
| G05 | admin AdminSecurityConfig:214 | M/security | 1차 로그인 성공이 공유 lockout 리셋 — MFA 브루트포스 잠금 우회 |
| G06 | admin AdminUserService:141 | M/quality | 마지막 PLATFORM_OPERATOR 락아웃 방지가 락 없는 count-check(write-skew) |
| G07 | admin MdsSchedulerService:98 | M/perf | MDS 캐시 delete-then-repopulate(비원자) — sync 윈도우 fail-closed |
| G08 | admin AdminUserService:97 | L/quality | removeTenant RP_ADMIN 마지막-테넌트 불변식 락 없는 count |
| G09 | admin InvitationService:94 | L/quality | 초대 accept read-check-update — double-accept 우회 |
| G10 | core IdTokenIssuer:33 | M/security | prod/qa yml issuer-base 빈 문자열 → iss='/tenantId' 발급 |
| G11 | admin PasswordResetService:39 | M/security | invite.base-url prod 폴백이 http://localhost:5173 |
| G12 | core LicenseGuardFilter:39 | M/security | deployment.mode 값 검증 부재 — 오타로 온프렘 라이선스 silent 비활성 |
| G13 | admin AdminSecurityConfig:220 | L/security | 감사로그 IP가 getRemoteAddr(프록시 IP) |
| G14 | webauthn PackedAttestationVerifier:135 | M/security | packed DER 길이 파서 additive 경계검사 int 오버플로 우회 |
| G15 | admin CredentialAdminService:154 | M/perf | Credential toView 행마다 Redis GET(페이지당 최대 200 왕복 N+1) |
| G16 | admin MdsAdminController:48 | L/perf | MDS status Redis KEYS 전체 스캔(블로킹 O(N)) |
| G17 | admin AdminUserService:160 | L/perf | AdminUser toView 사용자마다 매핑 조회 N+1 |
| G18 | passkey-app RegistrationStartService:75 | M/quality | userHandle 길이 미검증 — 64B 초과 시 finish 저장 500 |
| G19 | sdk-java JwksCache:115 | H/perf | JWKS fetch connect/read timeout 부재 — hung endpoint가 single-flight 락 하 전체 검증 차단(F27 잔여 갭) |
| G20 | sdk-java IdTokenVerifier:59 | L/security | id-token verify exp만 검사, nbf/iat 미검증 |
| G21 | webauthn NativeMetadataBlobVerifier:15 | L/security | MDS BLOB verifier payload.nextUpdate 신선도 미검증(stale 수용) |

### 재발견 12건 (기존 F/항목 교차확인 → 신뢰도 상승)

F31(rawId 미대조)·F01(mfaRequired no-op)·F02(TOTP replay)·F25(429→500)·F26(kid-miss)·
F06(Funnel KST off-by-one)·F07(before 커서)·F20(입력 크기)·
sec-ratelimit-ip-proxy-collapse(RateLimitFilter)·sec-session-csrf-cookie-not-secure·
rp-pending-user-unbounded-dos(InMemoryUserStore + RegisterStartReq @Size).

### 미재발견 8건 (백로그 — 이번 렌즈 맹점)

F04(KeyRotation 리스 미해제)·F08(ApiKey 정렬 불안정)·F10(Funnel rolling-window)·
F11(MdsHistory TZ 왕복)·F14(license grace 절단)·F15(license cache tenantId)·
F30(인증경로 BE/BS)·F35(CBOR non-canonical).
패턴 = **"파일당 1건 편향"**: F06/F10(FunnelService), F30/F31(NativeWebAuthnVerifier) —
같은 파일 인접 결함 중 하나만 잡음. 교훈: 이미 결함 발견된 파일도 전체 재정독 필요.

### refuted 8건

BE/BS 인증경로(F30과 별개 판정)·tenant suspend 재검사·X-Trace-Id 반영·License Clock 미사용·
ApiKey findAll·RelayKeyGuard 지연·packed/android-key alg 핀 — 반박 검증에서 재현 경로 불성립 또는 이미 방어 존재로 refuted.

### 실행 결과 (2026-07-05 완료)

**confirmed 33건 중 32건 구현 완료, F01만 백로그**(프론트+백 크로스커팅 기능이라 별도 spec).
10개 배치(A~J) 각각 spec+quality+codex 3-gate 리뷰. **codex성 게이트가 실질 결함 2건 적발**:
- 배치 E: G18 userHandle 문자셋 사전검사가 패딩 포함 표준 base64url 정상 입력을 거부하는
  **회귀** → 디코더 예외 기반으로 전환.
- 배치 J: G21 MDS 신선도 검사가 인터페이스 미노출로 **프로덕션에서 실행 안 되는 죽은 코드**
  → 인터페이스 default + MdsBlobClient Clock 배선으로 활성화.

whole-branch 최종 리뷰(opus): **Ready to merge**. 크로스커팅 정합성 전부 통과
(ErrorCode F006 충돌 없음, V2/V3 마이그레이션 정합, @Version 부재는 세션당 순차라 실위협 아님,
동일 파일 다중 배치 상호 훼손 없음, 실행 경계 위반 없음). 배포 체크리스트 1건 격상:
**forward-headers=framework는 앞단 프록시가 X-Forwarded-* 스트립을 전제**(followups 참조).

미실행·후속: `docs/superpowers/followups/2026-07-04-zero-base-reaudit-followups.md`
(F01 별도 spec, 미재발견 8건 맹점, 배치별 Minor·CI 대기·배포 조건).

### 구현 배치 (Task A~J, 파일·도메인 그룹)

- **A** [admin operator]: AdminUserService 5건(G03/G04/G06/G08/G17) + InvitationService(G09) + PasswordResetService(G11)
- **B** [admin config]: AdminSecurityConfig 4건(F01 mfaRequired/G05 lockout/G13 IP/쿠키 Secure) + TotpService(F02)
- **C** [admin mds/perf]: MdsSchedulerService(G07) + MdsAdminController(G16) + CredentialAdminService(G15) + FunnelService(F06)
- **D** [core]: IdTokenIssuer(G10) + LicenseGuardFilter(G12) + ActivityRepository(F07)
- **E** [passkey-app registration]: RegistrationStartService(G18) + RegistrationFinishService(G01/G02) + RegistrationStartRequest(F20)
- **F** [passkey-app security]: RateLimitFilter(forward-headers)
- **G** [rp-app]: InMemoryUserStore(pending cap) + RegisterStartReq(@Size)
- **H** [sdk-java idtoken]: JwksCache timeout(G19) + IdTokenVerifier kid-miss(F26)+nbf/iat(G20)
- **I** [sdk-java error]: PasskeyResponseErrorHandler(F25 429→500)
- **J** [webauthn]: PackedAttestationVerifier DER(G14) + NativeMetadataBlobVerifier freshness(G21) + NativeWebAuthnVerifier rawId(F31)
