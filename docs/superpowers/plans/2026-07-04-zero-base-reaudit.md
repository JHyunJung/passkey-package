# 제로베이스 3관점 재감사 + 개선 실행 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 감사와 직교하는 렌즈로 3관점(quality/perf/security) 제로베이스 재감사를 수행하고, confirmed된 동작불변 개선과 실버그를 3-gate 검증하에 구현한다.

**Architecture:** Phase 1은 Workflow로 14개 finder를 병렬 fan-out(blind 탐색). Phase 2는 dedup(barrier) → 반박 전담 검증자 → 기존 154건 대조로 신규/재발견/미재발견 분류. Phase 3은 confirmed를 실행-불변/실행-실버그/백로그-정책 버킷으로 나눠 건별 3-gate 구현. Phase 4는 결과를 spec·백로그에 반영.

**Tech Stack:** Java 17, Spring Boot, Gradle 멀티모듈, Oracle+Flyway, Redis, JUnit/Testcontainers. 감사는 Workflow(subagent orchestration).

## Global Constraints

- 작업 worktree: `.claude/worktrees/zero-base-reaudit` (브랜치 `zero-base-reaudit`, base main@69427029). 모든 커밋은 이 worktree에서.
- 실행 경계: 동작불변 + 실버그(틀린→올바른 동작)만 구현. 순수 정책·설계 변경(로그인 흐름 변경 등)은 백로그. F01 mfaRequired 강제화는 백로그-정책.
- 회귀 판정 = base worktree 대조. 전체 `./gradlew build`는 SliceConfig 충돌·Oracle 컨테이너 경합으로 항상 실패 → 머지 게이트로 쓰지 않음.
- 건별 3-gate 리뷰: spec 정합 / quality / codex. codex 게이트는 동시성·lost-update·fail-open을 특히 검사(지난 2회 롤백 근거).
- 신규 거부 경로 생기는 수정은 negative test 필수. 서명 변조 테스트는 디코드 바이트 레벨 변조로 작성(문자치환 금지).
- 신규 발견 번호 체계: G01, G02, … (기존 F 번호 이어서).
- 기존 confirmed 154건 근거 문서: `docs/superpowers/specs/2026-07-01-codebase-deep-audit-design.md`(30건 + B그룹 16), `docs/superpowers/specs/2026-06-10-security-audit-design.md` 및 `docs/superpowers/followups/2026-05-31-hardening-master-backlog.md`(94건 계열).

---

## File Structure

- `docs/superpowers/specs/2026-07-04-zero-base-reaudit-design.md` — 설계(작성됨). 감사 결과표는 Phase 4에서 갱신.
- `docs/superpowers/plans/2026-07-04-zero-base-reaudit.md` — 본 계획.
- `/private/tmp/.../scratchpad/reaudit-findings.json` — Phase 1 raw 발견 수집(임시).
- `/private/tmp/.../scratchpad/reaudit-verdicts.json` — Phase 2 판정 결과(임시).
- `docs/superpowers/followups/2026-07-04-zero-base-reaudit-followups.md` — Phase 4 백로그(미실행 정책 변경 + 미재발견 맹점 기록).
- 소스 수정 파일 — Phase 3 confirmed 건별로 확정(계획 시점 미정, 감사 산출).

---

## Phase 1 — 제로베이스 감사 fan-out

### Task 1: 14-finder blind 감사 Workflow 실행

**Files:**
- Create: `scratchpad/reaudit-findings.json` (수집 결과)

**Interfaces:**
- Produces: 각 finder가 반환하는 발견 배열. 발견 스키마 = `{id, lens, module, file, line, perspective(security|quality|perf), severity(high|medium|low), title, failureScenario, suggestedFix, behaviorChange(bool)}`.

- [ ] **Step 1: finder 렌즈 정의 확인**

spec의 Phase 1 표 그대로 14개 렌즈: L1 등록flow / L2 인증flow / L3 admin운영flow / L4 SDK↔서버계약 / L5 동시성·트랜잭션·락 / L6 설정·배포드리프트 / L7 입력경계 / L8 시간·TZ / L9~L14 모듈압축(core/admin-app/passkey-app/rp-app/sdk-java/webauthn).

- [ ] **Step 2: Workflow 스크립트 작성 및 실행**

