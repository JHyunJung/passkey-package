# VPD 멀티테넌트 격리 완전 제거 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Oracle VPD(DBMS_RLS) 기반 테넌트 격리를 코드·설정·마이그레이션·스크립트·테스트 전반에서 제거하고, 앱 레벨 Hibernate `@Filter`(`TenantFilterAspect`)를 유일한 테넌트 격리 계층으로 남긴다.

**Architecture:** `passkey.vpd.enabled`는 이미 모든 운영 프로필에서 `false`이고 VPD on 경로는 죽은 코드다. VPD on 전용 인프라(`TenantAwareDataSource`, `tenant_predicate` 함수, 7개 DBMS_RLS 정책, `CTX_PKG`/`APP_CTX`, definer 우회 패키지 `api_key_lookup_pkg`)를 물리적으로 제거한다. `TenantContextHolder`·`TenantFilterAspect`·엔티티 `@Filter`·DB 유저 3분할은 유지된다(격리 무손실).

**Tech Stack:** Java 21, Spring Boot 3, Hibernate 6.6, Oracle (EE/XE/SE2), Flyway, Testcontainers(Oracle), Gradle, JUnit 5.

## Global Constraints

- 격리 무손실: 작업 후에도 cross-tenant 누출이 없어야 한다. 검증 게이트는 `AppLevelIsolationIT`.
- DB 유저 3분할(APP_OWNER/APP_RUNTIME/APP_ADMIN) **유지**. EXEMPT ACCESS POLICY GRANT만 제거(무의미).
- 과거 마이그레이션 파일은 **삭제 금지** — 내용을 no-op으로 비우고 버전/파일명 보존(신규 환경 Flyway 버전 점프·후속 의존 보호).
- 신규 정리 마이그레이션은 `V52__drop_vpd.sql`. 모든 DROP에 멱등 가드(ORA-28101 정책없음 / ORA-04043 객체없음 / ORA-00439 SE2 / ORA-01031 권한없음 삼킴). 가드는 fail-closed(`SQLCODE = -코드` 양성 매칭).
- `api_key` PK·tenant_id는 RAW(16) (UUID 바이너리). `key_prefix`는 전역 UNIQUE(V7).
- 회귀 판정은 `./gradlew build` 결과로 하지 않는다(SliceConfig 충돌+Oracle 컨테이너 경합으로 항상 빨감, pre-existing). base 브랜치(`main`, HEAD=1704748) 대조로 신규 회귀만 확정.
- 패키지 리네임: `com.crosscert.passkey.core.vpd` → `com.crosscert.passkey.core.tenant`.
- 작업 디렉토리: `.claude/worktrees/vpd-removal`, 브랜치 `worktree-vpd-removal`. 모든 경로는 이 worktree 기준 상대경로로 다룬다.
- Oracle 동작(정책 제거 멱등성, DROP 권한)은 inspection으로 안 잡힘 → Testcontainers 실행으로만 검증.

---

### Task 1: TenantAwareDataSource 삭제 + CoreDataSourceConfig 단순화

VPD on 전용 DB 커널 브리지를 제거하고, 데이터소스 빈에서 `vpdEnabled` 분기를 들어낸다. off 경로(physical pool 직접 반환)가 유일 경로가 된다.

**Files:**
- Delete: `core/src/main/java/com/crosscert/passkey/core/vpd/TenantAwareDataSource.java`
- Delete: `core/src/test/java/com/crosscert/passkey/core/vpd/TenantAwareDataSourceTest.java`
- Modify: `core/src/main/java/com/crosscert/passkey/core/config/CoreDataSourceConfig.java`

**Interfaces:**
- Consumes: 없음 (시작점)
- Produces: `dataSource` 빈은 이제 항상 `physicalDataSource`(HikariDataSource)를 그대로 반환. `passkey.vpd.enabled` 프로퍼티는 더 이상 읽지 않음.

- [ ] **Step 1: 현재 빌드 가능 상태 확인 (baseline)**

Run: `./gradlew :core:compileJava -q`
Expected: BUILD SUCCESSFUL (변경 전 컴파일 통과 확인). 실패하면 멈추고 보고.

- [ ] **Step 2: TenantAwareDataSource 사용처 전수 확인**

Run: `grep -rn "TenantAwareDataSource" core passkey-app admin-app rp-app --include=*.java | grep -v "/test/"`
Expected: `CoreDataSourceConfig.java`의 import·생성자 호출 2곳만 나와야 함. 다른 main 사용처가 있으면 그 파일도 이 Task 범위에 추가.

- [ ] **Step 3: CoreDataSourceConfig를 단순화**

`core/src/main/java/com/crosscert/passkey/core/config/CoreDataSourceConfig.java` 전체를 아래로 교체. `TenantAwareDataSource` import·`vpdEnabled` 파라미터·삼항 분기를 제거하고, `physicalDataSource`를 `@Primary`로 직접 노출한다.

```java
package com.crosscert.passkey.core.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires the physical HikariCP pool as the application DataSource.
 *
 * <p>Spring Boot's auto-config provides DataSourceProperties bound to
 * {@code spring.datasource.*} (url/username/password/driver-class-name).
 * We use {@link DataSourceProperties#initializeDataSourceBuilder()} to
 * get a builder pre-loaded with those base settings, then bind
 * {@code spring.datasource.hikari.*} onto the resulting HikariDataSource
 * directly so pool tuning takes effect.
 *
 * <p>Tenant isolation is provided entirely by the app-level Hibernate
 * {@code @Filter} activated by
 * {@link com.crosscert.passkey.core.tenant.TenantFilterAspect}. There is
 * no DB-kernel VPD layer.
 */
@Configuration
public class CoreDataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }
}
```

- [ ] **Step 4: TenantAwareDataSource와 그 테스트 삭제**

```bash
git rm core/src/main/java/com/crosscert/passkey/core/vpd/TenantAwareDataSource.java
git rm core/src/test/java/com/crosscert/passkey/core/vpd/TenantAwareDataSourceTest.java
```

- [ ] **Step 5: core 컴파일 검증**

Run: `./gradlew :core:compileJava -q`
Expected: BUILD SUCCESSFUL. `TenantAwareDataSource` 미해결 참조 에러가 나면 Step 2에서 누락한 사용처 → 처리.

- [ ] **Step 6: 전체 앱 컴파일 검증 (다운스트림 영향)**

Run: `./gradlew compileJava -q`
Expected: BUILD SUCCESSFUL. (테스트 컴파일은 후속 Task에서 정리하므로 여기선 main만.)

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(core): VPD 데이터소스 래퍼 제거, physical pool 직접 노출

TenantAwareDataSource(ctx_pkg.set_tenant 브리지)와 그 테스트를 삭제하고
CoreDataSourceConfig의 passkey.vpd.enabled 분기를 제거. 데이터소스는 이제
항상 HikariDataSource 그대로. 테넌트 격리는 앱 레벨 @Filter가 전담.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: vpd 패키지 → tenant 리네임

VPD가 사라진 뒤 `core/.../vpd/`에는 VPD 무관 공유 컴포넌트(`TenantContextHolder`, `TenantFilterAspect`, `DevTenantHeaderFilter`)만 남는다. 패키지를 `tenant`로 리네임해 이름과 의미를 일치시킨다.

