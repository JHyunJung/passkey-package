# DB 미사용 컬럼 정리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 코드에서 안 쓰이는 DB 컬럼 2개(`credential.backup_state`, `mds_blob_cache.blob_jwt`)와 죽은 `MdsBlobCacheRepository`를 제거한다.

**Architecture:** Flyway V47 마이그레이션으로 컬럼을 멱등 DROP하고, JPA 엔티티 필드·raw SQL·죽은 Repository·이를 참조하는 슬라이스 테스트 10개를 정리한다. 정방향 전용(롤백 스크립트 없음). 동작 변화 없음 — 두 컬럼 모두 읽는 코드가 없었다.

**Tech Stack:** Java 17, Spring Boot 3, JPA/Hibernate, Flyway, Oracle XE 21, Gradle.

---

## 작업 환경 (모든 Task 공통)

- **작업 디렉토리(worktree)**: `/Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/db-unused-cleanup`
- **브랜치**: `worktree-db-unused-cleanup`
- 모든 `git`/`./gradlew` 명령은 이 디렉토리에서 실행. 메인 repo가 아님을 매 Task 첫 명령으로 확인:
  ```bash
  cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/db-unused-cleanup
  git branch --show-current   # → worktree-db-unused-cleanup 이어야 함
  ```
- **인프라**: Oracle(`passkey-oracle`, 1521) + Redis(`passkey-redis`, 6379) 컨테이너가 떠 있어야 함. 확인: `docker ps --format '{{.Names}}' | grep -E 'passkey-oracle|passkey-redis'`. 없으면 메인 repo에서 `docker compose up -d`.
- **DB 접속**(검증용): `docker exec -i passkey-oracle bash -lc "sqlplus -S APP_OWNER/app_owner_pw@localhost:1521/XEPDB1"`. heredoc은 `<<'EOF'`로 감싼다.
- **빌드 주의**: 전체 `./gradlew build`는 pre-existing 함정(SliceConfig 충돌·Oracle 경합)이 있어 머지 게이트로 쓰지 않는다. 영향 모듈만 타겟 컴파일/테스트한다.

---

## File Structure

| 파일 | 책임 | 조치 |
|---|---|---|
| `core/src/main/resources/db/migration/V47__drop_unused_columns.sql` | 컬럼·제약 멱등 DROP | **생성** |
| `core/src/main/java/com/crosscert/passkey/core/entity/Credential.java` | credential 엔티티 매핑 | 필드 1개 제거 |
| `core/src/main/java/com/crosscert/passkey/core/entity/MdsBlobCache.java` | mds_blob_cache 엔티티 매핑 | 필드+getter 제거 |
| `core/src/main/java/com/crosscert/passkey/core/repository/MdsBlobCacheRepository.java` | (죽은) Repository | **삭제** |
| `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsBlobStore.java` | MDS BLOB 영속화 raw SQL | UPDATE에서 blob_jwt 제거 |
| `admin-app/src/test/.../*SecurityTest.java` (10개) | 슬라이스 테스트 | `@MockBean` 1줄씩 제거 |

---

## Task 1: V47 Flyway 마이그레이션 — 컬럼·제약 DROP

**Files:**
- Create: `core/src/main/resources/db/migration/V47__drop_unused_columns.sql`

마이그레이션 파일 최신 버전이 V46임을 확인했다(V47이 비어 있음). 이 Task는 SQL 파일만 추가하고, 실제 적용 검증은 Task 5(부팅)에서 한다.

- [ ] **Step 1: 현재 최신 마이그레이션 번호 확인 (V47 충돌 없음 확인)**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/db-unused-cleanup
ls core/src/main/resources/db/migration/ | grep -E '^V4[5-9]' | sort
```
Expected: `V45__...`, `V46__...`만 존재. `V47__`이 없어야 함. (있으면 다음 빈 번호로 변경.)

- [ ] **Step 2: V47 마이그레이션 작성**

Create `core/src/main/resources/db/migration/V47__drop_unused_columns.sql`:

```sql
-- ============================================================
-- V47 — 미사용 컬럼 정리
--
-- 코드 전수조사 결과 읽는 경로가 없는 컬럼 제거:
--   1. credential.backup_state (CLOB) + ck_credential_backup_state CHECK
--      — Credential.java 에 매핑만 있고 getter 호출 0. webauthn 의
--        backupState(BS 비트)는 무관한 별개.
--   2. mds_blob_cache.blob_jwt (BLOB) — MdsBlobStore 가 raw SQL 로 쓰기만,
--        읽는 코드 없음("감사·재검증용" 의도 미구현).
--
-- DROP COLUMN 은 NOT NULL 과 무관하게 동작 — V36 류 "MODIFY BLOB NOT NULL →
-- ORA-22296" 함정 해당 없음.
--
-- 멱등: 이미 없는 컬럼/제약은 EXCEPTION 으로 무시(재실행·환경차 안전).
--   ORA-00904 = 컬럼 없음, ORA-02443 = 제약 없음.
-- ============================================================

