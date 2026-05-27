# Phase B — Audit Chain Per-Tenant Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** AuditLog 의 단일 글로벌 hash chain 에 **tenant 별 분리 체인** 을 동시 유지하여, 운영자가 tenant 단위로 무결성 검증 + 24h sparkline 조회 + 위변조 tenant 식별 가능하게 한다.

**Architecture:**
- 글로벌 chain (`PREV_HASH`/`HASH`, RAW(32)) 그대로 유지 — 테이블 전체 append-only 무결성 보장 (row 삭제 공격 감지)
- tenant chain 추가 (`TENANT_PREV_HASH`/`TENANT_HASH`, RAW(32)) — 같은 tenant 내 row 변조 검증, isolation 제공
- append 시 단일 `AUDIT_CHAIN_LOCK` 로 두 chain 모두 직렬화 (분리 락은 불필요 — 단순함이 안전성)
- 백필은 idempotent endpoint (`POST /admin/api/audit/chain/backfill`) — 이미 채워진 row 는 skip
- 디자인 spec 변경: spec V24~V27 → 실제 V25~V28 (V24 가 이미 사용 중)

**Tech Stack:** Spring Boot 3 + Oracle 19c + JPA + Flyway

---

## File Structure

**Create (server)**
- `core/src/main/resources/db/migration/V25__audit_log_tenant_chain.sql`
- `core/src/main/java/com/crosscert/passkey/core/repository/AuditLogRepository.java` (메서드 추가)
- `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainBackfillService.java`
- `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainMonitorController.java`
- `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainOverview.java` (DTO record)
- `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainTenantOverview.java` (DTO record)
- `admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditChainPerTenantIT.java`
- `admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditChainBackfillIT.java`

**Modify (server)**
- `core/src/main/java/com/crosscert/passkey/core/entity/AuditLog.java` — `tenantPrevHash`, `tenantHash` 필드 + getter
- `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogService.java` — append 시 tenant chain 계산
- `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainVerifier.java` — `verifyTenant(UUID)`, `verifyAllTenants()` 추가, 기존 `verify()` (글로벌) 유지

**Create (UI)**
- `admin-ui/src/pages/AuditChainMonitor.tsx` — 신규 페이지 (PLATFORM_OPERATOR `/audit-chain`)
- `admin-ui/src/api/auditChain.ts` — API 클라이언트 헬퍼

**Modify (UI)**
- `admin-ui/src/App.tsx` — `/audit-chain` 라우트 추가
- `admin-ui/src/shell/Sidebar.tsx` — PLATFORM_NAV 에 Audit Chain 추가
- `admin-ui/src/api/types.ts` — overview/verify 응답 타입

**Tests:** IT 2개 (per-tenant isolation, backfill idempotency). 그 외 수동 smoke.

---

## Conventions

- **Working dir base**: `/Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/audit-chain-per-tenant`
- 서버 빌드: `./gradlew :admin-app:compileJava :admin-app:compileTestJava` (전체 빌드는 시간 걸림)
- 서버 테스트: `./gradlew :admin-app:test --tests "*AuditChainPerTenantIT" --tests "*AuditChainBackfillIT"`
- UI 빌드: `cd admin-ui && npm run build`
- **commit 전 codex review**: 각 task 마지막 commit step 직전 `codex review` 실행 (실행 불가 시 skip + 보고)
- commit prefix:
  - 마이그레이션: `chore(core): V25 ...` 또는 `refactor(core): ...`
  - 서비스: `feat(admin-app): ...`
  - UI: `feat(admin-ui): ...`
  - 테스트: `test(admin-app): ...`
- 한국어 주석 OK

---

## Task 1: V25 마이그레이션 — tenant chain 컬럼 추가

**Files:**
- Create: `core/src/main/resources/db/migration/V25__audit_log_tenant_chain.sql`

- [ ] **Step 1.1: V25 SQL 작성**

```sql
-- ============================================================
-- V25 — audit_log per-tenant hash chain 컬럼 추가
--
-- 목적: tenant 단위 무결성 검증 + sparkline + 위변조 tenant 식별
--   (Phase B Audit Chain Monitor 화면)
--
-- 결정:
--   - 기존 글로벌 chain (PREV_HASH/HASH) 유지 — 테이블 전체 append-only
--     무결성 보장 (row 삭제 공격 감지).
--   - tenant chain 컬럼 2개 추가 — 같은 tenant 내부 row 변조 검증.
--     append 시 두 chain 동시 계산, 단일 AUDIT_CHAIN_LOCK 으로 직렬화.
--   - platform-level audit (tenant_id NULL) 은 별도 tenant chain
--     (TENANT_ID IS NULL) 로 묶음.
--
-- 백필: V25 안에서 하지 않음. 신규 INSERT 부터 tenant chain 자동 채워짐.
--   기존 row (tenant_prev_hash/tenant_hash IS NULL) 는 PLATFORM_OPERATOR 가
--   POST /admin/api/audit/chain/backfill 명시 호출.
--
-- Idempotency: ALTER ADD / CREATE INDEX 는 재실행 시 ORA-01430 / ORA-00955
-- 로 실패. EXCEPTION 으로 감싸 멱등.
-- ============================================================

-- 1. tenant_prev_hash 컬럼 추가 — ORA-01430 swallow
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE audit_log ADD (tenant_prev_hash RAW(32))';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -1430 THEN NULL;
    ELSE RAISE;
    END IF;
END;
/

-- 2. tenant_hash 컬럼 추가 — ORA-01430 swallow
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE audit_log ADD (tenant_hash RAW(32))';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -1430 THEN NULL;
    ELSE RAISE;
    END IF;
END;
/

-- 3. tenant chain 순회용 인덱스 — ORA-00955 swallow
--    (tenant_id, id) 로 tenant 별 순서 보장 (id 는 PK + sequence 단조 증가)
BEGIN
  EXECUTE IMMEDIATE 'CREATE INDEX audit_log_tenant_seq_ix ON audit_log (tenant_id, id)';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -955 THEN NULL;
    ELSE RAISE;
    END IF;
END;
/

-- 4. 권한 변경 없음 — 컬럼 추가는 기존 SELECT/INSERT grant 를 상속.
--    UPDATE 권한 여전히 미부여 (append-only 유지).
```

