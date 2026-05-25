# Phase 0 — Foundation: 설계 문서

작성일: 2026-05-25
상태: 검토 대기
대상 Phase: 0 (Foundation) / 전체 5개 Phase 중 첫 번째

## 1. 배경과 1차 목표

Crosscert가 납품할 **멀티테넌트 FIDO2 Passkey SaaS 제품**의 그린필드 구축. 최종 산출물은 다음 5개로 구성된다.

1. `:passkey-app` — Spring Boot 단일 모듈. FIDO2 ceremony 코어 + RP HTTP API 표면.
2. `:admin-app` — Spring Boot 단일 모듈. Admin REST API + Admin SPA(React/TS) 정적 자원 서빙 + 운영 스케줄러.
3. `sdk-java/` — RP 백엔드용 Java SDK. `:passkey-app`의 RP API에 대한 얇은 래퍼.
4. `examples/sample-rp/` — SDK dogfood용 샘플 RP 서버. 메인 빌드 그래프 밖.
5. Oracle 스키마 + Redis 설정.

전체 구축 순서는 `Phase 0 → 1 → 2 → 3 → 4`로 합의되었다. 이 문서는 그중 **Phase 0(Foundation)** 만 다룬다.

### 1.1 Phase 0의 목표 (Definition of Done)

> `:passkey-app`과 `:admin-app`이 각자 독립 bootJar로 기동되고, Oracle 19c + Redis 7과 연결되며, **Oracle VPD에 의해 테넌트 격리가 실제로 동작**하는 골격을 만든다.

핵심 가치는 **"VPD가 실제로 격리한다"** 가 자동 테스트로 증명되는 것. 도메인 로직(FIDO2 ceremony, API Key, 운영자 인증 등)은 의도적으로 Phase 0에 포함하지 않는다.

## 2. 기술 스택 (확정)

| 영역 | 선택 |
|---|---|
| 언어 | Java 17 |
| 백엔드 프레임워크 | Spring Boot 3.5.x (latest patch at impl time) |
| 빌드 | Gradle (Kotlin DSL) 멀티모듈 |
| 데이터베이스 | Oracle 19c (로컬은 Oracle XE 컨테이너) |
| 캐시/세션/PubSub | Redis 7 |
| ORM/Persistence | Spring Data JPA |
| DB 마이그레이션 | Flyway |
| 프론트(Admin) | React + TypeScript (Phase 2에서 본격 진행) |

## 3. 멀티모듈 구조 (확정)

`:passkey-app`과 `:admin-app`은 **각자 독립된 bootJar**로 따로 기동 가능해야 한다. 공용 코드는 `:core` 한 모듈에 모은다.

```
Passkey2/
├ build.gradle.kts                  # 루트 — 플러그인, BOM, java toolchain
├ settings.gradle.kts               # :core, :passkey-app, :admin-app 등록
├ docker-compose.yml                # Oracle 19c XE + Redis 7
├ scripts/
│  ├ wait-for-oracle.sh
│  └ bootstrap-vpd.sql              # DBA 권한 1회 실행 (role/유저/grant)
├ core/
│  └ src/main/java/com/crosscert/passkey/core/
│     ├ entity/                     # Tenant, ScheduledLease, MdsBlobCache, Credential(컬럼만)
│     ├ repository/                 # Spring Data JPA repository 인터페이스
│     ├ vpd/                        # TenantContext, TenantContextHolder, TenantAwareDataSource
│     ├ config/                     # Jackson, Redis, ProblemDetail, 공통 application.yml fragments
│     ├ audit/                      # (패키지만, Phase 2에서 채움)
│     └ mds/                        # MDS BLOB 파싱·검증 공용 로직 (Phase 3에서 채움)
├ passkey-app/
│  └ src/main/java/com/crosscert/passkey/app/
│     ├ PasskeyApplication.java
│     ├ fido2/                      # ceremony 코어 (Phase 1)
│     │   ├ registration/
│     │   ├ authentication/
│     │   ├ challenge/
│     │   └ mds/                    # 런타임 캐시 + Redis 구독자 (Phase 3)
│     ├ api/v1/rp/                  # (Phase 1)
│     └ config/                     # 이 앱 한정 설정
└ admin-app/
   └ src/main/
      ├ java/com/crosscert/passkey/admin/
      │  ├ AdminApplication.java
      │  ├ api/v1/admin/            # (Phase 2)
      │  ├ scheduler/               # MDS @Scheduled, RefreshToken 정리 (Phase 3)
      │  ├ session/                 # Spring Session/Redis (Phase 2)
      │  ├ audit/verify/            # audit chain 검증 (Phase 2)
      │  └ config/
      └ resources/
         └ static/                  # Admin SPA 빌드 산출물 (Phase 2)
```