-- 1. credential CHECK 제약 먼저 제거 (컬럼보다 선행)
DECLARE
  e_constraint_missing EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_constraint_missing, -2443);
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE credential DROP CONSTRAINT ck_credential_backup_state';
EXCEPTION
  WHEN e_constraint_missing THEN NULL;
END;
/

-- 2. credential.backup_state 컬럼 제거
DECLARE
  e_column_missing EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_column_missing, -904);
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE credential DROP COLUMN backup_state';
EXCEPTION
  WHEN e_column_missing THEN NULL;
END;
/

-- 3. mds_blob_cache.blob_jwt 컬럼 제거
DECLARE
  e_column_missing EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_column_missing, -904);
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE mds_blob_cache DROP COLUMN blob_jwt';
EXCEPTION
  WHEN e_column_missing THEN NULL;
END;
/
```

- [ ] **Step 3: 커밋**

```bash
git add core/src/main/resources/db/migration/V47__drop_unused_columns.sql
git commit -m "feat(db): V47 미사용 컬럼 DROP — credential.backup_state, mds_blob_cache.blob_jwt"
```

---

## Task 2: MdsBlobStore raw SQL에서 blob_jwt 제거

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsBlobStore.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java:91` (유일한 호출부)

`store()`의 UPDATE 문이 `blob_jwt`에 쓰는 유일한 코드다. 컬럼 DROP 후 이 SQL이 그대로면 `ORA-00904`로 MDS sync가 깨진다 → 반드시 함께 제거.

현재 코드(line 37-54):
```java
    @Transactional
    public void store(String rawJwt, MdsBlob blob) {
        long version = blob.no();
        LocalDate nextUpdate = blob.nextUpdate();
        Instant now = clock.instant();
        int updated = jdbc.update(
                "UPDATE APP_OWNER.mds_blob_cache " +
                "SET version=?, next_update=?, fetched_at=?, blob_jwt=? " +
                "WHERE id=HEXTORAW('" + SINGLETON_HEX + "')",
                version,
                java.sql.Date.valueOf(nextUpdate),
                Timestamp.from(now),
                rawJwt);
        if (updated != 1) {
            throw new IncorrectUpdateSemanticsDataAccessException(
                    "mds_blob_cache sentinel row missing — V19 migration may not have run");
        }
    }
```

- [ ] **Step 1: 호출부 확인 — rawJwt 파라미터를 다른 곳에서도 쓰는지**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/db-unused-cleanup
grep -rn "\.store(" admin-app/src/main/java --include="*.java" | grep -i mds
```
**확인 완료**: 호출부는 단 1곳 — `MdsSchedulerService.java:91`:
```java
            store.store(fetched.rawJwt(), blob);
```
→ 시그니처를 `store(MdsBlob blob)`으로 바꾸고 이 호출을 `store.store(blob);`으로 수정한다(YAGNI — 안 쓰는 인자 제거). `fetched.rawJwt()`는 이 호출 외에 다른 용도가 있는지 같은 메서드 범위에서 확인 후, 없으면 그 지역변수/호출도 정리.

- [ ] **Step 2: UPDATE 문에서 blob_jwt 제거**

Replace `store()` body (위 코드)를 다음으로:
```java
    @Transactional
    public void store(MdsBlob blob) {
        long version = blob.no();
        LocalDate nextUpdate = blob.nextUpdate();
        Instant now = clock.instant();
        int updated = jdbc.update(
                "UPDATE APP_OWNER.mds_blob_cache " +
                "SET version=?, next_update=?, fetched_at=? " +
                "WHERE id=HEXTORAW('" + SINGLETON_HEX + "')",
                version,
                java.sql.Date.valueOf(nextUpdate),
                Timestamp.from(now));
        if (updated != 1) {
            throw new IncorrectUpdateSemanticsDataAccessException(
                    "mds_blob_cache sentinel row missing — V19 migration may not have run");
        }
    }