- [ ] **Step 1.2: Flyway 검증 (재실행 안전)**

```bash
./gradlew :admin-app:compileJava 2>&1 | tail -3
```

SQL 검증은 IT 에서 진행. 이 task 에서는 컴파일 통과만 확인.

- [ ] **Step 1.3: codex review**

`codex review`. issue 발견 시 fix.

- [ ] **Step 1.4: Commit**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/audit-chain-per-tenant
git add core/src/main/resources/db/migration/V25__audit_log_tenant_chain.sql
git commit -m "chore(core): V25 audit_log tenant_prev_hash/tenant_hash 컬럼 (Phase B.1)"
```

---

## Task 2: AuditLog entity 에 tenant chain 필드 추가

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/AuditLog.java`

- [ ] **Step 2.1: 필드 + 생성자 + getter 추가**

기존 `AuditLog.java` 의 `tenantId` 필드 **다음** 위치에 다음 2개 필드 삽입:

```java
@Column(name = "TENANT_PREV_HASH", length = 32)
private byte[] tenantPrevHash;

@Column(name = "TENANT_HASH", length = 32)
private byte[] tenantHash;
```

`hash` 필드는 `nullable = false` 지만 `tenantHash` 는 **nullable** (기존 row 백필 전 NULL 허용).

기존 public 생성자에 두 인자 추가:

```java
public AuditLog(byte[] prevHash, byte[] hash, UUID actorId, String actorEmail,
                String action, String targetType, String targetId,
                UUID tenantId,
                byte[] tenantPrevHash, byte[] tenantHash,   // 신규
                String payload, Instant createdAtArg) {
    this.prevHash = prevHash;
    this.hash = hash;
    this.actorId = actorId;
    this.actorEmail = actorEmail;
    this.action = action;
    this.targetType = targetType;
    this.targetId = targetId;
    this.tenantId = tenantId;
    this.tenantPrevHash = tenantPrevHash;
    this.tenantHash = tenantHash;
    this.payload = payload;
    seedTimestamps(createdAtArg);
}
```

기존 생성자 호출처 (`AuditLogService.append`) 도 다음 task 에서 업데이트 — 그래야 컴파일 OK.

getter 추가:
```java
public byte[] getTenantPrevHash() { return tenantPrevHash; }
public byte[] getTenantHash() { return tenantHash; }

// 백필용 setter — package-private (audit/ 패키지에서만 호출)
void setTenantPrevHash(byte[] v) { this.tenantPrevHash = v; }
void setTenantHash(byte[] v) { this.tenantHash = v; }
```

**중요**: 기존 생성자 시그니처가 바뀌므로 다음 task 에서 호출처 (`AuditLogService.append`) 를 업데이트해야 컴파일 통과. 이 task 만 단독 commit 하지 말고 Task 3 의 끝에서 같이 commit.

- [ ] **Step 2.2: 컴파일은 Task 3 와 함께 검증 — 이 task 는 commit 안 함**

기존 생성자 호출처를 같이 바꿔야 컴파일이 통과하므로, **이 task 의 변경은 stage 만 하고 Task 3 끝에서 같이 commit**.

```bash
# stage 만, commit 안 함
git add core/src/main/java/com/crosscert/passkey/core/entity/AuditLog.java
git status --short
```

- [ ] **Step 2.3: Task 3 진행 → 그 안에서 함께 commit**

---

## Task 3: AuditLogService.append 가 tenant chain 동시 계산

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogService.java`
- Modify: `core/src/main/java/com/crosscert/passkey/core/repository/AuditLogRepository.java` (메서드 추가)

**Tenant chain 의 hash 입력 형식**: 글로벌 chain 과 동일한 pipe-delimited 형식. 단 `prev_hash_hex` 자리에 `tenant_prev_hash` 가 들어감. 같은 함수 `computeHash` 재사용.

- [ ] **Step 3.1: AuditLogRepository 에 tenant 별 latest hash 조회 메서드 추가**

기존 `findLatestForUpdate()` 패턴 따라 추가. 단 같은 트랜잭션 안에서 `AUDIT_CHAIN_LOCK` 가 이미 잡혀 있으므로 PESSIMISTIC_WRITE 없이도 안전 — 단순 SELECT.

```java
// AuditLogRepository.java 에 추가
@org.springframework.data.jpa.repository.Query("""
    select a from AuditLog a
    where (:tenantId is null and a.tenantId is null)
       or (a.tenantId = :tenantId)
    order by a.id desc
    """)