**Files:**
- Move: `core/src/main/java/com/crosscert/passkey/core/vpd/TenantContextHolder.java` → `.../core/tenant/TenantContextHolder.java`
- Move: `core/src/main/java/com/crosscert/passkey/core/vpd/TenantFilterAspect.java` → `.../core/tenant/TenantFilterAspect.java`
- Move: `core/src/main/java/com/crosscert/passkey/core/vpd/DevTenantHeaderFilter.java` → `.../core/tenant/DevTenantHeaderFilter.java`
- Move (test): `core/src/test/java/com/crosscert/passkey/core/vpd/{TenantContextHolderTest,TenantFilterAspectIT,TenantFilterBindingIT,DevTenantHeaderFilterTest,AppLevelIsolationIT,RuntimeDsHelper}.java` → `.../core/tenant/` (존재하는 것만)
- Modify: 모든 `import com.crosscert.passkey.core.vpd.*` 참조 파일 (~18개)

**Interfaces:**
- Consumes: Task 1 (CoreDataSourceConfig가 이미 `core.tenant.TenantFilterAspect`를 javadoc에서 참조하도록 작성됨)
- Produces: `com.crosscert.passkey.core.tenant.TenantContextHolder`(set/get/clear), `com.crosscert.passkey.core.tenant.TenantFilterAspect`. 클래스명·메서드 시그니처는 불변, 패키지 경로만 변경.

- [ ] **Step 1: vpd 패키지에 남은 파일 확인 (VpdToggleIT/VpdIsolationIT는 Task 5에서 삭제하므로 여기서 리네임 대상 제외)**

Run: `ls core/src/main/java/com/crosscert/passkey/core/vpd/ core/src/test/java/com/crosscert/passkey/core/vpd/`
Expected (main): `TenantContextHolder.java TenantFilterAspect.java DevTenantHeaderFilter.java` (TenantAwareDataSource는 Task 1에서 삭제됨).
Expected (test): `AppLevelIsolationIT.java DevTenantHeaderFilterTest.java RuntimeDsHelper.java TenantContextHolderTest.java TenantFilterAspectIT.java TenantFilterBindingIT.java VpdIsolationIT.java VpdToggleIT.java`.

- [ ] **Step 2: main 3개 파일을 tenant 패키지로 이동 + package 선언 수정**

```bash
mkdir -p core/src/main/java/com/crosscert/passkey/core/tenant
git mv core/src/main/java/com/crosscert/passkey/core/vpd/TenantContextHolder.java core/src/main/java/com/crosscert/passkey/core/tenant/TenantContextHolder.java
git mv core/src/main/java/com/crosscert/passkey/core/vpd/TenantFilterAspect.java core/src/main/java/com/crosscert/passkey/core/tenant/TenantFilterAspect.java
git mv core/src/main/java/com/crosscert/passkey/core/vpd/DevTenantHeaderFilter.java core/src/main/java/com/crosscert/passkey/core/tenant/DevTenantHeaderFilter.java
```

이동한 3개 파일의 첫 줄 `package com.crosscert.passkey.core.vpd;` → `package com.crosscert.passkey.core.tenant;` 로 수정.

- [ ] **Step 3: 유지할 test 파일 5개를 tenant 패키지로 이동 + package 선언 수정**

VpdIsolationIT/VpdToggleIT는 Task 5에서 삭제할 것이므로 **이동하지 않는다**(vpd 패키지에 남겨둠). 나머지를 이동:

```bash
mkdir -p core/src/test/java/com/crosscert/passkey/core/tenant
git mv core/src/test/java/com/crosscert/passkey/core/vpd/TenantContextHolderTest.java core/src/test/java/com/crosscert/passkey/core/tenant/TenantContextHolderTest.java
git mv core/src/test/java/com/crosscert/passkey/core/vpd/TenantFilterAspectIT.java core/src/test/java/com/crosscert/passkey/core/tenant/TenantFilterAspectIT.java
git mv core/src/test/java/com/crosscert/passkey/core/vpd/TenantFilterBindingIT.java core/src/test/java/com/crosscert/passkey/core/tenant/TenantFilterBindingIT.java
git mv core/src/test/java/com/crosscert/passkey/core/vpd/DevTenantHeaderFilterTest.java core/src/test/java/com/crosscert/passkey/core/tenant/DevTenantHeaderFilterTest.java
git mv core/src/test/java/com/crosscert/passkey/core/vpd/AppLevelIsolationIT.java core/src/test/java/com/crosscert/passkey/core/tenant/AppLevelIsolationIT.java
git mv core/src/test/java/com/crosscert/passkey/core/vpd/RuntimeDsHelper.java core/src/test/java/com/crosscert/passkey/core/tenant/RuntimeDsHelper.java
```

이동한 6개 test 파일의 `package ...core.vpd;` → `package ...core.tenant;` 로 수정.

- [ ] **Step 4: 전 모듈에서 import 경로 일괄 치환**

Run: `grep -rln "com\.crosscert\.passkey\.core\.vpd\." core passkey-app admin-app rp-app --include=*.java`
이 목록의 각 파일에서 `com.crosscert.passkey.core.vpd.TenantContextHolder` → `com.crosscert.passkey.core.tenant.TenantContextHolder`, 동일하게 `.vpd.TenantFilterAspect`/`.vpd.DevTenantHeaderFilter` → `.tenant.*` 로 치환. (VpdIsolationIT/VpdToggleIT가 `core.vpd.TenantContextHolder`를 import하면 그 둘은 Task 5 삭제 대상이므로 그대로 둬도 무방하나, 치환해도 무해.)

- [ ] **Step 5: vpd 패키지 잔재 확인 (삭제 예정 2개 IT만 남아야 함)**

Run: `grep -rln "com\.crosscert\.passkey\.core\.vpd\." core passkey-app admin-app rp-app --include=*.java`
Expected: `VpdIsolationIT.java`, `VpdToggleIT.java`(자기 자신 import) 외엔 0건. 다른 파일이 남으면 Step 4 누락 → 처리.

- [ ] **Step 6: main 컴파일 검증**

Run: `./gradlew compileJava -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: 유지 test 컴파일 검증 (이동한 5개)**

Run: `./gradlew :core:compileTestJava -q`
Expected: BUILD SUCCESSFUL. (VpdIsolationIT/VpdToggleIT는 아직 존재·`core.vpd` import 상태로 컴파일됨 — Task 5에서 삭제.)

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor(core): vpd 패키지를 tenant 로 리네임

TenantContextHolder/TenantFilterAspect/DevTenantHeaderFilter 및 유지 테스트를
core.vpd → core.tenant 로 이동하고 import ~18곳 갱신. VPD 제거 후 패키지명이
의미와 일치하도록. VpdIsolationIT/VpdToggleIT 는 Task 5 에서 삭제 예정이라 미이동.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: ApiKeyLookupService를 native 쿼리로 교체

definer 우회 PL/SQL 패키지 `api_key_lookup_pkg` 호출을 직접 SQL로 대체한다. VPD가 없으니 우회가 불필요하고, `findByPrefix`는 `@Filter`가 안 걸리는 JdbcTemplate native 쿼리로 전역 unique prefix를 룩업한다.

**Files:**
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyLookupService.java`
- Test: `passkey-app/src/test/java/com/crosscert/passkey/app/security/ApiKeyLookupServiceIT.java` (없으면 Create; 있으면 재작성)

**Interfaces:**
- Consumes: Task 2 (`com.crosscert.passkey.core.tenant.TenantContextHolder` — 단 이 서비스는 직접 import 안 할 수 있음). `api_key` 테이블: `id RAW(16)`, `tenant_id RAW(16)`, `key_hash VARCHAR2(255)`, `key_prefix VARCHAR2`(전역 UNIQUE), `expires_at`/`revoked_at` TIMESTAMP WITH TIME ZONE, `last_used_at`.
- Produces: 시그니처 **불변** — `Optional<ApiKeyAuthRow> findByPrefix(String keyPrefix)`, `void touchLastUsed(UUID apiKeyId, UUID tenantId, Instant now)`, `record ApiKeyAuthRow(UUID id, UUID tenantId, String keyHash, Instant expiresAt, Instant revokedAt)`. `ApiKeyAuthFilter`는 무변경.