### 3.1 의존 그래프

```
:passkey-app ──depends on──▶ :core
:admin-app   ──depends on──▶ :core
:passkey-app  ✕  :admin-app   (서로 의존 없음)
```

### 3.2 캡슐화 규칙

- **FIDO2 ceremony 코어는 `:passkey-app/fido2/**`에만 존재**. `:core`나 `:admin-app`에서 이 패키지를 import할 수 없다(컴파일 타임에 보장).
- **MDS BLOB 파싱·서명 검증 로직은 `:core/mds`** 에 둔다. `:admin-app/scheduler`는 이를 사용해 BLOB을 처리하고 `mds_blob_cache`에 UPSERT하며, ceremony 코드를 절대 호출하지 않는다.
- `:core`는 양쪽 앱이 공유하는 "데이터·인프라·설정"만 담는다. 비대해지면 Phase 0 종료 후 별도로 쪼갠다.

## 4. Oracle 데이터베이스 설계

### 4.1 멀티테넌시 정책: VPD + 2-role

- 모든 tenant-scoped 테이블에 `tenant_id` 컬럼.
- VPD policy가 `tenant_id = SYS_CONTEXT('APP_CTX','TENANT_ID')`를 자동 부착.
- DB 사용자/롤 두 개:

| 롤 | 사용 주체 | 권한 |
|---|---|---|
| `APP_RUNTIME` | passkey-app·admin-app의 일반 트랜잭션 | VPD policy 항상 적용. 테넌트 데이터만 자동 필터링. platform-scoped 테이블에 대한 쓰기 권한 없음. |
| `APP_ADMIN` | admin-app의 스케줄러, Flyway 마이그레이션 | VPD policy 우회(`exempt access policy`). platform-scoped 테이블(`tenant`, `mds_blob_cache`, `scheduler_lease`) 쓰기. |

### 4.2 Phase 0에서 생성하는 스키마 (V1 마이그레이션)

**Platform-scoped (tenant_id 없음, VPD 미적용):**

- `tenant`
  - `id` (PK, VARCHAR2)
  - `display_name` VARCHAR2
  - `status` VARCHAR2 (`active`/`suspended`)
  - `created_at`, `updated_at` TIMESTAMP
- `scheduler_lease`
  - `name` (PK, VARCHAR2) — e.g. `'mds-refresh'`
  - `holder` VARCHAR2 (host:pid)
  - `expires_at` TIMESTAMP
- `mds_blob_cache`
  - `id` (PK, NUMBER, sequence) — singleton row 사용
  - `version` NUMBER — MDS payload의 `no`
  - `next_update` DATE
  - `fetched_at` TIMESTAMP
  - `blob_jwt` CLOB

**Tenant-scoped (tenant_id 컬럼 + VPD policy):**

- `credential` — Phase 1에서 채울 테이블이지만, **Phase 0에서 컬럼 정의 + VPD policy 적용까지 한다** (VPD 격리 자동 테스트의 대상이 된다)
  - `id` (PK, NUMBER, sequence)
  - `tenant_id` (FK → tenant.id, NOT NULL)
  - `user_handle` RAW(64)
  - `credential_id` RAW(256) UNIQUE per tenant
  - `public_key` BLOB
  - `sign_count` NUMBER DEFAULT 0
  - `aaguid` RAW(16)
  - `transports` VARCHAR2(128)
  - `attestation_fmt` VARCHAR2(64)
  - `backup_state` CLOB (JSON; `IS JSON` constraint)
  - `created_at`, `last_used_at` TIMESTAMP

### 4.3 PL/SQL 컨텍스트

```sql
CREATE OR REPLACE CONTEXT app_ctx USING ctx_pkg;

CREATE OR REPLACE PACKAGE ctx_pkg AS
  PROCEDURE set_tenant(p_tid IN VARCHAR2);
  PROCEDURE clear_tenant;
END;
/

CREATE OR REPLACE PACKAGE BODY ctx_pkg AS
  PROCEDURE set_tenant(p_tid IN VARCHAR2) IS
  BEGIN
    DBMS_SESSION.SET_CONTEXT('APP_CTX', 'TENANT_ID', p_tid);
  END;
  PROCEDURE clear_tenant IS
  BEGIN
    DBMS_SESSION.CLEAR_CONTEXT('APP_CTX', 'TENANT_ID');
  END;
END;
/

GRANT EXECUTE ON ctx_pkg TO APP_RUNTIME;
```