Optional<AuditLog> findLatestByTenant(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
                                       org.springframework.data.domain.Pageable limit);
```

호출 시 `PageRequest.of(0, 1)` 로 한 row 만. Optional 변환은 호출자가 `.stream().findFirst()` 또는 별도 wrapper.

**더 단순한 방법**: native query 로 직접:
```java
@org.springframework.data.jpa.repository.Query(value = """
    SELECT * FROM (
      SELECT * FROM audit_log
      WHERE (:tenantId IS NULL AND tenant_id IS NULL)
         OR (tenant_id = :tenantId)
      ORDER BY id DESC
    ) WHERE ROWNUM = 1
    """, nativeQuery = true)
Optional<AuditLog> findLatestByTenant(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId);
```

Oracle ROWNUM 패턴. UUID 가 RAW(16) 으로 매핑되는지 확인 필요 — 기존 코드는 JdbcTypeCode 로 UUID↔RAW 변환을 사용하므로 :tenantId 가 UUID 면 Hibernate 가 자동 변환할 것. 안되면 String form 으로 변환해서 binary 비교.

**추천**: JPQL 버전 사용 (더 안전, 타입 안전성). Pageable 로 LIMIT 1.

- [ ] **Step 3.2: AuditLogService.append 변경**

기존 메서드 안에서 `byte[] prevHash = repo.findLatestForUpdate()...` 다음에 tenant 버전 추가:

```java
// 기존 글로벌 prev
byte[] prevHash = repo.findLatestForUpdate()
        .map(AuditLog::getHash)
        .orElse(null);

// 신규: tenant prev (같은 트랜잭션 + 같은 락 보호 — 별도 락 불필요)
byte[] tenantPrev = repo.findLatestByTenant(
        req.tenantId(),
        org.springframework.data.domain.PageRequest.of(0, 1))
    .map(AuditLog::getTenantHash)
    .orElse(null);

Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
String payloadJson = serialize(req.payload());

// 글로벌 hash (기존)
byte[] hash = computeHash(prevHash, req, payloadJson, now);

// tenant hash (신규) — 같은 computeHash 사용. prev 만 tenantPrev 로 바꿔서 호출
byte[] tenantHash = computeHash(tenantPrev, req, payloadJson, now);

AuditLog row = new AuditLog(
        prevHash, hash,
        req.actorId(), req.actorEmail(),
        req.action(),
        req.targetType(), req.targetId(),
        req.tenantId(),
        tenantPrev, tenantHash,    // 신규
        payloadJson, now);
return repo.save(row);
```

**중요**: `computeHash` 의 입력은 prev_hash + 모든 다른 필드. 글로벌과 tenant 가 같은 row 에 대해 호출하면 prev 만 다르고 나머지는 같으므로 두 hash 가 다른 값을 가짐 — 각 chain 의 격리가 보장됨.

**대안 검토**: `Optional<Pageable>` 호환성 이슈가 있을 수 있음. Pageable 받는 메서드를 `List<AuditLog> findLatestByTenant(UUID, Pageable)` 로 바꿔 `.stream().findFirst()` 로 처리하는 게 더 안전:

```java
List<AuditLog> rows = repo.findLatestByTenant(req.tenantId(), PageRequest.of(0, 1));
byte[] tenantPrev = rows.isEmpty() ? null : rows.get(0).getTenantHash();
```

repository 메서드도 `List<AuditLog>` 반환으로 작성:
```java
@Query("""
    select a from AuditLog a
    where (:tenantId is null and a.tenantId is null)
       or (a.tenantId = :tenantId)
    order by a.id desc
    """)
List<AuditLog> findLatestByTenant(@Param("tenantId") UUID tenantId, Pageable limit);
```

- [ ] **Step 3.3: 컴파일 검증**

```bash
./gradlew :admin-app:compileJava :admin-app:compileTestJava 2>&1 | tail -8
```

0 에러. 기존 테스트의 AuditLog 생성자 호출처가 깨질 수 있으니 — 모두 새 시그니처 (tenantPrevHash, tenantHash 인자 추가) 로 수정. grep:

```bash
grep -rn "new AuditLog(" --include="*.java" core/ admin-app/ passkey-app/ | head -10
```

각 호출처를 새 시그니처에 맞춰 수정 (null 두 개 추가).

- [ ] **Step 3.4: codex review**

`codex review`

- [ ] **Step 3.5: Commit (Task 2 + Task 3 합쳐서)**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/AuditLog.java \
        core/src/main/java/com/crosscert/passkey/core/repository/AuditLogRepository.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogService.java
# 추가: 기존 호출처 수정 파일들 (grep 결과)
git add <필요한 다른 파일들>
git commit -m "feat(admin-app): AuditLog tenant chain 컬럼 + append 시 동시 계산 (Phase B.2+3)"
```