- [ ] **Step 1: 실패 테스트 작성 — native 룩업이 tenant context 없이 동작**

`passkey-app/src/test/java/com/crosscert/passkey/app/security/ApiKeyLookupServiceIT.java` 작성(또는 기존 패키지 호출 검증 테스트를 이 내용으로 교체). Testcontainers Oracle + Flyway 적용 후, tenant context 미설정 상태에서 `findByPrefix`가 행을 반환하고, `touchLastUsed`가 명시 tenantId로만 갱신함을 검증.

```java
package com.crosscert.passkey.app.security;

import com.crosscert.passkey.app.security.ApiKeyLookupService.ApiKeyAuthRow;
// (프로젝트의 기존 Oracle Testcontainers 베이스 클래스/애너테이션을 사용.
//  예: @SpringBootTest + @Testcontainers, 또는 core 의 AbstractOracleIT 패턴.
//  기존 IT 한 개를 열어 동일한 부트스트랩 애너테이션을 복사할 것.)
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyLookupServiceIT /* extends <기존 Oracle IT 베이스> */ {

    // 주입: ApiKeyLookupService service; JdbcTemplate/DataSource for seeding.

    @Test
    void findByPrefix_returnsRow_withoutTenantContext() {
        // given: api_key 행 1건을 직접 INSERT (id/tenant_id RAW(16), key_prefix 전역 unique)
        UUID id = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        String prefix = "pk_testpfx";
        seedApiKey(id, tenant, prefix, "$2a$dummyhash", /*expiresAt*/ null, /*revokedAt*/ null);

        // when: tenant context 미설정 상태에서 룩업 (인증 시점 재현)
        Optional<ApiKeyAuthRow> row = service.findByPrefix(prefix);

        // then: @Filter 우회 native 쿼리라 1행 반환
        assertThat(row).isPresent();
        assertThat(row.get().id()).isEqualTo(id);
        assertThat(row.get().tenantId()).isEqualTo(tenant);
    }

    @Test
    void findByPrefix_returnsEmpty_forUnknownPrefix() {
        assertThat(service.findByPrefix("pk_nope0000")).isEmpty();
    }

    @Test
    void touchLastUsed_updatesOnlyMatchingTenant() {
        UUID id = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        seedApiKey(id, tenant, "pk_touchpfx", "$2a$dummyhash", null, null);

        // 잘못된 tenantId → 0행 갱신(차단)
        service.touchLastUsed(id, UUID.randomUUID(), Instant.now());
        assertThat(lastUsedOf(id)).isNull();

        // 올바른 tenantId → 갱신
        Instant now = Instant.now();
        service.touchLastUsed(id, tenant, now);
        assertThat(lastUsedOf(id)).isNotNull();
    }

    // helper: seedApiKey(...) 는 INSERT INTO api_key(id, tenant_id, key_prefix, key_hash,
    //   created_at, expires_at, revoked_at) VALUES (?,?,?,?,...) 로 RAW(16) 바인딩.
    //   lastUsedOf(id) 는 SELECT last_used_at FROM api_key WHERE id=?.
    //   기존 IT 의 seed 헬퍼/JdbcTemplate 패턴을 재사용할 것.
}
```

- [ ] **Step 2: 테스트 실패 확인 (현재 서비스는 PL/SQL 패키지 호출)**

Run: `./gradlew :passkey-app:test --tests "*ApiKeyLookupServiceIT" -q`
Expected: 컨테이너 부팅 후 — 현 구현이 여전히 패키지를 호출하므로, 패키지가 Task 4에서 아직 안 지워진 상태라면 통과할 수도 있음. **이 단계의 목적은 테스트가 컴파일·실행되는지 확인**. 만약 패키지 의존으로 그린이면, Step 3에서 native로 바꾼 뒤에도 그린이어야 진짜 검증.

> 주의: 이 Task는 동작 보존 리팩터다. "실패→통과"의 빨강은 native 전환 전 구현과 무관하게, **tenant-context-없는 findByPrefix가 행을 반환**하는 속성을 native가 보장하는지에 있다. 패키지 제거(Task 4) 전이라 둘 다 통과할 수 있으니, Step 4 재실행으로 native 구현의 정확성을 확정한다.

- [ ] **Step 3: ApiKeyLookupService를 native 쿼리로 재작성**

`passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyLookupService.java`의 두 메서드 본문을 PL/SQL CallableStatement → 직접 SQL로 교체. `ApiKeyAuthRow` record와 RAW(16)↔UUID 헬퍼(`toUuidOrFail`/`toBytes`)는 그대로 유지. javadoc의 "definer-rights ... bypasses VPD" 문구를 "app-level @Filter 비적용 native 쿼리" 설명으로 갱신.

`findByPrefix` 본문:

```java
public Optional<ApiKeyAuthRow> findByPrefix(String keyPrefix) {
    // 인증 시점에는 아직 tenant context 가 없다. key_prefix 는 전역 UNIQUE(V7)
    // 이므로 tenant 없이 1행 룩업이 정확하다. JdbcTemplate 의 직접 SQL 은
    // Hibernate @Filter(ORM 레벨) 를 거치지 않으므로 모든 테넌트의 행을 본다.
    return jdbc.query(
            "SELECT id, tenant_id, key_hash, expires_at, revoked_at "
          + "FROM api_key WHERE key_prefix = ?",
            ps -> ps.setString(1, keyPrefix),
            rs -> {
                if (!rs.next()) return Optional.empty();
                UUID id = toUuidOrFail(rs.getBytes("id"), "id");
                UUID tenantId = toUuidOrFail(rs.getBytes("tenant_id"), "tenant_id");
                String keyHash = rs.getString("key_hash");
                java.sql.Timestamp expTs = rs.getTimestamp("expires_at");
                java.sql.Timestamp revTs = rs.getTimestamp("revoked_at");
                Instant expiresAt = expTs == null ? null : expTs.toInstant();
                Instant revokedAt = revTs == null ? null : revTs.toInstant();
                return Optional.of(new ApiKeyAuthRow(id, tenantId, keyHash, expiresAt, revokedAt));
            });
}
```

`touchLastUsed` 본문:

```java
public void touchLastUsed(UUID apiKeyId, UUID tenantId, Instant now) {
    // Best-effort: touch 실패가 유효 인증을 500 으로 만들면 안 된다.
    // tenant_id 를 WHERE 에 명시 — context 부재를 인가로 취급하지 않는다(타 테넌트
    // 행 변조 차단). VPD 제거 후에도 격리 의미를 명시 검증으로 유지.
    try {
        jdbc.update(
                "UPDATE api_key SET last_used_at = ? WHERE id = ? AND tenant_id = ?",
                ps -> {
                    ps.setTimestamp(1, java.sql.Timestamp.from(now));
                    ps.setBytes(2, toBytes(apiKeyId));
                    ps.setBytes(3, toBytes(tenantId));
                });
    } catch (org.springframework.dao.DataAccessException e) {
        log.warn("api-key touch_last_used failed (best-effort): {}", e.toString());
    }
}
```

`jdbc.query(String, PreparedStatementSetter, ResultSetExtractor)`와 `jdbc.update(String, PreparedStatementSetter)` 오버로드를 쓰므로 기존 `JdbcTemplate jdbc` 필드·생성자는 유지. `ConnectionCallback`/`CallableStatement` import는 제거.