```
(rawJwt 파라미터 제거. Step 1에서 호출부가 rawJwt를 넘기면 그 호출도 `store(blob)`으로 수정.)

- [ ] **Step 3: 클래스 Javadoc 갱신**

`MdsBlobStore.java` line 15-22 Javadoc에서 "원본 BLOB JWT를 그대로 저장한다 — 감사·재검증 가능" 문장 제거. 다음으로 대체:
```java
/**
 * Persists the most recent verified MDS BLOB metadata into the singleton
 * row of {@code mds_blob_cache} (seeded by V19, id = SINGLETON_ID).
 *
 * <p>The verifier path uses the parsed entries cached in Redis
 * (MdsSchedulerService T16); only version/next_update/fetched_at are stored here.
 */
```

- [ ] **Step 4: import 정리 — 사용 안 하게 된 import 제거**

rawJwt 제거로 미사용된 import가 있는지 확인(없을 가능성 큼 — String은 다른 데서 쓰임). 컴파일 경고 확인:
```bash
./gradlew :admin-app:compileJava -q 2>&1 | grep -iE "warning|unused" | head
```

- [ ] **Step 5: admin-app 컴파일 통과 확인**

Run:
```bash
./gradlew :admin-app:compileJava
```
Expected: BUILD SUCCESSFUL. (core 엔티티는 아직 안 고쳤지만 blob_jwt 컬럼 매핑이 남아 있어도 raw SQL과 무관하게 컴파일됨.)

- [ ] **Step 6: 커밋**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsBlobStore.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java
git commit -m "refactor(mds): MdsBlobStore UPDATE 에서 blob_jwt 제거 + 호출부 시그니처 정리"
```

---

## Task 3: 엔티티 필드 제거 (Credential, MdsBlobCache)

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/Credential.java:45-47`
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/MdsBlobCache.java:39-41, 66`

두 필드 모두 엔티티 외부에서 접근자가 없다(Credential.backupStateJson은 getter/setter 자체가 없음; MdsBlobCache.blobJwt는 getBlobJwt()가 있으나 호출 0건).

- [ ] **Step 1: Credential.backupStateJson 필드 제거**

`Credential.java`에서 다음 3줄(45-47) 제거:
```java
    @Lob
    @Column(name = "BACKUP_STATE")
    private String backupStateJson;
```
(getter/setter 없음 — 추가 제거 불필요. 생성자·recordAuthentication도 이 필드 미참조.)

- [ ] **Step 2: MdsBlobCache.blobJwt 필드 + getter 제거**

`MdsBlobCache.java`에서:
- line 39-41 제거:
  ```java
      @Lob
      @Column(name = "BLOB_JWT", nullable = false)
      private String blobJwt;
  ```
- line 66 getter 제거:
  ```java
      public String getBlobJwt() { return blobJwt; }
  ```

- [ ] **Step 3: core 컴파일 통과 확인**

Run:
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/db-unused-cleanup
./gradlew :core:compileJava
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: blobJwt 잔존 참조 없음 확인**

```bash
grep -rn "backupStateJson\|getBlobJwt\|blobJwt" core admin-app passkey-app --include="*.java" | grep -v "test"
```
Expected: 출력 없음(프로덕션 코드에 잔존 참조 0).

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/Credential.java \
        core/src/main/java/com/crosscert/passkey/core/entity/MdsBlobCache.java
