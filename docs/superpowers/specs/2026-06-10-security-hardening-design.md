# 보안 하드닝 설계 — CRITICAL + HIGH 4건 (2026-06-10 감사)

- **입력**: `docs/superpowers/audit/2026-06-10-security-audit-report.md` (confirmed 19건 중 critical 1 + high 3)
- **범위**: CRITICAL #1 + HIGH #2/#3/#4 **4건만**. medium 이하는 별도 후속.
- **방법론**: TDD(각 finding마다 실패 테스트 먼저) + Testcontainers 필수(GRANT/DB 변경 실증).
- **검증 게이트**: 각 묶음 커밋 전 `/codex:review`, Testcontainers·슬라이스 테스트 그린.

---

## 작업 묶음 구조

4건을 레이어가 다른 **2개 독립 묶음**으로 나눈다 (서로 충돌 없음).

| 묶음 | finding | 레이어 | 핵심 파일 |
|------|---------|--------|-----------|
| **A: admin-auth (앱)** | #1 MFA 재등록 우회 (CRITICAL), #4 MFA verify 브루트포스 (HIGH) | Java/Spring | `MfaPendingFilter`, `MfaController`, `AdminUser` |
| **B: DB 권한/부트스트랩 (인프라)** | #3 런타임 GRANT ALL (HIGH), #2 외부 부트스트랩 비번 (HIGH) | SQL/shell | `bootstrap-external.sql`, `bootstrap-vpd.sql`, `init-db-external.sh` |