- [ ] **Step 4: 테스트 실행 — native 구현 검증**

Run: `./gradlew :passkey-app:test --tests "*ApiKeyLookupServiceIT" -q`
Expected: PASS. 3개 테스트(context 없이 룩업 / unknown empty / tenant 불일치 차단) 모두 그린.

- [ ] **Step 5: ApiKeyRepository javadoc 갱신 (패키지 참조 제거)**

`core/src/main/java/com/crosscert/passkey/core/repository/ApiKeyRepository.java`의 `findByKeyPrefix` javadoc에서 "VPD filters by tenant_id = SYS_CONTEXT" 및 "definer-rights PL/SQL package (APP_OWNER.api_key_lookup_pkg.find_by_prefix)" 언급을 "app-level @Filter 가 tenant 로 격리하므로 tenant context 설정 후에만 행을 반환. 인증 필터는 native 쿼리(ApiKeyLookupService)로 context 전에 룩업"으로 수정.

- [ ] **Step 6: passkey-app 컴파일 + 관련 테스트 확인**

Run: `./gradlew :passkey-app:compileJava :passkey-app:test --tests "*ApiKeyAuthFilter*" --tests "*ApiKeyLookupService*" -q`
Expected: BUILD SUCCESSFUL, 테스트 그린. `ApiKeyAuthFilter`는 시그니처 불변이라 무변경 통과.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(passkey-app): ApiKey 룩업을 PL/SQL 패키지에서 native 쿼리로

api_key_lookup_pkg(definer 우회) 호출을 JdbcTemplate 직접 SQL 로 교체.
findByPrefix 는 전역 unique prefix 를 tenant context 없이 룩업(native 라
@Filter 우회), touchLastUsed 는 명시 tenant_id WHERE 로 격리 유지.
시그니처 불변 → ApiKeyAuthFilter 무변경.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: 설정(yml)에서 passkey.vpd.enabled 완전 제거

`vpd.enabled` 키와 `PASSKEY_VPD_ENABLED` 환경변수 참조를 모든 프로필에서 들어낸다.

**Files:**
- Modify: `core/src/main/resources/application-common.yml`
- Modify: `passkey-app/src/main/resources/application-dev.yml`, `application-qa.yml`, `application-prod.yml`
- Modify: `admin-app/src/main/resources/application-dev.yml`, `application-qa.yml`, `application-prod.yml`
- Modify: `passkey-app/src/test/resources/application-test.yml`
- Modify: `admin-app/src/main/resources/application.yml` (bootstrap-vpd 언급 주석), `passkey-app`/`admin-app` 의 `application.yml` 주석 일반

**Interfaces:**
- Consumes: Task 1 (코드가 더 이상 `passkey.vpd.enabled`를 읽지 않음 → yml 키 제거가 안전)
- Produces: 어떤 프로필에도 `vpd` 키 없음. 격리는 항상 앱 레벨.

- [ ] **Step 1: vpd 키 위치 전수 확인**

Run: `grep -rn "vpd\|PASSKEY_VPD_ENABLED" core/src admin-app/src passkey-app/src rp-app/src --include=*.yml`
Expected: spec에 적힌 위치들(application-common.yml, dev/qa/prod yml 6곳, test yml 1곳, 주석 몇 곳). 이 목록을 작업 체크리스트로 사용.

- [ ] **Step 2: application-common.yml 의 vpd 블록 제거**

`core/src/main/resources/application-common.yml`에서 다음 블록(주석 포함)을 삭제:

```yaml
  # VPD(Oracle Virtual Private Database, DBMS_RLS) 토글.
  # true  → Enterprise Edition / XE: DB 커널이 tenant 술어를 강제(이중 방어).
  # false → Standard Edition 2(VPD 미지원): 앱 레벨 Hibernate @Filter 가 격리.
  # 기본 false. VPD 를 지원하는 DB(EE/XE)에 배포할 때만 환경별 yml 에서 true 로 켠다.
  vpd:
    enabled: false
```

(`passkey:` 하위의 다른 키들은 유지.)

- [ ] **Step 3: passkey-app / admin-app 의 dev·qa·prod yml 에서 vpd 블록 제거**

각 파일에서 `vpd:` 블록을 삭제:
- `passkey-app/.../application-dev.yml`, `application-qa.yml`, `application-prod.yml`
- `admin-app/.../application-dev.yml`, `application-qa.yml`, `application-prod.yml`

prod yml의 경우 `vpd:` 위의 VPD 경고 주석("⚠️ 중요: 대상 Oracle 에 VPD 정책이 ...")도 함께 삭제. `PASSKEY_VPD_ENABLED` 환경변수 참조가 사라진다.

- [ ] **Step 4: passkey-app test yml 에서 vpd 제거**

`passkey-app/src/test/resources/application-test.yml`의 `vpd: enabled: true` 블록과 그 위 설명 주석(VPD ON 이유 설명)을 삭제. IT는 이제 앱 레벨 격리로 동작.

- [ ] **Step 5: application.yml 주석 정리**

`admin-app/src/main/resources/application.yml`의 "context created by scripts/bootstrap-vpd.sql before Flyway ever..." 주석을 bootstrap 스크립트 새 이름(Task 6에서 `bootstrap-schema.sql`)에 맞춰 갱신하거나, VPD 전제 문구면 제거. passkey-app application.yml에도 동일 점검.

- [ ] **Step 6: vpd 키 잔재 0 확인**

Run: `grep -rn "vpd\|PASSKEY_VPD_ENABLED" core/src admin-app/src passkey-app/src rp-app/src --include=*.yml`
Expected: 0건 (bootstrap 스크립트 이름 언급 주석은 Task 6에서 처리되므로 여기선 yml의 `vpd` 키/환경변수만 0).

- [ ] **Step 7: 앱 부팅 스모크 (dev 프로필 컨텍스트 로드)**

Run: `./gradlew :passkey-app:compileJava :admin-app:compileJava -q`
Expected: BUILD SUCCESSFUL. (yml은 컴파일 대상 아니나, `@Value("${passkey.vpd.enabled}")` 참조가 Task 1에서 제거됐으므로 미해결 플레이스홀더 부팅 에러 위험 없음. 부팅 검증은 Task 8 dogfooding에서.)

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "chore(config): passkey.vpd.enabled 키를 전 프로필에서 제거

application-common(기본 false), passkey-app/admin-app dev·qa·prod yml,
passkey-app test yml(enabled:true), prod VPD 경고 주석까지 삭제.
PASSKEY_VPD_ENABLED 환경변수 참조 소멸 — 격리는 항상 앱 레벨.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: VPD on 전용 테스트 삭제 + AppLevelIsolationIT 회귀 게이트 승격

DB 커널 정책을 검증하던 테스트를 제거하고, off 격리 증명 테스트를 핵심 회귀 게이트로 다듬는다.

**Files:**
- Delete: `core/src/test/java/com/crosscert/passkey/core/vpd/VpdIsolationIT.java`
- Delete: `core/src/test/java/com/crosscert/passkey/core/vpd/VpdToggleIT.java`
- Modify: `core/src/test/java/com/crosscert/passkey/core/tenant/AppLevelIsolationIT.java` (Task 2에서 이동됨)
- Modify: `core/src/test/java/com/crosscert/passkey/core/tenant/RuntimeDsHelper.java` (VPD 전용 헬퍼면 정리/삭제)

**Interfaces:**
- Consumes: Task 2 (AppLevelIsolationIT는 `core.tenant` 패키지에 있음)
- Produces: VPD on 테스트 0건. `AppLevelIsolationIT`가 "VPD 없이 cross-tenant 누출 없음"을 증명.

