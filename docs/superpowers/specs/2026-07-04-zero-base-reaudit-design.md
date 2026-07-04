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
