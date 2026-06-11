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
| **B: DB 권한/부트스트랩 (인프라)** | #3 런타임 GRANT ALL (HIGH), #2 외부 부트스트랩 비번 (HIGH) | SQL/shell/config | `bootstrap-external.sql`, `bootstrap-vpd.sql`, `init-db-external.sh`, `admin-app application.yml`, V8 마이그레이션 |

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

> **lockout-DoS 트레이드오프(codex P2, 명시).** 공유 `lockedUntil`은 1차 로그인 게이트(`AdminUserDetails.isAccountNonLocked`)도 막는다. 즉 **비밀번호를 이미 가진 공격자가 MFA를 일부러 N회 실패시켜 정당 운영자를 락아웃**시킬 수 있다. 이는 수용 가능한 트레이드오프로 본다 — 이 시나리오는 **"비밀번호가 이미 유출된 = 계정 침해" 상태**이고, 그때 fail-closed(진입 차단)가 fail-open(브루트포스 허용)보다 낫다. **전제 조건**: (1) lock은 시간 경과로 자동 해제(`lockDuration` 후), (2) lock 발동 시 `log.warn` 보안 알림으로 운영자가 인지·대응 가능. 영구 락이 아니므로 가용성 영향은 한시적. 별도 운영자 unlock 경로가 필요하면 후속.

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

> **codex 리뷰 정정(2026-06-10).** 초안의 "방식 1: APP_ADMIN_USER에 최소 DDL grant"는 **WRONG**으로 판정됐다. Flyway가 `APP_OWNER` 스키마를 타겟(`application.yml:10` `schemas`/`default-schema`)하는데 datasource는 `APP_ADMIN_USER`라 **러너 ≠ 소유자**. 다른 스키마 객체를 마이그레이션하려면 `CREATE/ALTER/DROP ANY` 광범위 권한이 필요하고, 그걸 주면 admin-app 침해 시 **DDL로 audit_log를 여전히 파괴**할 수 있어 finding의 목적(append-only 복원)을 못 닫는다. 또 "audit_log SELECT+INSERT only로 축소"도 **WRONG** — 런타임은 audit_log만 쓰는 게 아니다(아래).

**채택: Approach A — Flyway를 스키마 소유자 `APP_OWNER`로 실행, 런타임은 `APP_ADMIN_USER` 유지, DDL 권한만 0으로.**

Spring Boot 3.5.14(`gradle/libs.versions.toml:2`)는 `spring.flyway.user`/`password`/`url`를 주 datasource와 **분리 지정** 가능하고, `url` 미설정 시 주 datasource로 폴백한다. → 앱 인스턴스 1개로 런타임=APP_ADMIN_USER, 마이그레이션=APP_OWNER 동시 가능.

**변경 1 — Flyway 자격증명 분리.** `admin-app` 설정에 `spring.flyway.user=APP_OWNER`(+ password) 추가. 런타임 `spring.datasource`는 APP_ADMIN_USER 그대로.

**변경 2 — 두 부트스트랩에서 `GRANT ALL PRIVILEGES TO APP_ADMIN_USER` 제거** (`bootstrap-vpd.sql:90`, `bootstrap-external.sql:89`).

**변경 3 — APP_ADMIN_USER 런타임 권한 = "DDL만 제거, 나머지 유지".** 다음은 **유지**해야 admin-app이 안 깨진다(codex가 코드에서 실증):
- `EXEMPT ACCESS POLICY` 유지 — V35:6이 "admin-app은 APP_ADMIN EXEMPT로 실행" 전제, cross-tenant는 앱 레이어 제어(`TenantBoundary.java:77` PLATFORM_OPERATOR는 tenant scope 없음, 서비스가 `findAll()`로 전테넌트 조회).
- 일반 admin 테이블 **DML 유지** — 런타임이 쓰는 테이블: `tenant`(TenantAdminService:148), `api_key`(ApiKeyAdminService:87), `admin_user`/초대(AdminUserService:41), `security_policy`(SecurityPolicyService:38), MDS 이력(MdsHistoryService:51). 이들은 V1/V7/V9/V26/V27/V31 등이 `APP_ADMIN` 롤에 부여하는 객체 GRANT로 충족.
- `audit_log`는 **SELECT+INSERT만**(V10:39 그대로 살아남음) → admin-app 침해 시에도 UPDATE/DELETE 불가 → `AuditChainVerifier` 우회 차단. **DDL 권한이 0이므로 DROP/TRUNCATE도 불가** — 이게 초안 방식 1이 못 막던 구멍.