---

## Task 4: AuditChainVerifier — per-tenant 검증 추가

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainVerifier.java`
- Modify: `core/src/main/java/com/crosscert/passkey/core/repository/AuditLogRepository.java` (tenant 별 ordered query 추가)

- [ ] **Step 4.1: Repository 에 tenant 별 정렬 조회 추가**

```java
@Query("""
    select a from AuditLog a
    where (:tenantId is null and a.tenantId is null)
       or (a.tenantId = :tenantId)
    order by a.id asc
    """)
List<AuditLog> findAllByTenantOrdered(@Param("tenantId") UUID tenantId);

@Query("select distinct a.tenantId from AuditLog a")
List<UUID> findDistinctTenantIds();
```

`findDistinctTenantIds()` 는 NULL 도 결과에 포함 (JPQL distinct 가 NULL 처리하는 방식 확인 필요 — 안 되면 native query 로):

```java
@Query(value = "SELECT DISTINCT tenant_id FROM audit_log", nativeQuery = true)
List<UUID> findDistinctTenantIds();
```

- [ ] **Step 4.2: AuditChainVerifier 에 verifyTenant + verifyAllTenants 추가**

기존 `Result` record 옆에 tenant 결과 record + List wrapper:

```java
public record TenantResult(UUID tenantId, boolean ok, UUID brokenAt) {
    public static TenantResult valid(UUID tenantId) { return new TenantResult(tenantId, true, null); }
    public static TenantResult broken(UUID tenantId, UUID id) { return new TenantResult(tenantId, false, id); }
}

public TenantResult verifyTenant(UUID tenantId) {
    List<AuditLog> rows = repo.findAllByTenantOrdered(tenantId);
    byte[] expectedPrev = null;
    for (AuditLog row : rows) {
        if (!Arrays.equals(expectedPrev, row.getTenantPrevHash())) {
            return TenantResult.broken(tenantId, row.getId());
        }
        byte[] expected = recomputeTenantHash(row);
        if (!Arrays.equals(expected, row.getTenantHash())) {
            return TenantResult.broken(tenantId, row.getId());
        }
        expectedPrev = row.getTenantHash();
    }
    return TenantResult.valid(tenantId);
}

public List<TenantResult> verifyAllTenants() {
    List<UUID> tenantIds = repo.findDistinctTenantIds();
    List<TenantResult> out = new java.util.ArrayList<>(tenantIds.size());
    for (UUID id : tenantIds) {
        out.add(verifyTenant(id));
    }
    return out;
}