- [ ] **Step 1: VpdIsolationIT / VpdToggleIT 내용 확인 (살릴 케이스 식별)**

Run: `grep -n "void \|@Test\|vpd.enabled\|VpdEnabled\|VpdDisabled\|enableFilter\|cross" core/src/test/java/com/crosscert/passkey/core/vpd/VpdToggleIT.java core/src/test/java/com/crosscert/passkey/core/vpd/VpdIsolationIT.java`
Expected: VpdToggleIT의 `VpdDisabled`(off 격리) 케이스가 `AppLevelIsolationIT`와 중복인지 확인. off 격리 검증 케이스가 AppLevelIsolationIT에 없으면 Step 4에서 이관.

- [ ] **Step 2: VpdIsolationIT / VpdToggleIT 삭제**

```bash
git rm core/src/test/java/com/crosscert/passkey/core/vpd/VpdIsolationIT.java
git rm core/src/test/java/com/crosscert/passkey/core/vpd/VpdToggleIT.java
```

- [ ] **Step 3: 빈 vpd 테스트 디렉토리 정리**

Run: `ls core/src/test/java/com/crosscert/passkey/core/vpd/ 2>/dev/null || echo "empty/gone"`
Expected: 비었으면 `rmdir` (git은 빈 디렉토리 추적 안 하므로 자동 사라짐). 남은 파일 있으면 Task 2 누락 → 처리.

- [ ] **Step 4: AppLevelIsolationIT를 회귀 게이트로 보강**

`AppLevelIsolationIT.java`를 열어 다음을 보장(없으면 추가):
1. 클래스 javadoc에 "VPD 제거 후 테넌트 격리의 단일 회귀 게이트. 이 테스트가 깨지면 cross-tenant 누출 가능."을 명시.
2. 핵심 케이스: tenant A 컨텍스트(`TenantContextHolder.set(A)`)에서 `@Transactional` 경로로 credential/api_key 조회 시 tenant B 데이터가 **0행**.
3. tenant context 없을 때(PLATFORM_OPERATOR 케이스) `@Filter` 미적용으로 cross-tenant 조회가 동작함도 1케이스로 명시(의도된 동작).
4. `@TestPropertySource(properties="passkey.vpd.enabled=false")`가 있으면 **제거**(키가 사라졌으므로 불필요/무효). 패키지 import가 `core.tenant`인지 확인.

(이미 충분하면 javadoc 보강 + 불필요 `@TestPropertySource` 제거만.)

- [ ] **Step 5: RuntimeDsHelper 점검**

`RuntimeDsHelper.java`가 APP_RUNTIME_USER DataSource로 VPD 정책 동작을 검증하던 헬퍼면, VPD 제거 후 쓰임을 확인. AppLevelIsolationIT가 여전히 참조하면 유지, VpdIsolationIT/VpdToggleIT 전용이었으면 `git rm`.

Run: `grep -rln "RuntimeDsHelper" core/src/test --include=*.java`
Expected: 참조처가 0이면 삭제, AppLevelIsolationIT 등이 참조하면 유지.

- [ ] **Step 6: core 테스트 컴파일 + 회귀 게이트 실행**

Run: `./gradlew :core:compileTestJava -q && ./gradlew :core:test --tests "*AppLevelIsolationIT" --tests "*TenantFilterAspectIT" --tests "*TenantFilterBindingIT" -q`
Expected: 컴파일 BUILD SUCCESSFUL, 격리 테스트 그린. (Testcontainers Oracle 부팅 포함.)

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "test(core): VPD on 테스트 삭제, AppLevelIsolationIT 회귀 게이트 승격

VpdIsolationIT/VpdToggleIT(DB 커널 정책 검증) 삭제. AppLevelIsolationIT 를
'VPD 없이 cross-tenant 누출 없음' 단일 회귀 게이트로 보강(불필요한
@TestPropertySource vpd.enabled 제거, 격리/cross-tenant 케이스 명시).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: 부트스트랩·배포 스크립트에서 VPD 제거 + 리네임

유저 분리(3-user 생성·GRANT)는 유지하고 VPD 전용 부분(CTX_PKG/APP_CTX/DBMS_RLS GRANT/EXEMPT)만 제거. `bootstrap-vpd.sql`은 이름이 부정확해지므로 `bootstrap-schema.sql`로 리네임.

**Files:**
- Move+Modify: `scripts/bootstrap-vpd.sql` → `scripts/bootstrap-schema.sql`
- Modify: `scripts/bootstrap-external-body.sql`
- Modify: `scripts/bootstrap-external.sql`
- Modify: `scripts/init-db-external.sh`
- Modify: `scripts/run-bootstrap.sh`
- Modify: `scripts/reset-app-owner-external.sql`

**Interfaces:**
- Consumes: 없음(독립). DB 유저 구조는 유지 결정.
- Produces: 부트스트랩이 3-user(APP_OWNER/APP_RUNTIME_USER/APP_ADMIN_USER)와 테이블 GRANT만 설정. VPD 컨텍스트/정책 권한 없음.

- [ ] **Step 1: bootstrap-vpd.sql 리네임**

```bash
git mv scripts/bootstrap-vpd.sql scripts/bootstrap-schema.sql
```

- [ ] **Step 2: bootstrap-schema.sql 에서 VPD 부분 제거**

`scripts/bootstrap-schema.sql`에서:
- **삭제**: 라인 45 `GRANT EXEMPT ACCESS POLICY TO APP_ADMIN;`, 라인 58 `GRANT CREATE ANY CONTEXT TO APP_OWNER;`, 라인 60 `GRANT EXECUTE ON DBMS_RLS TO APP_OWNER;`, 라인 63 `GRANT EXEMPT ACCESS POLICY TO APP_OWNER;`
- **삭제**: 라인 90~123 전체 (CTX_PKG 패키지 정의 + `CREATE OR REPLACE CONTEXT APP_CTX` + `GRANT EXECUTE ON APP_OWNER.CTX_PKG` 2건)
- **유지**: 롤 생성(APP_RUNTIME/APP_ADMIN), 유저 생성(APP_RUNTIME_USER/APP_ADMIN_USER), CREATE SESSION/TABLE/SEQUENCE/PROCEDURE/TRIGGER/VIEW/UNLIMITED TABLESPACE GRANT, 롤 부여.
- **검토**: `GRANT EXECUTE ON DBMS_SESSION TO APP_OWNER`(라인 61) — CTX_PKG가 사라지면 set_tenant용 DBMS_SESSION도 불필요. 다른 용도 없으면 삭제.
- 파일 상단 헤더 주석에서 VPD 설명을 "스키마 소유자 + 런타임/어드민 유저·권한 부트스트랩(VPD 없음, 격리는 앱 레벨)"으로 갱신.

- [ ] **Step 3: bootstrap-external-body.sql 에서 CTX_PKG 제거**

`scripts/bootstrap-external-body.sql`의 라인 94~123 영역(CTX_PKG 패키지 + APP_CTX CONTEXT + GRANT)을 삭제. 유저/스키마/GRANT 유지. EXEMPT/DBMS_RLS/CREATE ANY CONTEXT GRANT도 동일하게 제거.

- [ ] **Step 4: bootstrap-external.sql 점검**

Run: `grep -n "CTX_PKG\|APP_CTX\|DBMS_RLS\|EXEMPT\|set_tenant\|bootstrap-vpd\|CREATE.*CONTEXT" scripts/bootstrap-external.sql`
나오는 VPD 참조/호출부를 제거하거나 새 파일명으로 갱신.

- [ ] **Step 5: init-db-external.sh 에서 PASSKEY_VPD_ENABLED 제거**