**변경 4 — APP_OWNER 부트스트랩 권한 보강 2가지** (Flyway가 APP_OWNER로 돌기 위해 필요):
1. `GRANT CREATE VIEW TO APP_OWNER` 추가 — 현재 부트스트랩(`bootstrap-vpd.sql:51` 등)은 table/sequence/procedure/trigger/context만 grant, V40이 뷰 생성(`V40__readable_uuid_views.sql:25`)인데 권한 없음.
2. `GRANT EXEMPT ACCESS POLICY TO APP_OWNER`를 V8 마이그레이션 → SYS 부트스트랩으로 **이동**. Flyway가 APP_OWNER로 돌면 V8이 자기 자신에게 시스템 권한을 grant 못 한다(`V8__api_key_vpd_policy.sql:55`, definer-rights API key 패키지에 필요).

> APP_OWNER는 자기 객체에 대한 GRANT를 `APP_ADMIN`/`APP_RUNTIME` 롤에 부여 가능(`WITH GRANT OPTION` 불필요, 롤이 재grant 안 함). V10:39·V1:40·V7:46·V9:26의 기존 객체 GRANT는 그대로 동작.

> 방식 B(Flyway 전용 3번째 계정)는 기각 — 스키마 미소유 시 `CREATE/ALTER/DROP ANY` 광범위 권한 필요, A보다 런타임 보호 이득 없음. 진짜 구멍은 APP_ADMIN_USER의 ALL PRIVILEGES이고 A가 직접 닫는다.

**⚠️ 권한 목록은 추측 금지 — Testcontainers에서 APP_OWNER로 V1..V44 전체 마이그레이션을 실제로 돌려 실패→권한 추가 반복으로 확정한다** (메모리: Oracle 권한/DDL은 inspection이 못 잡음, Testcontainers 실행 필수). codex가 스캔한 비-단순 마이그레이션: V3(함수+DBMS_RLS), V8/V18/V19/V20/V42(APP_OWNER 패키지/함수), V19(DROP 패키지/테이블/시퀀스+VPD 정책 DROP), V35(VPD 정책), V40(뷰), 다수 ALTER/DROP 컬럼(V6/V21~V25/V29/V32/V33/V36/V37/V44).

**회귀 테스트 (Testcontainers, 필수):**
- 작성 시점에 **빨강이어야 정상** — 현재 GRANT ALL이라 UPDATE/DELETE가 성공하므로.
- `APP_ADMIN_USER로 audit_log UPDATE → ORA 권한 거부`
- `APP_ADMIN_USER로 audit_log DELETE → ORA 권한 거부`
- `APP_ADMIN_USER로 audit_log DROP/TRUNCATE → ORA 권한 거부` *(DDL 0 확인)*
- `APP_ADMIN_USER로 audit_log INSERT/SELECT → 성공`
- `APP_ADMIN_USER로 tenant/api_key/admin_user DML → 성공` *(런타임 안 깨짐 회귀)*
- `APP_OWNER로 전체 Flyway 마이그레이션(V1..V44) 완주`

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
- **가드 배치(codex GAP 지적)**: 빈 값 가드 PL/SQL 블록은 **반드시 첫 `CREATE USER`(현재 `bootstrap-external.sql:28`) 앞에** 둔다. 뒤에 있으면 계정이 약한 비번으로 이미 생성된 뒤라 무의미.
- **DEFINE 빈 기본값 = 비대화식 안전(codex 확인)**: `DEFINE x = ''`로 변수를 정의해두면 sqlplus가 대화식 프롬프트로 멈추지 않는다(undefined `&&var`라면 프롬프트→파이프 실행 hang). env 주입 경로는 빈 기본 `DEFINE` 줄 앞에 **구체 `DEFINE` 줄을 prepend**해 덮어쓴다.
- `init-db-external.sh`: env(`APP_OWNER_PW`/`RUNTIME_PW`/`ADMIN_PW`)를 sqlplus에 DEFINE으로 전달, env가 비면 셸에서 먼저 중단. **현재 `init-db-external.sh:93`이 `APP_ADMIN_USER/admin_pw`를 하드코딩하고 있으므로 함께 제거**(codex 지적).