작업 순서: **A 먼저** (#1→#4, 같은 파일이라 한 흐름) → **B** (#3→#2, 같은 두 SQL 파일).

---

## 묶음 A — admin-auth (앱)

### #1 — MFA 재등록 우회 차단 (CRITICAL)

**근본 원인 2개를 모두 막는다 (방어심층).**

#### (a) `MfaPendingFilter` — pending 허용 경로를 verify로만 좁힘

현재 `MfaPendingFilter.java:76`이 `uri.startsWith("/admin/api/mfa/")`로 MFA 네임스페이스 **전체**를 pending 상태에서 허용 → enroll/confirm/disable 도달. 명시 경로 매칭으로 교체:

```java
private boolean isAllowedWhilePending(HttpServletRequest req) {
    String uri = req.getRequestURI();
    if (uri == null) return false;
    return uri.equals("/admin/api/mfa/verify")   // 2차 인증 완료용 — 유일 허용
            || uri.equals("/admin/api/me")
            || uri.equals("/admin/logout");
}
```

→ enroll/confirm/disable은 pending 세션에서 403 `{"error":"mfa_required"}`.

#### (b) `MfaController` — 핸들러 진입부 이중 방어

filter(a)가 1차 방어. "보안 결정을 한 곳에만 의존하지 않는다" 원칙으로 enroll/confirm/disable 진입부에 pending 직접 거부를 추가한다. 핸들러 시그니처에 `HttpServletRequest req`를 추가하고 세션 attribute를 직접 검사:

```java
// enroll/confirm/disable 진입부 (verify는 제외 — verify는 pending에서 정상 도달해야 함)
var session = req.getSession(false);
if (session != null && Boolean.TRUE.equals(session.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR))) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "mfa_required"));
}
```

> **설계 판단**: (a)는 "verify 외에는 애초에 도달 불가"로 공격 표면 자체를 제거, (b)는 filter 우회/리팩터 회귀 대비. 둘 다 둔다.

#### `validCode`의 `mfaEnabled` 미검사는 그대로 둔다

verify는 enroll 직후 confirm 전에도 동작해야 하는 정상 흐름이 있다. enroll 도달 자체를 (a)(b)로 막으면 우회 체인이 끊긴다. 여기에 `mfaEnabled` 검사를 넣으면 정상 enroll→confirm 흐름이 깨진다. **건드리지 않는다.**

### #4 — verify 브루트포스 lockout (HIGH)

**기존 `AdminUser.recordFailedLogin`/`recordSuccessfulLogin` 재사용 (통합 lockout).** 1차 로그인과 같은 `failedLoginCount`/`lockedUntil` 필드·정책 공유 → 마이그레이션 불필요, fail-closed(MFA 실패로 lock 걸리면 1차 로그인까지 차단).

`MfaController.verify`에 다음 추가:

1. **진입 시 lock 검사** — `u.isLocked(now)`면 코드 검사 전에 거부.
2. **TOTP·복구코드 둘 다 실패** 시 → `u.recordFailedLogin(now, MAX, DURATION)` + `users.save(u)`.
3. **성공 시** → `u.recordSuccessfulLogin()` (1차 실패 카운트도 함께 리셋 — 통합 모델의 의도된 동작).
4. **lock 발동 시** → 현재 `MFA_PENDING` 세션 무효화 + `log.warn` 보안 알림.

임계치(`MAX`)·지속시간(`DURATION`)은 **1차 로그인과 동일 정책 재사용** — `AdminSecurityConfig`가 이미 보유한 설정값을 주입(하드코딩 금지).

> **복구코드 합산**: 보고서가 "복구코드 브루트포스는 비현실적"이라 했으나, 통합 카운터라 복구코드 실패도 같은 카운터에 합산된다. 추가 비용 0, 방어만 강해지므로 합산 유지.

### 묶음 A 테스트 (TDD, 먼저 작성)

`MfaPendingFilterTest` + `MfaControllerTest` (MockMvc 슬라이스, Testcontainers 불필요):

1. `enroll-while-pending → 403` *(현재 공백 — finding의 핵심)*
2. `disable-while-pending → 403`, `confirm-while-pending → 403`
3. `verify-while-pending → 200` *(정상 경로 회귀 안전망)*
4. `verify N회 실패 → lockedUntil 설정 + 이후 거부`
5. `verify 성공 → failedLoginCount 리셋`
6. `lock 중 verify → 코드 맞아도 거부`

---

## 묶음 B — DB 권한/부트스트랩 (인프라)

### #3 — 런타임 GRANT ALL 제거 (HIGH)

**두 부트스트랩 모두**에서 `GRANT ALL PRIVILEGES TO APP_ADMIN_USER` 제거:
- `bootstrap-vpd.sql:90`
- `bootstrap-external.sql:89`

GRANT ALL을 빼면 APP_ADMIN_USER가 Flyway DDL을 못 하므로 **최소 권한으로 대체** (방식 1).

**방식 1 (채택): 마이그레이션 전용 권한 명시 GRANT.** APP_ADMIN_USER에 Flyway가 요구하는 DDL 권한만 명시 부여(예: `CREATE TABLE/SEQUENCE/INDEX`, `UNLIMITED TABLESPACE`, `ALTER` 등). audit_log는 V10:39의 `GRANT SELECT, INSERT ON audit_log TO APP_ADMIN`만 살아남아 append-only 무결성 복원. → admin-app 침해 시에도 audit_log DELETE/UPDATE 불가 → `AuditChainVerifier` 우회 차단.

> 방식 2(Flyway 전용 계정 완전 분리)는 더 깨끗하나 EXEMPT ACCESS POLICY·접속 설정 재배선이 커서 이번 범위 초과. 채택 안 함.

**⚠️ 정확한 권한 목록은 추측 금지 — Testcontainers에서 Flyway 마이그레이션을 실제로 돌려 실패→권한 추가 반복으로 확정한다** (메모리: Oracle 권한/DDL은 inspection이 못 잡음, Testcontainers 실행 필수).

**회귀 테스트 (Testcontainers, 필수):**
- 작성 시점에 **빨강이어야 정상** — 현재 GRANT ALL이라 UPDATE/DELETE가 성공하므로.
- `APP_ADMIN_USER로 audit_log UPDATE → ORA 권한 거부`
- `APP_ADMIN_USER로 audit_log DELETE → ORA 권한 거부`
- `APP_ADMIN_USER로 audit_log INSERT/SELECT → 성공`
- `축소된 권한으로 전체 Flyway 마이그레이션 완주`

### #2 — 외부 부트스트랩 비번 (HIGH)

**sqlplus DEFINE 치환 + fail-fast.** `bootstrap-external.sql`의 3계정(APP_OWNER/APP_RUNTIME_USER/APP_ADMIN_USER) 평문 비번(`app_owner_pw`/`runtime_pw`/`admin_pw`)을 외부 입력으로:

```sql
-- 비밀번호는 외부 입력. 미정의 시 부트스트랩 중단(fail-fast).
-- ⚠️ DBeaver 등 DEFINE 미지원 클라이언트: 아래 DEFINE 3줄을 강한 실제 비번으로
--    직접 치환한 뒤 실행하라. (sqlplus/init-db-external.sh 경로는 env 주입.)
DEFINE app_owner_pw = ''
DEFINE runtime_pw   = ''
DEFINE admin_pw     = ''

-- 빈 값 가드: 하나라도 비면 즉시 중단 (Oracle에서 '' IS NULL 이 참 → fail-closed)
BEGIN
  IF '&&app_owner_pw' IS NULL OR '&&runtime_pw' IS NULL OR '&&admin_pw' IS NULL THEN
    RAISE_APPLICATION_ERROR(-20001, '비밀번호 미정의 — DEFINE 또는 env 주입 필요');
  END IF;
END;
/
```

- `CREATE USER ... IDENTIFIED BY "&&app_owner_pw"` 형태로 치환 (큰따옴표로 특수문자 허용).
- `init-db-external.sh`: env(`APP_OWNER_PW`/`RUNTIME_PW`/`ADMIN_PW`)를 sqlplus에 DEFINE으로 전달, env가 비면 셸에서 먼저 중단.

**`bootstrap-vpd.sql` 비번은 이번 범위 제외.** 같은 평문 비번이지만 **docker-compose 전용**(내부 컨테이너 네트워크, 외부 미노출)이라 위협 등급이 낮고, 보고서 #2도 external만 confirmed. vpd에는 **#3 GRANT ALL 제거만** 적용하고 비번은 그대로 둔다 (docker-compose 흐름 재검증 부담 회피).

### 묶음 B 작업 순서

1. Testcontainers 회귀 테스트 먼저 작성 (audit_log UPDATE/DELETE 거부) — 현재 GRANT ALL이라 **실패가 정상**.
2. 두 SQL의 GRANT ALL → 최소 권한 교체, Flyway 완주할 때까지 권한 조정.
3. external 비번 DEFINE화 + `init-db-external.sh` env 주입.

---

## 에러 처리 & 검증 전략

- **A**: lock 발동 시 세션 무효화. 응답은 enumeration 방지 위해 기존 401 패턴 유지 검토(잠금 여부를 별도 코드로 노출하지 않음). enroll/disable/confirm pending 거부는 403 `mfa_required`로 통일.
- **B**: 비번 미주입 → 부트스트랩 즉시 중단(부분 생성 방지). **idempotency 보존** — 재실행 시 기존 계정/롤 EXCEPTION 무시 패턴(ORA-01920/01921) 유지.
- **공통**: 각 묶음 완료 후 `/codex:review`(메모리: 커밋 전 codex 리뷰 필수) → Testcontainers 그린 + 슬라이스 테스트 그린 확인 후 커밋.

---

## 범위 밖 (명시적 제외)

- medium 7건 / low 6건 / info 1건 — 별도 후속 spec.
- #3 방식 2(Flyway 전용 계정 완전 분리).
- `bootstrap-vpd.sql` 비번 외부화 (docker-compose 전용이라 등급 낮음).
- `MfaController.validCode`의 `mfaEnabled` 검사 추가 (정상 흐름 파괴 위험).