private byte[] recomputeTenantHash(AuditLog row) {
    try {
        Map<String, Object> payload = canonical.readValue(
                row.getPayload(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        AuditAppendRequest req = new AuditAppendRequest(
                row.getActorId(), row.getActorEmail(), row.getAction(),
                row.getTargetType(), row.getTargetId(),
                row.getTenantId(),
                payload);
        return AuditLogService.computeHash(
                row.getTenantPrevHash(), req, row.getPayload(), row.getCreatedAt());
    } catch (Exception e) {
        return new byte[0];
    }
}
```

기존 `verify()` (글로벌) 는 그대로 둔다.

- [ ] **Step 4.3: 컴파일**
```bash
./gradlew :admin-app:compileJava :admin-app:compileTestJava 2>&1 | tail -5
```

- [ ] **Step 4.4: codex review**

- [ ] **Step 4.5: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainVerifier.java \
        core/src/main/java/com/crosscert/passkey/core/repository/AuditLogRepository.java
git commit -m "feat(admin-app): AuditChainVerifier verifyTenant/verifyAllTenants (Phase B.4)"
```

---

## Task 5: BackfillService + 백필 endpoint

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainBackfillService.java`

- [ ] **Step 5.1: BackfillService 작성**

```java
package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 기존 audit_log row 의 tenant chain 컬럼 (V25 신규) 을 채우는 백필 서비스.
 *
 * <p>호출 흐름: PLATFORM_OPERATOR 가 POST /admin/api/audit/chain/backfill 호출 →
 *   {@link #backfill()} 실행 → tenant_id 별로 id ASC 순회 → tenant chain 재계산.
 *
 * <p>Idempotent: tenant_hash 가 이미 채워진 row 는 skip. 같은 트랜잭션 안에서
 *   AUDIT_CHAIN_LOCK 을 잡아 동시 append 와 직렬화.
 */
@Service
public class AuditChainBackfillService {

    private final AuditLogRepository repo;
    private final EntityManager em;
    private final ObjectMapper canonical;

    public AuditChainBackfillService(AuditLogRepository repo, EntityManager em, ObjectMapper baseMapper) {
        this.repo = repo;
        this.em = em;
        this.canonical = baseMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public record Summary(int tenantsProcessed, int rowsUpdated, int rowsSkipped) {}

    @Transactional
    public Summary backfill() {
        // 단일 chain lock — append 와 직렬화
        em.createNativeQuery(
                "SELECT 1 FROM APP_OWNER.scheduler_lease WHERE name = :n FOR UPDATE")
            .setParameter("n", AuditLogService.CHAIN_LOCK_NAME)
            .getSingleResult();

        List<UUID> tenantIds = repo.findDistinctTenantIds();
        int updated = 0;
        int skipped = 0;
        for (UUID tenantId : tenantIds) {
            List<AuditLog> rows = repo.findAllByTenantOrdered(tenantId);
            byte[] prev = null;
            for (AuditLog row : rows) {
                if (row.getTenantHash() != null) {
                    // 이미 채워짐 — chain 연속성 위해 prev 갱신만 하고 skip
                    prev = row.getTenantHash();
                    skipped++;
                    continue;
                }
                Map<String, Object> payload;
                try {
                    payload = canonical.readValue(row.getPayload(),
                            new com.fasterxml.jackson.core.type.TypeReference<>() {});
                } catch (Exception e) {
                    payload = new HashMap<>();
                }
                AuditAppendRequest req = new AuditAppendRequest(
                        row.getActorId(), row.getActorEmail(), row.getAction(),
                        row.getTargetType(), row.getTargetId(),
                        row.getTenantId(),
                        payload);
                byte[] computed = AuditLogService.computeHash(prev, req, row.getPayload(), row.getCreatedAt());
                row.setTenantPrevHash(prev);
                row.setTenantHash(computed);
                prev = computed;
                updated++;
            }
        }
        return new Summary(tenantIds.size(), updated, skipped);
    }
}
```

- [ ] **Step 5.2: 컴파일**
```bash
./gradlew :admin-app:compileJava 2>&1 | tail -3
```

- [ ] **Step 5.3: codex review**

- [ ] **Step 5.4: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainBackfillService.java
git commit -m "feat(admin-app): AuditChainBackfillService (idempotent) (Phase B.5)"
```

---

## Task 6: REST API (Audit Chain Monitor + Verify + Backfill)

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainMonitorController.java`
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainOverview.java`
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainTenantOverview.java`

- [ ] **Step 6.1: DTO records**

`AuditChainTenantOverview.java`:
```java
package com.crosscert.passkey.admin.audit;

import java.util.List;
import java.util.UUID;

public record AuditChainTenantOverview(
        UUID tenantId,
        String tenantName,
        boolean intact,
        long verifiedRows,
        List<Long> buckets,
        UUID tamperedEntryId
) {}
```

`AuditChainOverview.java`:
```java
package com.crosscert.passkey.admin.audit;

import java.time.Instant;
import java.util.List;

public record AuditChainOverview(
        Instant verifiedAt,
        int windowHours,
        int bucketSizeMinutes,
        Totals totals,
        List<AuditChainTenantOverview> tenants
) {
    public record Totals(
            int tenantsIntact,
            int tenantsTotal,
            int tenantsTampered,
            long verifiedRows,
            long verificationMs
    ) {}
}
```

- [ ] **Step 6.2: Controller**

```java
package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.admin.auth.AdminUserPrincipal;
import com.crosscert.passkey.admin.auth.TenantBoundary;
import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/api/audit/chain")
public class AuditChainMonitorController {

    private final AuditChainVerifier verifier;
    private final AuditChainBackfillService backfillService;
    private final AuditLogRepository auditRepo;
    private final TenantRepository tenantRepo;
    private final TenantBoundary boundary;
    private final Clock clock;

    public AuditChainMonitorController(AuditChainVerifier verifier,
                                        AuditChainBackfillService backfillService,
                                        AuditLogRepository auditRepo,
                                        TenantRepository tenantRepo,
                                        TenantBoundary boundary,
                                        Clock clock) {
        this.verifier = verifier;
        this.backfillService = backfillService;
        this.auditRepo = auditRepo;
        this.tenantRepo = tenantRepo;
        this.boundary = boundary;
        this.clock = clock;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    public AuditChainOverview overview(@RequestParam(defaultValue = "24") int windowHours) {
        long startMs = System.currentTimeMillis();
        Instant now = clock.instant();
        Instant since = now.minus(windowHours, ChronoUnit.HOURS);
        int bucketSizeMinutes = 60;

        List<AuditChainVerifier.TenantResult> results = verifier.verifyAllTenants();
        Map<UUID, Tenant> tenantsById = tenantRepo.findAll().stream()
                .collect(Collectors.toMap(Tenant::getId, t -> t));

        List<AuditChainTenantOverview> tenants = new ArrayList<>(results.size());
        long verifiedRowsTotal = 0;
        int tampered = 0;
        for (var r : results) {
            List<AuditLog> rows = auditRepo.findAllByTenantOrdered(r.tenantId());
            verifiedRowsTotal += rows.size();
            if (!r.ok()) tampered++;

            // bucket by hour
            long[] buckets = new long[windowHours];
            for (AuditLog row : rows) {
                Instant t = row.getCreatedAt();
                if (t.isBefore(since)) continue;
                long minutesSince = ChronoUnit.MINUTES.between(t, now);
                int idx = (int) (minutesSince / bucketSizeMinutes);
                if (idx >= 0 && idx < windowHours) {
                    // 최신이 index 0 가 아니라 가장 오래된 게 0 이 되도록 invert
                    buckets[windowHours - 1 - idx]++;
                }
            }
            List<Long> bucketList = new ArrayList<>(windowHours);
            for (long b : buckets) bucketList.add(b);

            String name = r.tenantId() == null ? "[platform]"
                    : tenantsById.containsKey(r.tenantId())
                        ? tenantsById.get(r.tenantId()).getName()
                        : r.tenantId().toString();
            tenants.add(new AuditChainTenantOverview(
                    r.tenantId(), name, r.ok(), rows.size(), bucketList, r.brokenAt()));
        }

        long verifyMs = System.currentTimeMillis() - startMs;
        AuditChainOverview.Totals totals = new AuditChainOverview.Totals(
                results.size() - tampered, results.size(), tampered, verifiedRowsTotal, verifyMs);
        return new AuditChainOverview(now, windowHours, bucketSizeMinutes, totals, tenants);
    }

    public record TenantVerifyResponse(UUID tenantId, boolean intact, UUID tamperedEntryId, Instant verifiedAt) {}

    @GetMapping("/verify")
    public TenantVerifyResponse verifyTenant(
            @RequestParam(required = false) String tenantId,
            @AuthenticationPrincipal AdminUserPrincipal me) {
        UUID resolved;
        if ("me".equalsIgnoreCase(tenantId) || tenantId == null) {
            resolved = me.tenantId();   // null 일 수도 (PLATFORM_OPERATOR)
            // PLATFORM_OPERATOR 가 tenantId 없이 호출하면 platform chain (NULL) 검증
        } else if ("null".equalsIgnoreCase(tenantId)) {
            // 명시적 platform chain 검증 (PLATFORM_OPERATOR 만)
            if (!me.role().equals("PLATFORM_OPERATOR")) {
                throw new org.springframework.security.access.AccessDeniedException("RP_ADMIN cannot verify platform chain");
            }
            resolved = null;
        } else {
            UUID requested = UUID.fromString(tenantId);
            boundary.assertCanAccess(me, requested);
            resolved = requested;
        }

        var r = verifier.verifyTenant(resolved);
        return new TenantVerifyResponse(r.tenantId(), r.ok(), r.brokenAt(), clock.instant());
    }

    public record BackfillResponse(int tenantsProcessed, int rowsUpdated, int rowsSkipped) {}

    @PostMapping("/backfill")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    public BackfillResponse backfill() {
        var s = backfillService.backfill();
        return new BackfillResponse(s.tenantsProcessed(), s.rowsUpdated(), s.rowsSkipped());
    }
}
```

**주의 — `AdminUserPrincipal`, `TenantBoundary`, `Tenant` 등의 정확한 패키지/시그니처는 grep 으로 확인 후 import:**

```bash
grep -rn "class AdminUserPrincipal\|interface AdminUserPrincipal\|record AdminUserPrincipal" admin-app/src/main/java/ | head
grep -rn "class TenantBoundary\|interface TenantBoundary" admin-app/src/main/java/ | head
grep -rn "class Tenant " core/src/main/java/ | head
grep -rn "interface TenantRepository\|class TenantRepository" core/src/main/java/ | head
grep -rn "@PreAuthorize\|hasRole" admin-app/src/main/java/com/crosscert/passkey/admin/ | head -10
```

위 패턴이 코드와 다르면 그에 맞춰 controller 조정.

- [ ] **Step 6.3: 컴파일**
```bash
./gradlew :admin-app:compileJava 2>&1 | tail -10
```

- [ ] **Step 6.4: codex review**

- [ ] **Step 6.5: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainMonitorController.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainOverview.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainTenantOverview.java
git commit -m "feat(admin-app): Audit Chain Monitor API (overview/verify/backfill) (Phase B.6)"
```

---

## Task 7: IT — per-tenant verify isolation + backfill idempotency

**Files:**
- Create: `admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditChainPerTenantIT.java`
- Create: `admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditChainBackfillIT.java`

- [ ] **Step 7.1: 기존 IT 패턴 파악**

```bash
ls admin-app/src/test/java/com/crosscert/passkey/admin/audit/
find admin-app/src/test -name "*IT.java" | head -5
```

가장 유사한 IT 한 개 Read 해서 (TestContainers Oracle? abstract base class? SpringBootTest 패턴?) 따라간다.

- [ ] **Step 7.2: AuditChainPerTenantIT 작성**

기본 시나리오:
1. tenantA, tenantB 두 tenant 의 audit row 각 3개씩 INSERT (AuditLogService.append)
2. `verifyTenant(tenantA)` → ok=true
3. `verifyTenant(tenantB)` → ok=true
4. `verify()` (글로벌) → ok=true
5. tenantA 의 두번째 row 의 payload 컬럼을 직접 UPDATE (native SQL) 로 조작
6. `verifyTenant(tenantA)` → ok=false, brokenAt=조작된 row id
7. `verifyTenant(tenantB)` → ok=true (격리 확인)
8. `verify()` (글로벌) → ok=false (글로벌 chain 도 깨졌으므로)

기존 IT 패턴 활용 + AuditLogService 직접 호출 + AuditChainVerifier 직접 호출. JdbcTemplate 으로 native UPDATE.

- [ ] **Step 7.3: AuditChainBackfillIT 작성**

시나리오:
1. AuditLogService.append 로 row 2개 생성 (tenant chain 채워진 상태)
2. native UPDATE 로 그 2개의 tenant_hash, tenant_prev_hash 를 NULL 로 만들기 (기존 row 시뮬레이션)
3. 새로 row 1개 더 append (이건 tenant chain 채워짐)
4. `backfillService.backfill()` 호출 → summary 확인 (rowsUpdated=2, rowsSkipped>=1)
5. `verifyAllTenants()` → 모두 intact

- [ ] **Step 7.4: 테스트 실행**

```bash
./gradlew :admin-app:test --tests "*AuditChainPerTenantIT" --tests "*AuditChainBackfillIT" 2>&1 | tail -30
```

PASS 확인. FAIL 시 디버깅.

- [ ] **Step 7.5: codex review**

- [ ] **Step 7.6: Commit**

```bash
git add admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditChainPerTenantIT.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/audit/AuditChainBackfillIT.java
git commit -m "test(admin-app): per-tenant chain verify + backfill IT (Phase B.7)"
```

---

## Task 8: UI — Audit Chain Monitor 페이지 + API 클라이언트

**Files:**
- Create: `admin-ui/src/api/auditChain.ts`
- Create: `admin-ui/src/pages/AuditChainMonitor.tsx`
- Modify: `admin-ui/src/api/types.ts`
- Modify: `admin-ui/src/App.tsx`
- Modify: `admin-ui/src/shell/Sidebar.tsx`

- [ ] **Step 8.1: types.ts 에 응답 타입 추가**

```ts
export type AuditChainOverview = {
  verifiedAt: string;
  windowHours: number;
  bucketSizeMinutes: number;
  totals: {
    tenantsIntact: number;
    tenantsTotal: number;
    tenantsTampered: number;
    verifiedRows: number;
    verificationMs: number;
  };
  tenants: {
    tenantId: string | null;
    tenantName: string;
    intact: boolean;
    verifiedRows: number;
    buckets: number[];
    tamperedEntryId: string | null;
  }[];
};

export type ChainVerifyResponse = {
  tenantId: string | null;
  intact: boolean;
  tamperedEntryId: string | null;
  verifiedAt: string;
};

export type BackfillResponse = {
  tenantsProcessed: number;
  rowsUpdated: number;
  rowsSkipped: number;
};
```

- [ ] **Step 8.2: api/auditChain.ts**

```ts
import { api } from './client';
import type { AuditChainOverview, ChainVerifyResponse, BackfillResponse } from './types';

export const auditChainApi = {
  overview: (windowHours = 24) =>
    api.get<AuditChainOverview>(`/admin/api/audit/chain/overview?windowHours=${windowHours}`),
  verify: (tenantId?: string) =>
    api.get<ChainVerifyResponse>(
      `/admin/api/audit/chain/verify${tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''}`
    ),
  backfill: () => api.post<BackfillResponse>('/admin/api/audit/chain/backfill', {}),
};
```

(`api` 헬퍼의 정확한 시그니처는 `admin-ui/src/api/client.ts` 확인 후 맞춤 — `api.get/post` 가 `<T>` 제네릭을 받는지)

- [ ] **Step 8.3: AuditChainMonitor.tsx 페이지**

```tsx
import { useEffect, useState } from 'react';
import { auditChainApi } from '@/api/auditChain';
import type { AuditChainOverview } from '@/api/types';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { useToast } from '@/components/Toast';

export default function AuditChainMonitor() {
  const [data, setData] = useState<AuditChainOverview | null>(null);
  const [loading, setLoading] = useState(false);
  const toast = useToast();

  async function load() {
    setLoading(true);
    try {
      const d = await auditChainApi.overview(24);
      setData(d);
    } catch (e) {
      // unhandledrejection bridge 에서 toast 처리
    } finally {
      setLoading(false);
    }
  }

  async function runBackfill() {
    try {
      const r = await auditChainApi.backfill();
      toast({
        kind: 'ok',
        title: '백필 완료',
        message: `${r.tenantsProcessed} tenants, ${r.rowsUpdated} updated, ${r.rowsSkipped} skipped`,
      });
      await load();
    } catch {
      /* toast bridge 처리 */
    }
  }

  useEffect(() => {
    load();
  }, []);

  if (!data) {
    return (
      <div className="p-8">
        <div className="text-text-mute text-sm">{loading ? 'Loading…' : 'No data'}</div>
      </div>
    );
  }

  const t = data.totals;
  const tamperedTenants = data.tenants.filter((x) => !x.intact);

  return (
    <div className="p-8 max-w-[1440px] mx-auto">
      <div className="flex items-end justify-between mb-5">
        <div>
          <h1 className="text-[22px] font-semibold tracking-[-0.011em]">Audit Chain Monitor</h1>
          <p className="text-[13px] text-text-mute mt-1">
            Last verified {new Date(data.verifiedAt).toLocaleString()} · took {t.verificationMs}ms
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="default" onClick={load} disabled={loading}>
            {loading ? 'Verifying…' : 'Re-verify'}
          </Button>
          <Button variant="outline" onClick={runBackfill}>
            Run backfill
          </Button>
        </div>
      </div>

      {tamperedTenants.length > 0 && (
        <div className="banner banner--danger mb-5">
          <div className="banner__icon">⚠</div>
          <div>
            <div className="banner__title">
              {tamperedTenants.length}개 tenant 에서 위변조 의심
            </div>
            <div className="banner__body">
              {tamperedTenants.map((x) => x.tenantName).join(', ')}
            </div>
          </div>
        </div>
      )}

      <div className="grid-4 mb-6">
        <div className="card"><div className="card__body">
          <div className="metric-label">Intact / total</div>
          <div className="metric-value">{t.tenantsIntact} / {t.tenantsTotal}</div>
        </div></div>
        <div className="card"><div className="card__body">
          <div className="metric-label">Tampered</div>
          <div className="metric-value">{t.tenantsTampered}</div>
        </div></div>
        <div className="card"><div className="card__body">
          <div className="metric-label">Verified rows</div>
          <div className="metric-value">{t.verifiedRows.toLocaleString()}</div>
        </div></div>
        <div className="card"><div className="card__body">
          <div className="metric-label">Verify time</div>
          <div className="metric-value">{t.verificationMs}ms</div>
        </div></div>
      </div>

      <div className="grid-3">
        {data.tenants.map((x) => (
          <div key={x.tenantId ?? 'platform'} className="card">
            <div className="card__head">
              <div>
                <div className="card__title">{x.tenantName}</div>
                <div className="card__sub">{x.verifiedRows} rows</div>
              </div>
              <Badge variant={x.intact ? 'success' : 'danger'} dot>
                {x.intact ? 'intact' : 'tampered'}
              </Badge>
            </div>
            <div className="card__body">
              <Sparkline buckets={x.buckets} />
              {!x.intact && x.tamperedEntryId && (
                <div className="text-[11px] text-danger mt-2 mono">
                  broken at id {x.tamperedEntryId}
                </div>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function Sparkline({ buckets }: { buckets: number[] }) {
  const max = Math.max(...buckets, 1);
  return (
    <svg viewBox={`0 0 ${buckets.length * 4} 24`} className="w-full h-6">
      {buckets.map((b, i) => {
        const h = Math.max(1, (b / max) * 24);
        return (
          <rect
            key={i}
            x={i * 4}
            y={24 - h}
            width={3}
            height={h}
            className="fill-accent"
          />
        );
      })}
    </svg>
  );
}
```

(`useToast` 호출 시그니처는 Phase A 의 shim API 와 일치)

- [ ] **Step 8.4: App.tsx 에 라우트 추가**

```tsx
import AuditChainMonitor from './pages/AuditChainMonitor';
// ...
<Route path="/audit-chain" element={<AuditChainMonitor />} />
```

기존 Route 들 사이에 추가.

- [ ] **Step 8.5: Sidebar.tsx 의 PLATFORM_NAV 에 항목 추가**

기존 PLATFORM_NAV 에:
```tsx
import { ShieldCheck, LinkIcon } from 'lucide-react';
// ...
const PLATFORM_NAV: NavItem[] = [
  { to: '/tenants', label: 'Tenants', icon: Building2 },
  { to: '/activity', label: 'Activity', icon: ActivityIcon },
  { to: '/audit', label: 'Audit', icon: ShieldCheck },
  { to: '/audit-chain', label: 'Chain', icon: LinkIcon },   // 신규
  { to: '/keys', label: 'Signing Keys', icon: KeyRound },
  { to: '/mds', label: 'MDS', icon: Database },
];
```

(`LinkIcon` 또는 다른 적절한 lucide 아이콘)

- [ ] **Step 8.6: 빌드 + 컴파일**
```bash
cd admin-ui && npm run build 2>&1 | tail -5
```
0 에러.

- [ ] **Step 8.7: codex review**

- [ ] **Step 8.8: Commit**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/audit-chain-per-tenant
git add admin-ui/
git commit -m "feat(admin-ui): Audit Chain Monitor 페이지 + 사이드바 nav (Phase B.8)"
```

---

## Self-Review

- [x] V25 마이그레이션 (V24 사용 중이라 +1 이동)
- [x] AuditLog entity 2 필드 추가 (Task 2+3 합쳐서 commit)
- [x] AuditLogService append 시 tenant chain 계산
- [x] AuditChainVerifier per-tenant + 전체 tenants
- [x] BackfillService idempotent
- [x] REST API 3개 (overview/verify/backfill)
- [x] IT 2개 (isolation + backfill)
- [x] UI 페이지 + nav + API 클라이언트

placeholder 없음. Oracle native 패턴 (FOR UPDATE / EXCEPTION) 활용. 시그니처 일관성 (TenantResult/AuditChainTenantOverview 등).

---

## 실행 가이드 요약

1. 각 Task step 순서대로
2. 각 Task 마지막 codex review 실행 → fix → commit
3. 빌드 실패 시 끝내기 전 fix
4. Task 7 IT 가 PASS 해야 다음 task 진행 가능