**`bootstrap-vpd.sql` 비번은 이번 범위 제외 — 단, 잔존 위험 명시.** 같은 평문 비번이지만 **docker-compose 전용**이라 위협 등급이 낮아 보고서 #2도 external만 confirmed. 다만 codex가 `docker-compose.yml:6`에서 **Oracle 1521 포트가 호스트로 노출**됨을 지적: "엄격히 로컬 바인딩일 때만" 저위험이다. vpd에는 **#3 GRANT ALL 제거만** 적용하고 비번은 그대로 두되, **후속 백로그에 "1521을 `127.0.0.1`로 바인딩 또는 vpd 비번 외부화"를 기록**한다(이번 범위 밖, 별도 처리).

### 묶음 B 작업 순서

1. Testcontainers 회귀 테스트 먼저 작성 (audit_log UPDATE/DELETE/DROP 거부 + tenant/api_key DML 성공) — 현재 GRANT ALL이라 **거부 테스트가 빨강이어야 정상**.
2. `spring.flyway.user=APP_OWNER` 분리 설정 + 두 SQL의 `GRANT ALL PRIVILEGES TO APP_ADMIN_USER` 제거.
3. APP_OWNER 권한 보강(`CREATE VIEW`, V8의 `EXEMPT ACCESS POLICY` grant를 SYS 부트스트랩으로 이동) — Testcontainers에서 APP_OWNER로 V1..V44 완주할 때까지 권한 조정.
4. external 비번 DEFINE화(가드를 첫 CREATE USER 앞 배치) + `init-db-external.sh` env 주입 + `:93` 하드코딩 제거.

---

## 에러 처리 & 검증 전략

- **A**: lock 발동 시 세션 무효화. 응답은 enumeration 방지 위해 기존 401 패턴 유지 검토(잠금 여부를 별도 코드로 노출하지 않음). enroll/disable/confirm pending 거부는 403 `mfa_required`로 통일.
- **B**: 비번 미주입 → 부트스트랩 즉시 중단(부분 생성 방지). **idempotency 보존** — 재실행 시 기존 계정/롤 EXCEPTION 무시 패턴(ORA-01920/01921) 유지.
- **공통**: 각 묶음 완료 후 `/codex:review`(메모리: 커밋 전 codex 리뷰 필수) → Testcontainers 그린 + 슬라이스 테스트 그린 확인 후 커밋.

---

## 범위 밖 (명시적 제외)

- medium 7건 / low 6건 / info 1건 — 별도 후속 spec.
- #3 방식 B(Flyway 전용 3번째 계정) — 기각, Approach A 채택.
- `bootstrap-vpd.sql` 비번 외부화 + `docker-compose.yml:6`의 1521 포트를 `127.0.0.1` 바인딩 — **후속 백로그**(docker-compose 전용이라 등급 낮으나 호스트 노출은 codex가 지적, 별도 처리).
- `MfaController.validCode`의 `mfaEnabled` 검사 추가 (정상 흐름 파괴 위험).
- MFA lockout 운영자 unlock 경로 신설 (필요 시 후속 — 현재는 시간 경과 자동 해제로 충분).