`scripts/init-db-external.sh`에서:
- 라인 39 주석(`SE ... PASSKEY_VPD_ENABLED=false`) 제거 또는 "격리는 앱 레벨 @Filter"로 갱신
- 라인 66 `PASSKEY_VPD_ENABLED="${PASSKEY_VPD_ENABLED:-false}"` 삭제
- 라인 116 bootRun 전달부 `PASSKEY_VPD_ENABLED="${PASSKEY_VPD_ENABLED}" \` 삭제
- 라인 36 주석 예시의 `PASSKEY_VPD_ENABLED=false` 제거
- `bootstrap-vpd.sql` 참조가 있으면 `bootstrap-schema.sql`로 갱신

- [ ] **Step 6: run-bootstrap.sh 리네임 반영**

Run: `grep -n "bootstrap-vpd" scripts/run-bootstrap.sh`
나오는 `bootstrap-vpd.sql` → `bootstrap-schema.sql`로 치환.

- [ ] **Step 7: reset-app-owner-external.sql 점검**

`scripts/reset-app-owner-external.sql`의 `DROP_POLICY` 루프·CTX_PKG 보존 로직 확인. VPD 정책이 없는 환경에서 멱등(no-op)이 되도록 가드 유지(이미 ORA 예외 삼킴이면 무변경). CTX_PKG 보존 주석은 "VPD 제거 후 불필요하나 무해"로 갱신하거나 해당 보존 분기 제거.

- [ ] **Step 8: VPD 잔재 스캔 (스크립트 전반)**

Run: `grep -rn "bootstrap-vpd\|CTX_PKG\|APP_CTX\|DBMS_RLS\|set_tenant\|EXEMPT ACCESS\|PASSKEY_VPD_ENABLED\|ADD_POLICY" scripts/`
Expected: reset-app-owner-external.sql의 멱등 DROP_POLICY 가드(의도적 잔존) 외 0건. 그 외가 나오면 처리.

- [ ] **Step 9: 셸 스크립트 문법 검증**

Run: `bash -n scripts/init-db-external.sh && bash -n scripts/run-bootstrap.sh && echo "syntax OK"`
Expected: `syntax OK` (구문 오류 없음).

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "chore(scripts): 부트스트랩에서 VPD 제거, bootstrap-vpd→bootstrap-schema 리네임

CTX_PKG/APP_CTX CONTEXT/DBMS_RLS·EXEMPT·CREATE ANY CONTEXT GRANT 제거,
3-user 분리·테이블 GRANT 유지. init-db-external.sh 의 PASSKEY_VPD_ENABLED
삭제. reset SQL 의 DROP_POLICY 는 멱등 no-op 으로 보존.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: 마이그레이션 재작성(no-op) + V52 forward DROP

과거 VPD 마이그레이션을 흔적 없이 비우고, 기배포 DB에서 실제 객체를 떼는 forward-only `V52`를 추가한다. 이 Task는 반드시 Testcontainers로 검증한다.

**Files:**
- Modify: `core/src/main/resources/db/migration/V3__vpd_policies.sql`
- Modify: `core/src/main/resources/db/migration/V8__api_key_vpd_policy.sql`
- Modify: `core/src/main/resources/db/migration/V19__uuid_migration.sql` (DROP_POLICY/패키지 부분만)
- Modify: `core/src/main/resources/db/migration/V20__vpd_policy_for_uuid.sql`
- Modify: `core/src/main/resources/db/migration/V35__tenant_child_vpd_policies.sql`
- Modify: `core/src/main/resources/db/migration/V42__api_key_touch_last_used_vpd_off.sql`
- Create: `core/src/main/resources/db/migration/V52__drop_vpd.sql`

**Interfaces:**
- Consumes: Task 3 (앱이 더 이상 `api_key_lookup_pkg`를 호출하지 않음 → 패키지 제거 안전)
- Produces: 신규 DB에 VPD 객체 0. 기배포 DB는 V52가 7개 정책 + tenant_predicate + 2개 패키지 + CTX_PKG + APP_CTX 제거. 검증: `user_policies` 0행.

- [ ] **Step 1: 각 마이그레이션의 VPD 부분 경계 재확인**

Run: `grep -n "ADD_POLICY\|DROP_POLICY\|tenant_predicate\|CTX_PKG\|SYS_CONTEXT\|api_key_lookup_pkg\|CREATE TABLE\|HEXTORAW" core/src/main/resources/db/migration/V3__vpd_policies.sql core/src/main/resources/db/migration/V19__uuid_migration.sql core/src/main/resources/db/migration/V20__vpd_policy_for_uuid.sql core/src/main/resources/db/migration/V35__tenant_child_vpd_policies.sql`
Expected: V3/V20/V35는 전부 VPD(함수+정책)라 통째로 no-op. V19는 테이블 재생성(CREATE TABLE)과 VPD(DROP_POLICY/패키지)가 섞여 있으니 **VPD 줄만** 식별.

- [ ] **Step 2: V3 / V20 / V35 / V42 를 no-op 으로 비우기**

각 파일 내용을 아래 형태로 교체(버전·파일명 보존). 예 — `V3__vpd_policies.sql`:

```sql
-- V3 — (의도적 no-op) VPD(DBMS_RLS) 정책은 제거되었습니다.
-- 원래 이 마이그레이션은 APP_OWNER.tenant_predicate 함수와
-- CREDENTIAL_TENANT_ISOLATION 정책을 생성했습니다. 테넌트 격리는 이제
-- 앱 레벨 Hibernate @Filter(TenantFilterAspect)가 전담합니다.
-- 기배포 DB의 실제 객체 제거는 V52__drop_vpd.sql 가 수행합니다.
-- 파일/버전은 Flyway 히스토리 연속성을 위해 보존합니다.
SELECT 1 FROM dual;
```

동일 패턴으로:
- `V20__vpd_policy_for_uuid.sql` — "tenant_predicate 재정의 + 2개 정책 재부착" 설명으로 no-op
- `V35__tenant_child_vpd_policies.sql` — "자식 5개 테이블 정책" 설명으로 no-op
- `V42__api_key_touch_last_used_vpd_off.sql` — "api_key_lookup_pkg.touch_last_used 명시 파라미터화. 패키지 자체가 V52에서 제거됨" 설명으로 no-op

> `SELECT 1 FROM dual;` 는 Flyway가 빈 마이그레이션을 허용하지 않는 경우를 대비한 안전한 no-op 스테이트먼트.

- [ ] **Step 3: V8 에서 VPD 정책 + 패키지 제거 (파일은 보존, VPD 부분만 no-op)**

`V8__api_key_vpd_policy.sql`의 `DBMS_RLS.ADD_POLICY` 블록(라인 14-31)과 `api_key_lookup_pkg` 패키지/바디/GRANT(라인 33-153) 전체를 제거하고 no-op 헤더로 교체:

```sql
-- V8 — (의도적 no-op) api_key VPD 정책과 definer-rights api_key_lookup_pkg 는
-- 제거되었습니다. ApiKey prefix 룩업은 이제 앱 native 쿼리(ApiKeyLookupService)가
-- 수행합니다. 실제 객체 제거는 V52__drop_vpd.sql.
SELECT 1 FROM dual;
```

- [ ] **Step 4: V19 에서 VPD 줄만 제거 (테이블 재생성은 유지)**

`V19__uuid_migration.sql`에서 **DROP_POLICY 2건**(CREDENTIAL/API_KEY 정책 제거 블록)과 **api_key_lookup_pkg 재정의 블록**만 삭제. RAW(16) 테이블 재생성·데이터 변환·인덱스 등 나머지는 **그대로 유지**. 삭제 지점에 한 줄 주석:

```sql
-- (VPD DROP_POLICY 2건 및 api_key_lookup_pkg 재정의는 제거됨 — V52__drop_vpd.sql 참조)
```

Step 1의 grep 결과로 정확한 라인 범위를 짚어 해당 PL/SQL 블록만 도려낸다. CREATE TABLE/INSERT/ALTER는 절대 건드리지 않는다.

- [ ] **Step 5: V52__drop_vpd.sql 작성**

`core/src/main/resources/db/migration/V52__drop_vpd.sql` 신규 작성. 멱등 가드(fail-closed 양성 매칭)로 7개 정책 + 함수 + 2개 패키지 + 컨텍스트 제거:

```sql
-- ============================================================
-- V52 — VPD(DBMS_RLS) 객체 전면 제거 (forward-only)
--
-- 신규 DB: 재작성된 V3~V42 가 VPD 객체를 애초에 만들지 않으므로 전부 no-op 통과.
-- 기배포 DB(EE/XE): 실제 7개 정책 + tenant_predicate + api_key_lookup_pkg
--   + CTX_PKG + APP_CTX 를 제거.
-- SE2: 정책이 원래 없으므로 DROP_POLICY 는 ORA-28101, 컨텍스트/패키지 없으면
--   ORA-04043 → 모두 삼켜 no-op.
-- 모든 가드는 fail-closed 양성 매칭(SQLCODE = -코드)로 작성 — Oracle 3치
--   논리(NULL<>x=unknown) 우회.
-- 배포: 이 변경으로 V3/V8/V19/V20/V35/V42 체크섬이 바뀌므로 기배포 DB 는
--   `flyway repair` 후 `flyway migrate`. 상세는 docs 의 repair runbook.
-- ============================================================