git commit -m "refactor(entity): 미사용 필드 제거 — Credential.backupStateJson, MdsBlobCache.blobJwt"
```

---

## Task 4: 죽은 MdsBlobCacheRepository + 슬라이스 테스트 @MockBean 제거

**Files:**
- Delete: `core/src/main/java/com/crosscert/passkey/core/repository/MdsBlobCacheRepository.java`
- Modify: 10개 슬라이스 테스트 — 각 `@MockBean ... MdsBlobCacheRepository ...;` 1줄 제거

확인 완료: 이 Repository는 프로덕션 주입 0건, 테스트 `@MockBean` 선언 외 stubbing/verify 0건 → 줄 삭제 안전. 선언은 FQN 인라인 한 줄이라 별도 import 제거 불필요.

대상 10개 파일:
```
admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminControllerSecurityTest.java
admin-app/src/test/java/com/crosscert/passkey/admin/config/MeControllerSecurityTest.java
admin-app/src/test/java/com/crosscert/passkey/admin/license/LicenseGuardFilterIT.java
admin-app/src/test/java/com/crosscert/passkey/admin/system/SystemInfoControllerSecurityTest.java
admin-app/src/test/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtControllerSecurityTest.java
admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminControllerSecurityTest.java
admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditLogControllerSecurityTest.java
admin-app/src/test/java/com/crosscert/passkey/admin/mds/MdsAdminControllerSecurityTest.java
admin-app/src/test/java/com/crosscert/passkey/admin/policy/SecurityPolicyControllerSecurityTest.java
admin-app/src/test/java/com/crosscert/passkey/admin/policy/AaguidPolicyControllerSecurityTest.java
```

- [ ] **Step 1: 각 테스트에서 @MockBean 줄 제거 (sed로 일괄)**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/db-unused-cleanup
for f in \
  admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminControllerSecurityTest.java \
  admin-app/src/test/java/com/crosscert/passkey/admin/config/MeControllerSecurityTest.java \
  admin-app/src/test/java/com/crosscert/passkey/admin/license/LicenseGuardFilterIT.java \
  admin-app/src/test/java/com/crosscert/passkey/admin/system/SystemInfoControllerSecurityTest.java \
  admin-app/src/test/java/com/crosscert/passkey/admin/keymgmt/KeyMgmtControllerSecurityTest.java \
  admin-app/src/test/java/com/crosscert/passkey/admin/apikey/ApiKeyAdminControllerSecurityTest.java \
  admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditLogControllerSecurityTest.java \
  admin-app/src/test/java/com/crosscert/passkey/admin/mds/MdsAdminControllerSecurityTest.java \
  admin-app/src/test/java/com/crosscert/passkey/admin/policy/SecurityPolicyControllerSecurityTest.java \
  admin-app/src/test/java/com/crosscert/passkey/admin/policy/AaguidPolicyControllerSecurityTest.java ; do
  # MdsBlobCacheRepository 를 포함하는 @MockBean 줄 삭제
  sed -i '' '/MdsBlobCacheRepository/d' "$f"
done
```
(macOS sed: `-i ''` 필수. 메모리 "BSD sed" 주의 — 여기선 `\b` 안 쓰므로 안전.)

- [ ] **Step 2: 제거 확인 — 잔존 참조 0**

```bash
grep -rn "MdsBlobCacheRepository" admin-app/src/test --include="*.java"
```
Expected: 출력 없음.

- [ ] **Step 3: Repository 파일 삭제**

```bash
git rm core/src/main/java/com/crosscert/passkey/core/repository/MdsBlobCacheRepository.java
```

- [ ] **Step 4: 전 모듈에서 MdsBlobCacheRepository 잔존 import/참조 0 확인**

```bash
grep -rn "MdsBlobCacheRepository" core admin-app passkey-app rp-app --include="*.java" --include="*.kt"
```
Expected: 출력 없음.

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "refactor(mds): 죽은 MdsBlobCacheRepository + 슬라이스 테스트 @MockBean 10개 제거"
```

---

## Task 5: 통합 검증 — 부팅 + DB 컬럼 DROP 확인

**Files:** (코드 변경 없음 — 검증 전용)

이 Task는 V47이 실제 적용되고, 엔티티 매핑과 DB 스키마가 일치하며, 앱이 정상 부팅하는지 확인한다.

- [ ] **Step 1: 인프라 가용 확인**

```bash
docker ps --format '{{.Names}}\t{{.Status}}' | grep -E 'passkey-oracle|passkey-redis'
```
Expected: 둘 다 Up. (oracle healthy.) 없으면 메인 repo에서 `docker compose up -d` 후 healthy 대기.

- [ ] **Step 2: DROP 전 컬럼 존재 확인 (baseline)**

```bash
docker exec -i passkey-oracle bash -lc "sqlplus -S APP_OWNER/app_owner_pw@localhost:1521/XEPDB1" <<'EOF'
SET PAGESIZE 50 FEEDBACK OFF
SELECT table_name, column_name FROM all_tab_columns
 WHERE owner='APP_OWNER'
   AND ((table_name='CREDENTIAL' AND column_name='BACKUP_STATE')
     OR (table_name='MDS_BLOB_CACHE' AND column_name='BLOB_JWT'));
EXIT
EOF
```
Expected: 2행(BACKUP_STATE, BLOB_JWT) — 아직 존재.

- [ ] **Step 3: admin-app dev 부팅 (Flyway V47 적용)**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/db-unused-cleanup
SPRING_PROFILES_ACTIVE=dev ./gradlew :admin-app:bootRun > /tmp/db-cleanup-boot.log 2>&1 &
```
8081 오픈 또는 실패까지 대기(Monitor/until-loop). 부팅 성공 신호: 로그에 `Started AdminApplication`.
Flyway 로그에 `Migrating schema "APP_OWNER" to version "47 - drop unused columns"` 또는 적용 흔적 확인:
```bash
grep -iE "V47|47 - drop|drop unused|Successfully applied" /tmp/db-cleanup-boot.log
```