각 finder subagent 프롬프트 필수 요소:
- "너는 기존 감사 결과를 모른다. 아래 렌즈로만 독립 탐색하라"(blind 강제)
- 렌즈별 추적 경로/초점(spec 표에서 복사)
- 반환 스키마(위 Interfaces) 강제 — Workflow schema 옵션 사용
- "심각도는 실제 재현 가능성 기준. 추측성 발견 금지. 파일:라인 필수"
- behaviorChange: 관찰 가능한 동작(응답/순서/거부/타이밍)이 바뀌면 true

Workflow 구조: `parallel`로 14 finder 동시 실행(각 phase='Find'), 결과를 flat하게 수집.

- [ ] **Step 3: raw 발견을 scratchpad에 저장**

전체 발견을 `reaudit-findings.json`으로 기록(dedup 전). 개수와 렌즈별 분포를 log로 출력.

---

## Phase 2 — 판정: dedup → 적대적 검증 → 기존 대조

### Task 2: dedup 및 반박 전담 검증

**Files:**
- Create: `scratchpad/reaudit-verdicts.json`

**Interfaces:**
- Consumes: `reaudit-findings.json` (Task 1)
- Produces: `{...finding, verdict(confirmed|refuted), verdictReason, reproPath}` 배열

- [ ] **Step 1: 파일·라인 기준 dedup**

동일 file+line(±3라인) 발견을 1건으로 병합. 병합 시 최고 severity 채택, 렌즈 목록 보존(교차확인 카운트). 이 단계가 유일한 barrier.

- [ ] **Step 2: 발견별 반박 전담 검증자 dispatch**

각 dedup 발견에 대해 검증자 subagent(schema 강제):
- 프롬프트: "이 발견을 **반박하라**. 실제 코드를 읽고 재현 경로가 성립하는지 확인. 성립 안 하면 refuted. 불확실하면 refuted(보수적)."
- 반환: verdict, verdictReason, reproPath(confirmed 시 구체 재현 시나리오)
- codex 계열 관점(동시성/lost-update/fail-open)은 특히 엄격 검사

- [ ] **Step 3: confirmed만 남겨 저장**

`reaudit-verdicts.json`에 confirmed 발견만 기록. refuted는 개수만 log.

### Task 3: 기존 154건 대조 및 3분류

**Files:**
- Modify: `docs/superpowers/specs/2026-07-04-zero-base-reaudit-design.md` (결과표 섹션 추가)

**Interfaces:**
- Consumes: `reaudit-verdicts.json` (Task 2)

- [ ] **Step 1: 기존 발견 목록 로드**

Global Constraints의 근거 문서 3개에서 기존 confirmed 발견의 (file, 증상) 목록 추출. subagent 1개에 위임(대조표 반환).

- [ ] **Step 2: 신규/재발견/미재발견 3분류**

- 신규: 기존 목록에 없음 → G01, G02, … 부여
- 재발견: 기존 F/기존건과 동일 → 교차확인으로 표시(신뢰도 상승)
- 미재발견: 기존엔 있으나 이번 렌즈가 못 잡음 → 맹점 기록

- [ ] **Step 3: 결과표를 spec에 추가하고 커밋**

spec 하단에 "## 재감사 결과" 섹션: 신규 발견표(G번호/파일:라인/관점/심각도/실행버킷), 재발견 요약, 미재발견 맹점 목록.

```bash
git add docs/superpowers/specs/2026-07-04-zero-base-reaudit-design.md
git commit -m "docs(spec): 재감사 결과 반영 — 신규 N건/재발견 M건/미재발견 K건"
```

---

## Phase 3 — 구현 (confirmed 건별 반복)

### Task 4: confirmed 발견 실행 버킷 분류

**Interfaces:**
- Consumes: Task 3의 신규 + 재발견 confirmed 발견

- [ ] **Step 1: 3버킷 분류**

각 confirmed를:
- **실행-불변**: 동작불변 개선
- **실행-실버그**: 동작 변경이나 틀린→올바른. F06/F25/F26/F07/F11/F14 등 재발견 실버그 포함
- **백로그-정책**: 순수 정책·설계 변경(F01 mfaRequired 등)

- [ ] **Step 2: 실행 대상 우선순위 정렬**