### 4.4 VPD 정책 등록 예시

```sql
CREATE OR REPLACE FUNCTION tenant_predicate(
  schema_name IN VARCHAR2, object_name IN VARCHAR2)
RETURN VARCHAR2 IS
BEGIN
  RETURN 'tenant_id = SYS_CONTEXT(''APP_CTX'',''TENANT_ID'')';
END;
/

BEGIN
  DBMS_RLS.ADD_POLICY(
    object_schema   => 'APP_OWNER',
    object_name     => 'CREDENTIAL',
    policy_name     => 'CREDENTIAL_TENANT_ISOLATION',
    function_schema => 'APP_OWNER',
    policy_function => 'TENANT_PREDICATE',
    statement_types => 'SELECT,INSERT,UPDATE,DELETE',
    update_check    => TRUE
  );
END;
/
```

### 4.5 Flyway 위치 및 실행

- 마이그레이션 SQL은 `:core/src/main/resources/db/migration/`에 둔다 (스키마는 공유 자산).
- **admin-app만 Flyway를 자동 실행**한다 (`spring.flyway.enabled=true`).
- passkey-app은 Flyway 비활성 (`spring.flyway.enabled=false`). 마이그레이션은 admin 책임이며, passkey는 이미 준비된 스키마를 사용한다는 책임 분리.
- Flyway는 `APP_ADMIN` 계정으로 실행하여 DDL/role grant/dbms_rls 호출이 가능하도록 한다.

### 4.6 테넌트 컨텍스트 주입 (Java 측)

- `TenantContextHolder` — `ThreadLocal<String>`. Filter가 식별한 tenant id를 저장.
- `TenantAwareDataSource` — HikariCP `DataSource`를 wrap.
  - `getConnection()` 시: `TenantContextHolder.get()`을 읽어 `BEGIN ctx_pkg.set_tenant(?); END;` 실행. 값이 null이면 set하지 않음(결과적으로 모든 row가 필터링됨 — 안전 기본값).
  - 반환 시 (`Connection.close()` 위임 직전): `ctx_pkg.clear_tenant` 실행 후 pool로 반환.
- Phase 0에서는 식별 Filter를 **dev-only stub**으로 둔다: `X-Tenant-Id` 헤더가 있으면 그 값을 ThreadLocal에 set. 운영용 인증(API Key, Spring Session)은 Phase 1, 2에서 정식 구현.

## 5. Redis (Phase 0)

Phase 0에서는 **커넥션/설정/health check만** 구성하고 실제 용도(Spring Session, pub/sub, cache, rate limit)는 후속 Phase로 미룬다.

- `docker-compose.yml`에 Redis 7 서비스.
- `:core/config/RedisConfig`에 `LettuceConnectionFactory` + `RedisTemplate<String, String>` 빈.
- 두 앱 모두 `/actuator/health/redis`가 `UP`이어야 한다.

## 6. 공통 인프라

- **Jackson**: `JavaTimeModule` 등록, `WRITE_DATES_AS_TIMESTAMPS=false`, `FAIL_ON_UNKNOWN_PROPERTIES=true` (입력 유연성보다 입력 검증을 우선).
- **에러 응답**: Spring 6의 `ProblemDetail` (RFC 7807) 사용. `:core/config/GlobalExceptionHandler`에서 표준화.
- **Logback**: profile별 분리(`local`, `dev`, `prod`). local은 콘솔 + 컬러, prod는 JSON.
- **application.yml**:
  - `:core/src/main/resources/application-common.yml` — DataSource, JPA, Redis, Jackson 공통.
  - 각 앱은 `application.yml`에서 `spring.config.import: classpath:application-common.yml`로 import.
  - profile은 `local`(docker-compose 연결), `dev`, `prod`.

## 7. 로컬 개발 환경

```sh
# 1. 인프라 기동
docker-compose up -d

# 2. 최초 1회 DBA 작업 (role/유저/grant)
./scripts/bootstrap-vpd.sh   # bootstrap-vpd.sql을 SYS 권한으로 실행

# 3. admin-app 기동 (Flyway 자동 실행으로 스키마 생성)
./gradlew :admin-app:bootRun
# → http://localhost:8081/actuator/health → 200 OK

# 4. passkey-app 기동
./gradlew :passkey-app:bootRun
# → http://localhost:8080/actuator/health → 200 OK
```

