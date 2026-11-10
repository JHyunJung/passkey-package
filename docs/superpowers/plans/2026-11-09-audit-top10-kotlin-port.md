# 감사 Top 10 개선 — Kotlin 코드베이스 이식 계획

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (+ Codex 리뷰 seat).

**Goal:** `chore/audit-top10`(Java 시절 main 기준, 2026-10-14)의 보안·성능 수정을 Kotlin 으로 전환된 현재 main(82a05d34)에 동작 그대로 옮긴다.

**Architecture:** 원 커밋이 정본이다. core(Java 유지) 커밋은 cherry-pick, admin-app·passkey-app(Kotlin) 커밋은 원 Java diff 의 의미를 Kotlin 본 코드·Kotlin 테스트로 다시 쓴다. 원 계획 `docs/superpowers/plans/2026-10-13-audit-top10.md` 의 해당 Task 절이 요구사항·동작 보존 근거·테스트 목록의 정본이다.

**Tech Stack:** Kotlin 2.4.21, Spring Boot 4.1.2, JUnit 5 + AssertJ + mockito-kotlin(MockK·Kotest·코루틴 금지), core 는 Java 17.

**Spec:** `docs/superpowers/specs/2026-10-13-audit-top10-design.md`

## 이미 반영됨 (이 브랜치)

- e837917a(docs), da590fa2(P7 heartbeat 타임아웃), 08f49e9e(S8 LicenseGuardFilter) — core 전용이라 cherry-pick -x.

## 이식하지 않음 (Ruling)

- 71621056(S20 검색 캐시)·d25e9bfb(S23 seg 인코딩): 대상 admin-ui(React)가 main 에서 삭제됨(df0dee19). Thymeleaf 화면은 서버 렌더·`@{}` 경로 변수 인코딩이라 같은 결함이 없다.
- 97c08e53·b0506e3e(P3 클라이언트 — 숨은 탭 Sidebar 폴링 중지 + keep-alive): 대상 삭제. Thymeleaf `sidebar.html` chain-card 의 `every 30s` 폴링에 같은 개선 여지가 있으나 세션 keep-alive 의미를 건드리는 JS 변경이라 브라우저 실검증과 함께 별도 작업으로 백로그.

## Global Constraints

- 동작 불변: 원 커밋이 의도한 변경 외에는 외부 동작(HTTP 응답·렌더 HTML·JSON·세션 직렬화·감사 로그)이 바뀌지 않는다. 동결 contract 스냅샷(`admin-app/src/test/resources` 이하 fixture)은 수정 금지.
- `AdminUserDetails` serialVersionUID 는 `-8462830666458161297L` 그대로.
- 테스트는 Kotlin(`src/test/kotlin`)으로. 원 Java 테스트의 테스트 메서드·단언을 빠짐없이 옮긴다(이름은 백틱 또는 원 이름 유지). 원 테스트가 의존하는 Java 시절 협력자 API 는 현재 Kotlin API 에 맞춘다.
- mockito-kotlin 규칙: Java bare `any()` → `anyOrNull()`, typed `any(X.class)` → `any<X>()`, thenThrow 뒤 재스텁은 `doReturn().whenever()`.
- core 는 Java 그대로 수정(Kotlin 전환 대상 아님).
- 커밋 메시지는 원 커밋 제목을 따르고 본문에 `(이식: <원 커밋 short sha>)` 표기.

---

### Task 1: P3 서버 — 감사 체인 overview 를 tenant 당 한 번만 읽기 (원 3ef6fdd6)

원 계획 Task 3 의 서버 절반. `AuditChainVerifier.kt`·`AuditChainMonitorController.kt` 수정 + `AuditChainOverviewCharacterizationTest.kt` 신규 + `AuditChainVerifierTest.kt` 추가 케이스.

### Task 2: S16 — 로그인 후 AdminUserDetails bcrypt 해시 소거 (원 810693ab)

원 계획 Task 4. `AdminUserDetails.kt` 가 `CredentialsContainer` 구현(passwordHash 만 null), equals/hashCode 정의 안 함. 테스트 `AdminLoginCredentialErasureTest`·`AdminUserDetailsTest`(신규 또는 기존에 추가)·`AdminUserDetailsServiceTest` 추가 케이스. 기존 `SessionSerializationTest`·session contract 스냅샷은 무수정 통과해야 한다.

### Task 3: S9 — 비밀번호 재설정 메일을 커밋 후 전용 스레드에서 발송 (원 dfc51412)

원 계획 Task 5. `AfterCommitMailDispatcher.kt` 신규, `PasswordResetService.kt` 수정, 테스트 3종.

### Task 4: P1 — API 키 secret 검증 결과 캐시 (원 731d647d)

원 계획 Task 8. core `CoreSecurityConfig.java` 주석(Java 그대로), passkey-app `VerifiedSecretCache.kt` 신규·`ApiKeyAuthFilter.kt` 수정, 테스트 3종.

### Task 5: P6 — Tomcat 워커 상한을 Hikari 풀−1 로 (원 698881f9)

원 계획 Task 9. yml·compose·문서·core `CeremonyEventRecorder.java` 는 원 diff 그대로, 테스트 `DbPoolConfigSnapshotTest`(admin-app)·`DbPoolThreadBudgetConfigTest`(passkey-app) 는 Kotlin.

### Task 6: P5 — audit_log (created_at, id) 복합 인덱스 V6 (원 계획 Task 10, 원 브랜치 미커밋)

원 브랜치 worktree `.claude/worktrees/audit-top10` 의 미커밋 변경(V6 마이그레이션, `AuditLogOrderIndexIT.java`, `AuditLogRepository.java` Javadoc, `scripts/full-schema-part1/2`, `qa-bundle/README.md`·`CHECKLIST.md`)을 원 계획 Task 10 절과 대조해 완성·검증한다(Testcontainers IT 실행 필수). 전부 core·scripts·문서라 Kotlin 변환 없음. 원 worktree 는 수정하지 않는다(읽기만).