-- 1) 7개 VPD 정책 제거 (멱등)
DECLARE
  PROCEDURE drop_policy(p_table IN VARCHAR2, p_policy IN VARCHAR2) IS
  BEGIN
    DBMS_RLS.DROP_POLICY(
      object_schema => 'APP_OWNER',
      object_name   => p_table,
      policy_name   => p_policy);
  EXCEPTION
    WHEN OTHERS THEN
      IF SQLCODE = -28101 THEN NULL;     -- ORA-28101: 정책 없음
      ELSIF SQLCODE = -439 THEN NULL;    -- ORA-00439: SE2(FGAC 미지원)
      ELSIF SQLCODE = -942 THEN NULL;    -- ORA-00942: 테이블 없음
      ELSE RAISE;
      END IF;
  END;
BEGIN
  drop_policy('CREDENTIAL',                  'CREDENTIAL_TENANT_ISOLATION');
  drop_policy('API_KEY',                     'API_KEY_TENANT_ISOLATION');
  drop_policy('TENANT_ALLOWED_ORIGIN',       'TENANT_ALLOWED_ORIGIN_ISOLATION');
  drop_policy('TENANT_ACCEPTED_FORMAT',      'TENANT_ACCEPTED_FORMAT_ISOLATION');
  drop_policy('TENANT_AAGUID_POLICY',        'TENANT_AAGUID_POLICY_ISOLATION');
  drop_policy('TENANT_AAGUID_POLICY_ENTRY',  'TENANT_AAGUID_ENTRY_ISOLATION');
  drop_policy('TENANT_WEBAUTHN_SNAPSHOT',    'TENANT_WEBAUTHN_SNAPSHOT_ISOLATION');
END;
/

-- 2) tenant_predicate 함수 제거 (멱등)
BEGIN
  EXECUTE IMMEDIATE 'DROP FUNCTION APP_OWNER.tenant_predicate';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -4043 THEN NULL;        -- ORA-04043: 객체 없음
    ELSE RAISE;
    END IF;
END;
/

-- 3) api_key_lookup_pkg 패키지 제거 (앱 native 쿼리로 대체됨)
BEGIN
  EXECUTE IMMEDIATE 'DROP PACKAGE APP_OWNER.api_key_lookup_pkg';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -4043 THEN NULL;
    ELSE RAISE;
    END IF;
END;
/

-- 4) CTX_PKG 패키지 제거
BEGIN
  EXECUTE IMMEDIATE 'DROP PACKAGE APP_OWNER.CTX_PKG';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -4043 THEN NULL;
    ELSE RAISE;
    END IF;
END;
/

-- 5) APP_CTX 컨텍스트 제거
--    주의: APP_OWNER 가 DROP CONTEXT 권한(DROP ANY CONTEXT)을 가지지 못하는
--    배포(외부 SE)에서는 ORA-01031 이 난다 → 삼켜서 보존(무해, 참조 패키지가
--    이미 사라져 컨텍스트는 동작 불가 상태로 남을 뿐).
BEGIN
  EXECUTE IMMEDIATE 'DROP CONTEXT APP_CTX';
EXCEPTION
  WHEN OTHERS THEN
    IF SQLCODE = -4043 THEN NULL;        -- ORA-04043: 컨텍스트 없음
    ELSIF SQLCODE = -1031 THEN NULL;     -- ORA-01031: DROP CONTEXT 권한 없음 → 보존
    ELSE RAISE;
    END IF;
END;
/
```

- [ ] **Step 6: 신규 DB 경로 검증 — Testcontainers 전체 마이그레이션**

Run: `./gradlew :core:test --tests "*AppLevelIsolationIT" -q`
Expected: Testcontainers Oracle이 V1~V52 전 마이그레이션을 적용하고 부팅. 재작성된 no-op + V52가 깨끗이 통과해야 PASS. (이게 신규 환경에서 VPD 객체 0 + 격리 동작을 동시에 증명.)

- [ ] **Step 7: V52 멱등성 재실행 검증 (수동 또는 전용 IT)**

기존에 Flyway repeatable/idempotency를 검증하는 IT가 있으면 실행. 없으면 Step 6의 컨테이너 로그에서 V52가 ORA 예외 없이 적용됐는지 확인. (정책 없는 신규 DB라 모든 DROP이 -28101/-4043 경로로 no-op 통과해야 함 — RAISE 안 나야 PASS.)

Run: `./gradlew :core:test --tests "*MigrationIT" --tests "*FlywayIT" -q 2>/dev/null || echo "전용 마이그레이션 IT 없음 — Step 6 로그로 확인"`

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(db): VPD 마이그레이션 재작성(no-op) + V52 forward DROP

V3/V8/V20/V35/V42 를 no-op 으로 비우고 V19 의 DROP_POLICY·패키지 줄만 제거
(테이블 재생성 유지). V52__drop_vpd.sql 가 7개 정책+tenant_predicate+
api_key_lookup_pkg+CTX_PKG+APP_CTX 를 멱등 가드로 제거. 신규 DB 는 전부
no-op, 기배포 DB 는 flyway repair 후 실제 객체 제거.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: 주석 정리 + 배포 runbook 문서화 + 최종 검증

흩어진 "VPD filters by SYS_CONTEXT" 주석을 갱신하고, flyway repair 배포 절차를 문서화하며, base 대조로 회귀를 확정한다.

**Files:**
- Modify: repository/service/entity의 VPD 언급 주석 (grep으로 발견되는 것들)
- Create: `docs/superpowers/runbooks/2026-06-23-vpd-removal-deploy.md` (또는 프로젝트의 기존 runbook/배포 문서 위치)

**Interfaces:**
- Consumes: Task 1-7 전부
- Produces: 코드/문서에 부정확한 VPD 주석 0. 배포 runbook 1건.

- [ ] **Step 1: VPD 언급 주석 전수 발견**

Run: `grep -rn "VPD\|SYS_CONTEXT\|APP_CTX\|ctx_pkg\|DBMS_RLS\|EXEMPT ACCESS\|definer-rights" core admin-app passkey-app rp-app --include=*.java`
Expected: repository/service/entity의 javadoc·인라인 주석들. `CredentialAdminService`처럼 이미 "VPD 가 admin-app 에서 비활성"으로 정확한 것은 "앱 레벨 tenantId 검사가 단일 격리 layer"로 더 명확히 하되 의미 보존.

- [ ] **Step 2: 발견된 주석 갱신**

각 주석을 실제 동작에 맞게 수정:
- "VPD filters by tenant_id = SYS_CONTEXT" → "app-level Hibernate @Filter(TenantFilterAspect) isolates by tenant_id"
- "definer-rights PL/SQL bypasses VPD safely" → "native 쿼리로 tenant context 전에 전역 prefix 룩업"
- entity의 `@Filter` 위 주석에 VPD 이중방어 언급이 있으면 "@Filter 가 유일 격리 계층"으로 수정

ApiKeyAuthFilter javadoc(라인 27-32)의 "definer-rights PL/SQL bypasses VPD safely" 도 갱신.

- [ ] **Step 3: 배포 runbook 작성**

`docs/superpowers/runbooks/2026-06-23-vpd-removal-deploy.md` 작성:

```markdown
# VPD 제거 배포 runbook