security high → quality/perf high → medium → low. 실행-불변과 실행-실버그만 Task 5 반복 대상.

### Task 5 (반복): confirmed 건별 3-gate 구현

실행 대상 각 건 G_i 에 대해 아래 사이클 1회. **한 건 = 한 커밋**.

**Files:**
- Modify: 해당 발견의 file:line (Task 3 결과에서 확정)
- Test: 동일 모듈의 기존 테스트 디렉터리 (거부 경로 추가 시 negative test)

- [ ] **Step 1: 실패하는 테스트 작성 (거부 경로/버그 재현이 있는 경우)**

실버그·신규 거부 경로: 현재 잘못된 동작을 드러내는 테스트를 먼저 작성. 서명 변조류는 디코드 바이트 레벨 변조. 순수 리팩터(동작불변, 테스트로 드러낼 게 없음)면 이 스텝 생략하고 기존 테스트 회귀 없음으로 대체.

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `./gradlew :<module>:test --tests "<TestClass>" ` (해당 모듈만)
Expected: 새 테스트 FAIL (버그 재현)

- [ ] **Step 3: 최소 수정 구현**

발견의 suggestedFix 적용. 근본 원인 수정만, 범위 확대 금지.

- [ ] **Step 4: 테스트 통과 확인 + base 대조 회귀 검사**

Run: `./gradlew :<module>:test --tests "<TestClass>"`
Expected: PASS.
회귀는 base worktree(`/Users/jhyun/Git/10-work/crosscert/Passkey2`)의 동일 모듈 테스트 결과와 대조 — 새로 빨개진 테스트만 회귀로 판정.

- [ ] **Step 5: 3-gate 리뷰**

subagent 3개 순차/병렬: (1) spec 정합 — 실행 경계 위반(정책 변경인데 구현) 여부, (2) quality — 근본 원인·범위·명명, (3) codex — 동시성/lost-update/fail-open/전체컬럼 UPDATE 부작용. 하나라도 롤백 권고 시 수정 또는 백로그 이관.

- [ ] **Step 6: 커밋**

```bash
git add <files>
git commit -m "fix(G_i): <증상> — <수정 요지> (관점)"
```

---

## Phase 4 — 결과 반영 및 머지

### Task 6: 백로그 문서화 및 main 머지

**Files:**
- Create: `docs/superpowers/followups/2026-07-04-zero-base-reaudit-followups.md`
- Modify: `docs/superpowers/specs/2026-07-04-zero-base-reaudit-design.md`

- [ ] **Step 1: 백로그 문서 작성**

백로그-정책 버킷 + 미재발견 맹점을 followups 문서에 기록(각 항목: 파일:라인, 증상, 왜 미실행, 실행 시 동작 변경).

- [ ] **Step 2: spec 결과표에 실행 완료 표시**

각 G번호에 상태(완료/백로그) 반영. A그룹 실행 요약 추가.

- [ ] **Step 3: 결과 문서 커밋**

```bash
git commit -am "docs: 재감사 실행 결과 — 완료 N건/백로그 M건 + 맹점 기록"
```

- [ ] **Step 4: main으로 --no-ff 머지**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git merge --no-ff zero-base-reaudit -m "Merge: 제로베이스 재감사 — 동작불변·실버그 N건 개선"
```

- [ ] **Step 5: worktree 정리 및 메모리 갱신**

```bash
git worktree remove .claude/worktrees/zero-base-reaudit
```
MEMORY.md에 재감사 결과 1줄 추가(신규/재발견/맹점 요약).

---

## Self-Review

- **Spec 커버리지**: Phase 1(14 finder)↔spec Phase 1 ✓, Phase 2(dedup/반박/대조)↔spec Phase 2 ✓, Phase 3(3버킷/3-gate)↔spec Phase 3·4 ✓, Phase 4(백로그/맹점)↔spec 산출물 ✓.
- **실행 경계**: Task 4 Step 1과 Global Constraints에서 F01=백로그 명시, 일관 ✓.
- **미확정 구간**: Phase 3 소스 파일은 감사 산출이라 계획 시점 미정 — 의도된 것(감사 계획의 본질). Task 3에서 확정 후 Task 5 반복.
- **회귀 판정**: 모든 검증 스텝이 base worktree 대조로 통일 ✓ (메모리 교훈 반영).
