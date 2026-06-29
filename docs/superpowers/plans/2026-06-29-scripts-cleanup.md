# scripts/ 폴더 정리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `scripts/`에서 실행 경로 참조가 0인 고립 도구 3개를 삭제하고, DB 인프라 10개는 유지한다.

**Architecture:** `git rm`으로 3개 파일을 한 커밋에 삭제(이력 보존). 살아있는 코드/문서는 깨질 참조가 없으므로(전수 grep 확인됨) 다른 파일은 수정하지 않는다. 삭제 후 grep으로 실행 경로 참조 0을 재확인하고, Gradle 복사 태스크가 멀쩡한지 안전 점검한다.

**Tech Stack:** git, bash grep, Gradle.

## Global Constraints

- 삭제 대상은 정확히 3개: `scripts/verify-lombok-bytecode.sh`, `scripts/build-docs-pdf.sh`, `scripts/docs-pdf.css`.
- 유지 대상 10개(클러스터 A 6 + 클러스터 B 4)는 **건드리지 않는다.**
- `docs/rp-server-api.pdf`(생성물)·`docs/rp-server-api.md`(원본)는 **건드리지 않는다.**
- 2026-06-14 Lombok plan/spec 문서는 역사 기록이므로 **수정하지 않는다.**
- README.md는 **수정하지 않는다** (145줄 PDF 언급은 admin-ui `/audit-chain` 런타임 기능이라 무관).
- 작업 디렉토리: 메인 repo 루트 `/Users/jhyun/Git/10-work/crosscert/Passkey2`, 브랜치 `main` (clean 상태). 단일 정리 커밋이라 worktree 불필요.

---

### Task 1: 고립 도구 3개 삭제 + 검증

**Files:**
- Delete: `scripts/verify-lombok-bytecode.sh`
- Delete: `scripts/build-docs-pdf.sh`
- Delete: `scripts/docs-pdf.css`

**Interfaces:**
- Consumes: 없음.
- Produces: 없음 (순수 삭제).

- [ ] **Step 1: 삭제 전 실행 경로 참조 0 확인 (사전 안전 점검)**

Run:
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
grep -rln "verify-lombok-bytecode\|build-docs-pdf\|docs-pdf\.css" . \
  --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.claude \
  | grep -v "^\./scripts/" | grep -v "^\./docs/superpowers/"
```
Expected: **출력 없음** (살아있는 코드/빌드/스크립트에서 참조 0; scripts 자기 자신과 docs/superpowers 역사 문서는 제외). 만약 다른 파일이 나오면 멈추고 그 참조를 먼저 검토한다.

- [ ] **Step 2: git rm 으로 3개 삭제**

Run:
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git rm scripts/verify-lombok-bytecode.sh scripts/build-docs-pdf.sh scripts/docs-pdf.css
```
Expected: `rm 'scripts/verify-lombok-bytecode.sh'` 등 3줄 출력.

- [ ] **Step 3: scripts/ 최종 10개 확인**

Run:
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
ls scripts/ | sort
```
Expected: 정확히 아래 10개:
```
bootstrap-external-body.sql
bootstrap-external.sql
bootstrap-schema.sql
init-db-external.sh
init-dev-db.sh
reset-app-owner-external.sql
reset-app-owner.sh
reset-app-owner.sql
run-bootstrap.sh
wait-for-oracle.sh
```

- [ ] **Step 4: 삭제 후 실행 경로 참조 0 재확인**

Run:
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
grep -rln "verify-lombok-bytecode\|build-docs-pdf\|docs-pdf\.css" . \
  --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.claude
```
Expected: 남는 건 **2026-06-14 Lombok 역사 문서뿐** (`docs/superpowers/plans/2026-06-14-lombok-code-quality.md`, `docs/superpowers/specs/2026-06-14-lombok-code-quality-design.md`). 살아있는 코드/빌드/스크립트 0건. (새로 추가될 이번 spec/plan 문서가 자기 자신을 언급할 수 있으나 그것도 문서일 뿐 실행 경로 아님.)

- [ ] **Step 5: bootstrap-schema 복사 태스크 안전 점검**

삭제는 `bootstrap-schema.sql`과 무관하지만, scripts/ 를 건드렸으니 Gradle 복사 태스크가 멀쩡한지 확인한다.

Run:
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
./gradlew :core:processTestResources :admin-app:processTestResources
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: 커밋**

Run:
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git commit -m "chore(scripts): 고립 도구 3개 제거 (lombok 검증·PDF 빌드)

verify-lombok-bytecode.sh(Lombok 도입 2026-06-14 완료, 코드 참조 0),
build-docs-pdf.sh + docs-pdf.css(PDF 변환 고립 쌍, 살아있는 참조 0) 삭제.
DB 인프라 10개·생성 PDF·역사 문서는 보존. 필요 시 git 이력에서 복구.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```
Expected: 3 files changed, deletions only.

---

## Self-Review (작성자 점검 결과)

**1. Spec coverage:**
- 삭제 3개(spec §3) → Task 1 Step 2. ✅
- 유지 10개(spec §4) → Global Constraints + Step 3 검증. ✅
- README 무수정(spec §5.2) → Global Constraints. ✅
- 역사 문서 무수정(spec §5.3) → Global Constraints + Step 4 expected. ✅
- 검증(spec §6): 실행 경로 참조 0(Step 4) + Gradle 점검(Step 5) + 최종 10개(Step 3). ✅
- 생성 PDF 보존(spec §3) → Global Constraints. ✅

**2. Placeholder scan:** TBD/TODO/"적절히" 없음. 모든 Step에 실제 명령 + expected. ✅

**3. Type consistency:** 파일 경로 3개가 Global Constraints·Files·Step 2에서 동일. 유지 목록 10개가 Step 3과 일치. ✅

이상 없음. 구현 준비 완료.