## 영향
V3/V8/V19/V20/V35/V42 마이그레이션 내용이 재작성되어 **체크섬이 변경됨**.
prod 는 validate-on-migrate=true 이므로 기배포 DB 는 `flyway repair` 필요.

## 절차 (기배포 EE/XE DB)
1. 앱 중단 또는 롤링 배포 준비.
2. `flyway repair` — 재작성된 마이그레이션의 기록 체크섬을 새 파일 체크섬으로 재정렬.
3. `flyway migrate` — V52__drop_vpd.sql 이 7개 정책 + tenant_predicate +
   api_key_lookup_pkg + CTX_PKG + APP_CTX 제거.
4. 검증:
   - `SELECT COUNT(*) FROM user_policies;` → 0
   - `SELECT object_name FROM user_objects WHERE object_name IN
     ('TENANT_PREDICATE','API_KEY_LOOKUP_PKG','CTX_PKG');` → 0행
   - `SELECT * FROM all_context WHERE namespace='APP_CTX';` → 0행
     (DROP CONTEXT 권한 없으면 1행 잔존 가능 — 무해, 참조 패키지 부재로 동작 불가)
5. 신규 앱 배포(PASSKEY_VPD_ENABLED 환경변수 제거 — 더 이상 읽지 않음).

## 절차 (SE2 DB)
- 정책이 원래 없으므로 `flyway repair` 후 `flyway migrate` 만. V52 는 전부 no-op.
- `PASSKEY_VPD_ENABLED` 환경변수가 배포 설정에 있으면 제거(무시되지만 정리).

## 절차 (신규 DB)
- 일반 `flyway migrate` 만. 재작성된 V3~V42 + V52 전부 no-op/clean.

## 롤백
- 코드 롤백은 가능하나 DB 는 forward-only(VPD 정책은 다시 안 생성됨). 격리는
  앱 레벨 @Filter 가 계속 보장하므로 정책 부재가 보안 회귀가 아님.
```

(프로젝트에 기존 runbook 디렉토리 관례가 있으면 그 위치/포맷을 따른다. Run: `ls docs/ | grep -i runbook` 로 확인.)

- [ ] **Step 4: VPD 잔재 최종 스캔 (코드+설정+스크립트 전체)**

Run: `grep -rn "passkey\.vpd\|PASSKEY_VPD_ENABLED\|TenantAwareDataSource\|api_key_lookup_pkg\|ctx_pkg\|CTX_PKG\|tenant_predicate\|DBMS_RLS\.ADD_POLICY\|core\.vpd\." core admin-app passkey-app rp-app scripts --include=*.java --include=*.yml --include=*.sql --include=*.sh`
Expected: V52의 DROP 참조, 과거 마이그레이션 no-op 주석의 설명적 언급, reset SQL 멱등 가드만 남음(전부 의도적). 활성 코드·설정의 VPD 참조는 0.

- [ ] **Step 5: base 대조 회귀 판정**

Run: `git fetch -q 2>/dev/null; git diff --stat main...HEAD`
변경 파일 목록이 spec 범위(core config/tenant, passkey-app security, yml, migration, scripts, docs)와 일치하는지 확인. 범위 밖 파일이 있으면 검토.

전체 테스트는 base 대조로(`./gradlew build` 빨강은 pre-existing이라 게이트 부적합):

Run: `./gradlew :core:test :passkey-app:test --tests "*AppLevelIsolationIT" --tests "*TenantFilter*" --tests "*ApiKey*" -q`
Expected: 격리·ApiKey 핵심 테스트 그린. 실패 시 base(main)에서 동일 테스트가 통과하는지 대조해 신규 회귀만 확정.

- [ ] **Step 6: dev 부팅 dogfooding (선택, 사용자 환경)**

dev DB로 passkey-app + admin-app 부팅 후:
- admin-ui에서 테넌트별 credential 목록이 격리되는지 육안 확인
- passkey-app 등록/인증 ceremony 1회(ApiKey native 룩업 경로 실동작)

(이 스텝은 로컬 Oracle/Redis 기동이 필요하므로, 자동화 불가 시 사용자에게 위임하고 결과 보고.)

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "docs: VPD 주석 갱신 + 제거 배포 runbook + 최종 잔재 스캔

repository/service/entity 의 'VPD filters by SYS_CONTEXT' 주석을 'app-level
@Filter isolates' 로 갱신. flyway repair 배포 절차 runbook 작성. 코드·설정·
스크립트 전반 VPD 잔재 0 확인(V52 DROP·no-op 주석·reset 멱등가드만 의도적 잔존).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review (작성자 체크 결과)

**Spec coverage:** spec 9개 섹션 ↔ task 매핑:
- §3-1 Java core(TenantAwareDataSource 삭제/CoreDataSourceConfig) → Task 1 ✓
- §3-1 패키지 리네임 vpd→tenant → Task 2 ✓
- §3-2 ApiKeyLookupService native 교체 → Task 3 ✓
- §3-3 yml vpd.enabled 제거 → Task 4 ✓
- §6 테스트 정리 + AppLevelIsolationIT 게이트 → Task 5 ✓
- §5 스크립트 정리/리네임 → Task 6 ✓
- §4 마이그레이션 재작성 + V52 → Task 7 ✓
- §6 주석 갱신 + §4-4 runbook + §8 검증 → Task 8 ✓
- §2 DB 유저 3분할 유지 → Global Constraints + Task 6(EXEMPT만 제거) ✓

**Placeholder scan:** "add appropriate ..."/TBD/TODO 없음. 모든 코드 스텝에 실제 코드/SQL 포함. (V19 라인 범위·기존 IT 베이스 클래스는 "Step 1 grep으로 식별"·"기존 IT 패턴 복사"로 구체적 행동 지정 — 파일 내용 의존이라 실행 시점 grep이 정확.)

**Type consistency:** `findByPrefix(String): Optional<ApiKeyAuthRow>`, `touchLastUsed(UUID,UUID,Instant): void`, `ApiKeyAuthRow(UUID,UUID,String,Instant,Instant)` — Task 3 전반 일관, ApiKeyAuthFilter 호출부(라인 149,183,209)와 일치. 패키지 `com.crosscert.passkey.core.tenant.*` — Task 2 이후 전 task 일관.