- [ ] **Step 4: DROP 후 컬럼 부재 확인**

```bash
docker exec -i passkey-oracle bash -lc "sqlplus -S APP_OWNER/app_owner_pw@localhost:1521/XEPDB1" <<'EOF'
SET PAGESIZE 50 FEEDBACK OFF
SELECT table_name, column_name FROM all_tab_columns
 WHERE owner='APP_OWNER'
   AND ((table_name='CREDENTIAL' AND column_name='BACKUP_STATE')
     OR (table_name='MDS_BLOB_CACHE' AND column_name='BLOB_JWT'));
SELECT constraint_name FROM all_constraints
 WHERE owner='APP_OWNER' AND constraint_name='CK_CREDENTIAL_BACKUP_STATE';
EXIT
EOF
```
Expected: 0행(컬럼·제약 모두 제거됨).

- [ ] **Step 5: health UP 확인**

```bash
curl -s -w "\n[%{http_code}]\n" http://localhost:8081/actuator/health
```
Expected: `status:UP`, db Oracle UP, [200]. (엔티티 매핑이 DB와 일치 → Hibernate 검증 통과 의미.)

- [ ] **Step 6: 부팅 프로세스 정리**

```bash
lsof -ti:8081 | xargs kill 2>/dev/null; echo "admin-app stopped"
```

---

## Task 6: 타겟 테스트 그린 — 영향 슬라이스 테스트

**Files:** (코드 변경 없음 — 검증 전용)

@MockBean 제거로 컨텍스트 로드가 깨지지 않는지(메모리 "슬라이스 MockBean 회귀" 역방향 리스크) 확인. MDS 관련 + 샘플 슬라이스 테스트를 실행한다.

- [ ] **Step 1: MDS 슬라이스 테스트 실행**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/db-unused-cleanup
./gradlew :admin-app:test --tests "*MdsAdminControllerSecurityTest" --tests "*MeControllerSecurityTest"
```
Expected: BUILD SUCCESSFUL, 두 슬라이스 컨텍스트 로드 성공(@MockBean 제거 후에도). 실패 시 — 어떤 빈이 MdsBlobCacheRepository에 의존했다는 뜻 → 그 의존을 찾아 함께 정리(설계 D의 단서와 배치).

- [ ] **Step 2: core 모듈 테스트 (엔티티 변경 영향)**

```bash
./gradlew :core:test 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL 또는 pre-existing 실패만(base 대비 신규 실패 없음). 신규 실패 시 backupStateJson/blobJwt 참조 테스트가 있었다는 뜻 → 해당 테스트 수정.

- [ ] **Step 3: base 대비 회귀 없음 최종 확인 (선택)**

신규 실패가 의심되면 base 브랜치(main HEAD)와 비교:
```bash
git stash list  # 변경 없음 확인용
# 의심 테스트만 main 체크아웃 worktree에서 대조 (메모리 "full build preexisting traps" 방식)
```

---

## Task 7: 정리 완료 보고 + 머지 준비

**Files:** (없음)

- [ ] **Step 1: 변경 요약 확인**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/db-unused-cleanup
git log --oneline 98599b6..HEAD
git diff --stat 98599b6..HEAD
```
Expected: Task 1-4의 커밋 4개. V47 신규 + 엔티티 2 + raw SQL 1 + Repository 삭제 + 테스트 10.

- [ ] **Step 2: finishing-a-development-branch 스킬로 머지 옵션 제시**

REQUIRED SUB-SKILL: `superpowers:finishing-a-development-branch` — main으로 `--no-ff` 머지할지 PR 낼지 사용자에게 확인.

---

## Self-Review 메모

- **Spec 커버리지**: 설계 A(V47)=Task1, B(엔티티)=Task3, C(raw SQL)=Task2, D(Repository+테스트)=Task4, E(데이터확인)=Task5 Step2. 검증=Task5,6. 전 항목 매핑됨.
- **순서 주의**: Task2(raw SQL)가 Task1(DROP) 적용보다 코드상 먼저 머지돼야 런타임 ORA-00904 회피. 같은 브랜치/PR이라 부팅 시 Flyway→앱로드 순서로 안전. Task 순서는 1→2→3→4→5(부팅)로, 부팅 시점엔 raw SQL이 이미 수정돼 있음 ✓.
- **타입 일관성**: `store(String rawJwt, MdsBlob blob)` → `store(MdsBlob blob)` 시그니처 변경을 Task2에서 호출부까지 반영(Step1에서 호출부 추적 명시).
- **placeholder 없음**: 모든 코드 블록 실제 내용 포함.