## 8. 검증: Phase 0 완료 기준

다음 자동 테스트가 모두 통과하면 Phase 0 완료로 선언한다.

### 8.1 Hello World 테스트

- `:admin-app`이 8081 포트로 기동되고 `/actuator/health`가 `{"status":"UP"}`.
- `:passkey-app`이 8080 포트로 기동되고 `/actuator/health`가 `{"status":"UP"}`.
- `db` 컴포넌트 health가 UP.
- `redis` 컴포넌트 health가 UP.

### 8.2 VPD 격리 자동 테스트 (필수)

`:core/src/test/java/.../vpd/VpdIsolationIT.java`에 `@SpringBootTest` 통합 테스트로:

1. **준비**: `APP_ADMIN`으로 테넌트 `T_A`, `T_B` INSERT. 각각에 credential row 1개씩 INSERT.
2. **격리 테스트 (APP_RUNTIME)**:
   - 테넌트 컨텍스트를 `T_A`로 set → `SELECT * FROM credential` → row 1개(T_A의 것).
   - 같은 connection에서 컨텍스트를 `T_B`로 재set → row 1개(T_B의 것).
   - 컨텍스트를 clear → 0 rows.
3. **우회 테스트 (APP_ADMIN)**:
   - 컨텍스트 없이 `SELECT * FROM credential` → 2 rows (T_A + T_B).
4. **쓰기 강제 테스트 (APP_RUNTIME)**:
   - 컨텍스트 `T_A`로 set한 상태에서 `tenant_id='T_B'`인 row를 INSERT 시도 → `update_check=TRUE` 정책에 의해 실패.

이 4개 시나리오가 모두 통과해야 한다.

## 9. Phase 0에서 의도적으로 제외한 항목

| 항목 | 다룰 Phase |
|---|---|
| FIDO2 ceremony 코어 (registration / authentication / attestation) | Phase 1 |
| `/api/v1/rp/**` HTTP 표면 | Phase 1 |
| API Key 발급/검증, Rate Limit | Phase 1 |
| `/.well-known/jwks` | Phase 1 |
| 운영자 인증 + Spring Session | Phase 2 |
| Admin SPA (React/TS) | Phase 2 |
| `audit_log` 체인 + 검증 | Phase 2 |
| MDS 다운로드 + Redis pub/sub | Phase 3 |
| `sdk-java`, `examples/sample-rp` | Phase 4 |
| CI/CD 파이프라인 | 별도 트랙 |

## 10. 후속 Phase로 넘기는 결정

Phase 0에서 결정하지 않고 다음 Phase에서 정한다:

- **API Key 형식 / hashing 알고리즘** → Phase 1에서 결정.
- **운영자 권한 모델(RBAC vs ABAC)** → Phase 2에서 결정.
- **audit_log 체인 알고리즘 세부(해시 함수, 청크 단위)** → Phase 2에서 결정.
- **MDS 미적용 상태에서 ceremony 동작 정책(fail-open / fail-closed)** → Phase 3에서 결정.
- **SDK 패키지 좌표(group/artifact) 및 배포 채널** → Phase 4에서 결정.

## 11. 위험 요소와 대응

| 위험 | 대응 |
|---|---|
| Oracle XE 컨테이너의 첫 부팅 시간이 길어 로컬 DX 저하 | `docker-compose.yml`에 healthcheck 등록, `wait-for-oracle.sh` 제공. dev 머신에 영구 볼륨 마운트. |
| Hikari가 connection을 미리 워밍업할 때 ThreadLocal 미설정 → set_tenant 실패 | Pool 초기화 단계에서는 `ctx_pkg.set_tenant`를 호출하지 않도록 wrapper를 명시적으로 분기. `getConnection()` 시점에만 호출. |
| Flyway 마이그레이션이 APP_RUNTIME 권한 부족으로 실패 | Flyway 실행 계정을 `APP_ADMIN`으로 명시. SQL 안에서 grant·dbms_rls 호출 가능. |
| dev stub Filter(`X-Tenant-Id` 헤더)가 운영에 유출 | `@Profile("!prod")`로 활성화 제한. prod 빌드에서는 빈 등록되지 않음. Phase 1의 정식 인증 Filter가 들어오면 자동으로 대체. |
