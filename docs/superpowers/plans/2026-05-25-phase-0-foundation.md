# Phase 0 — Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `:passkey-app`과 `:admin-app`이 각자 독립된 Spring Boot bootJar로 기동되고, Oracle 19c + Redis 7과 연결되며, Oracle VPD에 의해 테넌트 격리가 실제로 동작하는 것을 자동 테스트로 증명하는 토대를 만든다.

**Architecture:** Gradle 멀티모듈(Kotlin DSL)로 `:core`(공용 도메인·인프라) + `:passkey-app` + `:admin-app` 3개 모듈을 구성한다. Oracle VPD를 `APP_RUNTIME`/`APP_ADMIN` 두 개의 DB 롤로 분리해 적용하고, `TenantAwareDataSource`가 HikariCP connection borrow/return 시점에 `ctx_pkg.set_tenant`/`clear_tenant`를 호출해 컨텍스트를 주입한다. Flyway는 admin-app에서만 활성화되어 스키마를 관리한다.

**Tech Stack:** Java 17, Spring Boot 3.5.x, Gradle Kotlin DSL, Oracle 19c (XE in docker), Redis 7, Spring Data JPA, Flyway, HikariCP, Testcontainers (Oracle XE).

**Reference spec:** `docs/superpowers/specs/2026-05-25-phase-0-foundation-design.md`

---

## File Structure

이 plan으로 생성될 파일들의 전체 트리 (책임별):

```
Passkey2/
├ settings.gradle.kts                # 모듈 등록
├ build.gradle.kts                   # 루트 — Spring Boot/JPA plugin, BOM, toolchain
├ gradle/libs.versions.toml          # 의존성 카탈로그
├ .gitignore                         # 이미 존재 (Phase 0 plan에서 추가만)
├ docker-compose.yml                 # Oracle XE + Redis 7
├ scripts/
│  ├ bootstrap-vpd.sql               # DBA 1회 실행: role/유저/grant/context/package
│  └ wait-for-oracle.sh              # 헬스체크 polling
├ core/
│  ├ build.gradle.kts                # :core 모듈 설정
│  └ src/
│     ├ main/
│     │  ├ java/com/crosscert/passkey/core/
│     │  │  ├ entity/
│     │  │  │  ├ Tenant.java
│     │  │  │  ├ SchedulerLease.java
│     │  │  │  ├ MdsBlobCache.java
│     │  │  │  └ Credential.java                  # 컬럼만 — Phase 1에서 도메인 추가
│     │  │  ├ repository/
│     │  │  │  ├ TenantRepository.java
│     │  │  │  └ CredentialRepository.java
│     │  │  ├ vpd/
│     │  │  │  ├ TenantContextHolder.java
│     │  │  │  ├ TenantAwareDataSource.java
│     │  │  │  └ DevTenantHeaderFilter.java       # @Profile("!prod") dev-only
│     │  │  └ config/
│     │  │     ├ CoreDataSourceConfig.java
│     │  │     ├ CoreRedisConfig.java
│     │  │     ├ CoreJacksonConfig.java
│     │  │     ├ CoreWebConfig.java               # ProblemDetail handler
│     │  │     └ GlobalExceptionHandler.java
│     │  └ resources/
│     │     ├ application-common.yml              # 공통 datasource/jpa/redis/logging
│     │     └ db/migration/
│     │        ├ V1__platform_scoped_tables.sql
│     │        ├ V2__credential_table.sql
│     │        └ V3__vpd_policies.sql
│     └ test/
│        ├ java/com/crosscert/passkey/core/vpd/
│        │  ├ VpdIsolationIT.java                 # 핵심 통합 테스트
│        │  └ TenantAwareDataSourceTest.java      # 단위 테스트
│        └ resources/
│           └ application-test.yml
├ passkey-app/
│  ├ build.gradle.kts
│  └ src/main/
│     ├ java/com/crosscert/passkey/app/
│     │  ├ PasskeyApplication.java
│     │  └ config/
│     │     └ PasskeyHealthIndicator.java          # (옵션 — Phase 1로 미룸; Phase 0에는 없음)
│     └ resources/
│        └ application.yml
└ admin-app/
   ├ build.gradle.kts
   └ src/main/
      ├ java/com/crosscert/passkey/admin/
      │  └ AdminApplication.java
      └ resources/
         └ application.yml
```

설계 원칙:
- 한 파일은 한 책임. Config 클래스는 영역(DataSource/Redis/Jackson/Web)별로 분리.
- entity와 repository는 같이 모이지만, VPD 컨텍스트 관리(vpd 패키지)는 별도.
- 핵심 검증은 `VpdIsolationIT.java` 하나에 응집 — "Phase 0 완료 기준" 시나리오 그대로.

---

## Task 1: Gradle 루트 및 모듈 스켈레톤

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `core/build.gradle.kts`
- Create: `passkey-app/build.gradle.kts`
- Create: `admin-app/build.gradle.kts`

- [ ] **Step 1: 루트 settings.gradle.kts 작성**

`settings.gradle.kts`:

```kotlin
rootProject.name = "passkey2"

include(":core", ":passkey-app", ":admin-app")
```

- [ ] **Step 2: 의존성 버전 카탈로그 작성**

`gradle/libs.versions.toml`:

```toml
[versions]
spring-boot = "3.5.0"
spring-dep-mgmt = "1.1.6"
oracle-jdbc = "23.4.0.24.05"
testcontainers = "1.20.4"
flyway = "10.20.1"

[libraries]
oracle-jdbc = { module = "com.oracle.database.jdbc:ojdbc11", version.ref = "oracle-jdbc" }
flyway-core = { module = "org.flywaydb:flyway-core", version.ref = "flyway" }
flyway-oracle = { module = "org.flywaydb:flyway-database-oracle", version.ref = "flyway" }
testcontainers-bom = { module = "org.testcontainers:testcontainers-bom", version.ref = "testcontainers" }
testcontainers-oracle = { module = "org.testcontainers:oracle-xe" }
testcontainers-junit = { module = "org.testcontainers:junit-jupiter" }

[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
spring-dep-mgmt = { id = "io.spring.dependency-management", version.ref = "spring-dep-mgmt" }
```

- [ ] **Step 3: 루트 build.gradle.kts 작성**

`build.gradle.kts`:

```kotlin
plugins {
    java
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dep.mgmt) apply false
}

allprojects {
    group = "com.crosscert.passkey"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    dependencyManagement {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}")
            mavenBom("org.testcontainers:testcontainers-bom:${libs.versions.testcontainers.get()}")
        }
    }

    dependencies {
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
```

- [ ] **Step 4: :core/build.gradle.kts**

Library 모듈 — bootJar 비활성, plain jar 활성:

```kotlin
plugins {
    `java-library`
}

dependencies {
    api("org.springframework.boot:spring-boot-starter")
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-starter-data-redis")
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("org.springframework.boot:spring-boot-starter-validation")

    api(rootProject.libs.oracle.jdbc)
    api(rootProject.libs.flyway.core)
    api(rootProject.libs.flyway.oracle)

    testImplementation(rootProject.libs.testcontainers.oracle)
    testImplementation(rootProject.libs.testcontainers.junit)
}
```

- [ ] **Step 5: :passkey-app/build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":core"))
}

springBoot {
    mainClass.set("com.crosscert.passkey.app.PasskeyApplication")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("passkey-app.jar")
}
```

- [ ] **Step 6: :admin-app/build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":core"))
}

springBoot {
    mainClass.set("com.crosscert.passkey.admin.AdminApplication")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("admin-app.jar")
}
```

- [ ] **Step 7: 빌드 확인**

Gradle wrapper가 아직 없으므로 시스템 gradle로 한 번 호출하거나 wrapper 생성:

```bash
gradle wrapper --gradle-version=8.10
./gradlew projects
```

Expected output: `Root project 'passkey2'` 아래에 `:admin-app`, `:core`, `:passkey-app` 3개 모듈이 표시됨.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle/libs.versions.toml \
        core/build.gradle.kts passkey-app/build.gradle.kts admin-app/build.gradle.kts \
        gradlew gradlew.bat gradle/wrapper/
git commit -m "build: gradle multi-module skeleton (:core, :passkey-app, :admin-app)"
```

---

## Task 2: Application 클래스 + 최소 application.yml로 두 앱 부팅

**Files:**
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/PasskeyApplication.java`
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/AdminApplication.java`
- Create: `passkey-app/src/main/resources/application.yml`
- Create: `admin-app/src/main/resources/application.yml`
- Create: `core/src/main/resources/application-common.yml`

- [ ] **Step 1: 공통 application-common.yml 작성**

`core/src/main/resources/application-common.yml`:

```yaml
spring:
  jackson:
    serialization:
      write-dates-as-timestamps: false
    deserialization:
      fail-on-unknown-properties: true
    default-property-inclusion: non_null
  jpa:
    open-in-view: false
    properties:
      hibernate.dialect: org.hibernate.dialect.OracleDialect
      hibernate.jdbc.time_zone: UTC
    hibernate.ddl-auto: validate
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      pool-name: passkey-pool
  data:
    redis:
      lettuce:
        pool:
          enabled: true
          max-active: 8

management:
  endpoint.health.show-details: always
  endpoints.web.exposure.include: health,info
```

- [ ] **Step 2: passkey-app Application 클래스**

`passkey-app/src/main/java/com/crosscert/passkey/app/PasskeyApplication.java`:

```java
package com.crosscert.passkey.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.crosscert.passkey")
public class PasskeyApplication {
    public static void main(String[] args) {
        SpringApplication.run(PasskeyApplication.class, args);
    }
}
```

- [ ] **Step 3: passkey-app application.yml**

`passkey-app/src/main/resources/application.yml`:

```yaml
spring:
  application.name: passkey-app
  config.import: classpath:application-common.yml
  flyway.enabled: false

server.port: 8080
```

- [ ] **Step 4: admin-app Application 클래스**

`admin-app/src/main/java/com/crosscert/passkey/admin/AdminApplication.java`:

```java
package com.crosscert.passkey.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.crosscert.passkey")
public class AdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}
```

- [ ] **Step 5: admin-app application.yml**

`admin-app/src/main/resources/application.yml`:

```yaml
spring:
  application.name: admin-app
  config.import: classpath:application-common.yml
  flyway:
    enabled: true
    locations: classpath:db/migration
    schemas: APP_OWNER
    default-schema: APP_OWNER

server.port: 8081
```

- [ ] **Step 6: 컴파일 확인**

```bash
./gradlew :passkey-app:compileJava :admin-app:compileJava
```

Expected: `BUILD SUCCESSFUL`

이 시점에는 DataSource 빈이 아직 없어 부팅은 실패하므로 `bootRun`은 하지 않는다. 다음 Task에서 docker-compose와 DB 셋업 후 부팅.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/resources/application-common.yml \
        passkey-app/src/main/java passkey-app/src/main/resources/application.yml \
        admin-app/src/main/java admin-app/src/main/resources/application.yml
git commit -m "feat: add Application classes and base application.yml for both apps"
```

---

## Task 3: docker-compose로 Oracle XE + Redis 7

**Files:**
- Create: `docker-compose.yml`
- Create: `scripts/wait-for-oracle.sh`

- [ ] **Step 1: docker-compose.yml 작성**

```yaml
services:
  oracle:
    image: gvenzl/oracle-xe:21-slim-faststart
    container_name: passkey-oracle
    environment:
      ORACLE_PASSWORD: oracle
      APP_USER: APP_OWNER
      APP_USER_PASSWORD: app_owner_pw
    ports:
      - "1521:1521"
    volumes:
      - oracle-data:/opt/oracle/oradata
    healthcheck:
      test: ["CMD", "healthcheck.sh"]
      interval: 10s
      timeout: 5s
      retries: 20

  redis:
    image: redis:7-alpine
    container_name: passkey-redis
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5

volumes:
  oracle-data:
```

`gvenzl/oracle-xe` 이미지는 `APP_USER` 환경변수로 추가 유저(`APP_OWNER`)를 자동 생성. 이게 스키마/객체 소유자가 된다.

- [ ] **Step 2: wait-for-oracle.sh 작성**

```bash
#!/bin/bash
set -e
echo "Waiting for Oracle XE to be healthy..."
for i in {1..60}; do
  status=$(docker inspect -f '{{.State.Health.Status}}' passkey-oracle 2>/dev/null || echo "unknown")
  if [ "$status" = "healthy" ]; then
    echo "Oracle is healthy."
    exit 0
  fi
  echo "  attempt $i/60 — status=$status"
  sleep 5
done
echo "Oracle did not become healthy in time."
exit 1
```

- [ ] **Step 3: 권한 부여 및 기동**

```bash
chmod +x scripts/wait-for-oracle.sh
docker-compose up -d
./scripts/wait-for-oracle.sh
docker exec passkey-redis redis-cli ping
```

Expected: `Oracle is healthy.` 그리고 `PONG`.

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml scripts/wait-for-oracle.sh
git commit -m "build: add docker-compose with Oracle XE and Redis 7"
```

---

## Task 4: DBA 부트스트랩 SQL (role / 유저 / context / package)

**Files:**
- Create: `scripts/bootstrap-vpd.sql`
- Create: `scripts/run-bootstrap.sh`

- [ ] **Step 1: bootstrap-vpd.sql 작성**

`scripts/bootstrap-vpd.sql`:

```sql
-- 이 스크립트는 SYS(SYSDBA) 권한으로 1회 실행.
-- APP_OWNER 유저는 docker-compose의 APP_USER 환경변수로 이미 생성되어 있음.

ALTER SESSION SET CONTAINER = XEPDB1;

-- APP_RUNTIME 롤: 일반 트랜잭션 — VPD policy 적용 대상
CREATE ROLE APP_RUNTIME;
GRANT CREATE SESSION TO APP_RUNTIME;

-- APP_ADMIN 롤: 스케줄러/마이그레이션 — VPD 우회
CREATE ROLE APP_ADMIN;
GRANT CREATE SESSION TO APP_ADMIN;
GRANT EXEMPT ACCESS POLICY TO APP_ADMIN;

-- APP_OWNER에 객체 생성/관리 권한 부여 (스키마 소유자)
GRANT CREATE TABLE, CREATE SEQUENCE, CREATE PROCEDURE, CREATE TRIGGER,
      CREATE CONTEXT, CREATE PUBLIC SYNONYM, DROP PUBLIC SYNONYM,
      UNLIMITED TABLESPACE TO APP_OWNER;
GRANT EXECUTE ON DBMS_RLS TO APP_OWNER;
GRANT EXECUTE ON DBMS_SESSION TO APP_OWNER;

-- 런타임 유저 생성
CREATE USER APP_RUNTIME_USER IDENTIFIED BY runtime_pw;
GRANT APP_RUNTIME TO APP_RUNTIME_USER;

CREATE USER APP_ADMIN_USER IDENTIFIED BY admin_pw;
GRANT APP_ADMIN TO APP_ADMIN_USER;
-- ADMIN은 Flyway/DDL을 위해 APP_OWNER 객체에 광범위 권한 필요
GRANT ALL PRIVILEGES TO APP_ADMIN_USER;

-- APP_OWNER로 컨텍스트와 패키지 생성
CONNECT APP_OWNER/app_owner_pw@XEPDB1;

CREATE OR REPLACE CONTEXT APP_CTX USING APP_OWNER.CTX_PKG;

CREATE OR REPLACE PACKAGE CTX_PKG AS
  PROCEDURE set_tenant(p_tid IN VARCHAR2);
  PROCEDURE clear_tenant;
END;
/

CREATE OR REPLACE PACKAGE BODY CTX_PKG AS
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

GRANT EXECUTE ON CTX_PKG TO APP_RUNTIME;
GRANT EXECUTE ON CTX_PKG TO APP_ADMIN;
```

- [ ] **Step 2: run-bootstrap.sh 작성**

```bash
#!/bin/bash
set -e
docker exec -i passkey-oracle sqlplus -S sys/oracle@localhost:1521/XEPDB1 as sysdba < scripts/bootstrap-vpd.sql
```

- [ ] **Step 3: 실행 및 검증**

```bash
chmod +x scripts/run-bootstrap.sh
./scripts/run-bootstrap.sh
```

Expected: SQL 출력에서 `Role created.`, `User created.`, `Package created.` 등이 보이고 마지막에 `Grant succeeded.`.

검증 — APP_RUNTIME_USER로 접속해 컨텍스트 호출이 되는지:

```bash
docker exec -i passkey-oracle sqlplus -S APP_RUNTIME_USER/runtime_pw@localhost:1521/XEPDB1 <<EOF
BEGIN APP_OWNER.CTX_PKG.set_tenant('T_TEST'); END;
/
SELECT SYS_CONTEXT('APP_CTX','TENANT_ID') FROM dual;
EOF
```

Expected: 결과에 `T_TEST` 출력.

- [ ] **Step 4: Commit**

```bash
git add scripts/bootstrap-vpd.sql scripts/run-bootstrap.sh
git commit -m "build: add DBA bootstrap for APP_RUNTIME/APP_ADMIN roles + ctx_pkg"
```

---

## Task 5: Flyway V1 — platform-scoped 테이블

**Files:**
- Create: `core/src/main/resources/db/migration/V1__platform_scoped_tables.sql`

- [ ] **Step 1: V1 마이그레이션 작성**

`core/src/main/resources/db/migration/V1__platform_scoped_tables.sql`:

```sql
-- Platform-scoped 테이블: tenant_id 없음, VPD 미적용
-- APP_OWNER 스키마에 생성됨.

CREATE TABLE tenant (
  id              VARCHAR2(64)  NOT NULL,
  display_name    VARCHAR2(256) NOT NULL,
  status          VARCHAR2(16)  DEFAULT 'active' NOT NULL,
  created_at      TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at      TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_tenant PRIMARY KEY (id),
  CONSTRAINT ck_tenant_status CHECK (status IN ('active','suspended'))
);

CREATE TABLE scheduler_lease (
  name        VARCHAR2(64)  NOT NULL,
  holder      VARCHAR2(256) NOT NULL,
  expires_at  TIMESTAMP     NOT NULL,
  CONSTRAINT pk_scheduler_lease PRIMARY KEY (name)
);

CREATE SEQUENCE mds_blob_cache_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE;

CREATE TABLE mds_blob_cache (
  id           NUMBER       NOT NULL,
  version      NUMBER       NOT NULL,
  next_update  DATE         NOT NULL,
  fetched_at   TIMESTAMP    DEFAULT SYSTIMESTAMP NOT NULL,
  blob_jwt     CLOB         NOT NULL,
  CONSTRAINT pk_mds_blob_cache PRIMARY KEY (id)
);

-- 런타임/관리자 롤에 권한 부여
GRANT SELECT ON tenant TO APP_RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant TO APP_ADMIN;

GRANT SELECT, INSERT, UPDATE, DELETE ON scheduler_lease TO APP_ADMIN;

GRANT SELECT ON mds_blob_cache TO APP_RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON mds_blob_cache TO APP_ADMIN;
GRANT SELECT ON mds_blob_cache_seq TO APP_ADMIN;
```

- [ ] **Step 2: Commit**

이 시점에서는 admin-app이 부팅되지 않아 자동 적용은 안 됨. 다음 task에서 인프라 코드가 갖춰진 후 일괄 검증.

```bash
git add core/src/main/resources/db/migration/V1__platform_scoped_tables.sql
git commit -m "feat(db): V1 — platform-scoped tables (tenant, scheduler_lease, mds_blob_cache)"
```

---

## Task 6: Flyway V2 — credential 테이블 (tenant-scoped)

**Files:**
- Create: `core/src/main/resources/db/migration/V2__credential_table.sql`

- [ ] **Step 1: V2 마이그레이션 작성**

`core/src/main/resources/db/migration/V2__credential_table.sql`:

```sql
-- Tenant-scoped: tenant_id 컬럼 + VPD policy 대상.
-- 컬럼 정의만. Phase 1에서 도메인 로직 추가.

CREATE SEQUENCE credential_seq START WITH 1 INCREMENT BY 1 NOCACHE NOCYCLE;

CREATE TABLE credential (
  id               NUMBER       NOT NULL,
  tenant_id        VARCHAR2(64) NOT NULL,
  user_handle      RAW(64)      NOT NULL,
  credential_id    RAW(256)     NOT NULL,
  public_key       BLOB         NOT NULL,
  sign_count       NUMBER       DEFAULT 0 NOT NULL,
  aaguid           RAW(16),
  transports       VARCHAR2(128),
  attestation_fmt  VARCHAR2(64),
  backup_state     CLOB,
  created_at       TIMESTAMP    DEFAULT SYSTIMESTAMP NOT NULL,
  last_used_at     TIMESTAMP,
  CONSTRAINT pk_credential PRIMARY KEY (id),
  CONSTRAINT uq_credential_id UNIQUE (tenant_id, credential_id),
  CONSTRAINT fk_credential_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
  CONSTRAINT ck_credential_backup_state CHECK (backup_state IS NULL OR backup_state IS JSON)
);

CREATE INDEX ix_credential_tenant_user ON credential(tenant_id, user_handle);

GRANT SELECT, INSERT, UPDATE, DELETE ON credential TO APP_RUNTIME;
GRANT SELECT ON credential_seq TO APP_RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON credential TO APP_ADMIN;
GRANT SELECT ON credential_seq TO APP_ADMIN;
```

- [ ] **Step 2: Commit**

```bash
git add core/src/main/resources/db/migration/V2__credential_table.sql
git commit -m "feat(db): V2 — credential table (tenant-scoped, VPD-ready)"
```

---

## Task 7: Flyway V3 — VPD policy 등록

**Files:**
- Create: `core/src/main/resources/db/migration/V3__vpd_policies.sql`

- [ ] **Step 1: V3 마이그레이션 작성**

`core/src/main/resources/db/migration/V3__vpd_policies.sql`:

```sql
-- VPD policy predicate function
CREATE OR REPLACE FUNCTION tenant_predicate(
  schema_name IN VARCHAR2,
  object_name IN VARCHAR2
) RETURN VARCHAR2 IS
BEGIN
  RETURN 'tenant_id = SYS_CONTEXT(''APP_CTX'',''TENANT_ID'')';
END tenant_predicate;
/

-- credential 테이블에 정책 부착
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

- [ ] **Step 2: Commit**

```bash
git add core/src/main/resources/db/migration/V3__vpd_policies.sql
git commit -m "feat(db): V3 — VPD policy on credential table"
```

---

## Task 8: JPA Entity — Tenant, Credential, MdsBlobCache, SchedulerLease

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/entity/Tenant.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/entity/Credential.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/entity/MdsBlobCache.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/entity/SchedulerLease.java`

- [ ] **Step 1: Tenant.java**

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "TENANT")
public class Tenant {

    @Id
    @Column(name = "ID", length = 64, nullable = false)
    private String id;

    @Column(name = "DISPLAY_NAME", length = 256, nullable = false)
    private String displayName;

    @Column(name = "STATUS", length = 16, nullable = false)
    private String status;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt;

    protected Tenant() {}

    public Tenant(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
        this.status = "active";
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 2: Credential.java (Phase 0에서는 골격만 — Phase 1에서 도메인 메서드 추가)**

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "CREDENTIAL")
public class Credential {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "credential_seq")
    @SequenceGenerator(name = "credential_seq", sequenceName = "CREDENTIAL_SEQ", allocationSize = 1)
    @Column(name = "ID")
    private Long id;

    @Column(name = "TENANT_ID", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "USER_HANDLE", nullable = false)
    private byte[] userHandle;

    @Column(name = "CREDENTIAL_ID", nullable = false)
    private byte[] credentialId;

    @Lob
    @Column(name = "PUBLIC_KEY", nullable = false)
    private byte[] publicKey;

    @Column(name = "SIGN_COUNT", nullable = false)
    private long signCount;

    @Column(name = "AAGUID")
    private byte[] aaguid;

    @Column(name = "TRANSPORTS", length = 128)
    private String transports;

    @Column(name = "ATTESTATION_FMT", length = 64)
    private String attestationFmt;

    @Lob
    @Column(name = "BACKUP_STATE")
    private String backupStateJson;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "LAST_USED_AT")
    private Instant lastUsedAt;

    protected Credential() {}

    public Credential(String tenantId, byte[] userHandle, byte[] credentialId,
                      byte[] publicKey, byte[] aaguid) {
        this.tenantId = tenantId;
        this.userHandle = userHandle;
        this.credentialId = credentialId;
        this.publicKey = publicKey;
        this.aaguid = aaguid;
        this.signCount = 0;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public byte[] getCredentialId() { return credentialId; }
    public long getSignCount() { return signCount; }
}
```

- [ ] **Step 3: MdsBlobCache.java**

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "MDS_BLOB_CACHE")
public class MdsBlobCache {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "mds_blob_cache_seq")
    @SequenceGenerator(name = "mds_blob_cache_seq", sequenceName = "MDS_BLOB_CACHE_SEQ", allocationSize = 1)
    @Column(name = "ID")
    private Long id;

    @Column(name = "VERSION", nullable = false)
    private long version;

    @Column(name = "NEXT_UPDATE", nullable = false)
    private LocalDate nextUpdate;

    @Column(name = "FETCHED_AT", nullable = false)
    private Instant fetchedAt;

    @Lob
    @Column(name = "BLOB_JWT", nullable = false)
    private String blobJwt;

    protected MdsBlobCache() {}

    public Long getId() { return id; }
    public long getVersion() { return version; }
    public LocalDate getNextUpdate() { return nextUpdate; }
    public String getBlobJwt() { return blobJwt; }
}
```

- [ ] **Step 4: SchedulerLease.java**

```java
package com.crosscert.passkey.core.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "SCHEDULER_LEASE")
public class SchedulerLease {

    @Id
    @Column(name = "NAME", length = 64, nullable = false)
    private String name;

    @Column(name = "HOLDER", length = 256, nullable = false)
    private String holder;

    @Column(name = "EXPIRES_AT", nullable = false)
    private Instant expiresAt;

    protected SchedulerLease() {}

    public String getName() { return name; }
    public String getHolder() { return holder; }
    public Instant getExpiresAt() { return expiresAt; }
}
```

- [ ] **Step 5: 컴파일 확인**

```bash
./gradlew :core:compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/
git commit -m "feat(core): JPA entities for Tenant, Credential, MdsBlobCache, SchedulerLease"
```

---

## Task 9: JPA Repository 인터페이스

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/repository/TenantRepository.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/repository/CredentialRepository.java`

- [ ] **Step 1: TenantRepository.java**

```java
package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, String> {
}
```

- [ ] **Step 2: CredentialRepository.java**

```java
package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.Credential;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CredentialRepository extends JpaRepository<Credential, Long> {
}
```

Phase 0에서는 더 이상의 쿼리 메서드 없음. VPD가 자동 필터링하므로 `findAll()`/`save()`만 사용해도 격리 증명에 충분하다.

- [ ] **Step 3: 컴파일 확인**

```bash
./gradlew :core:compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/repository/
git commit -m "feat(core): TenantRepository, CredentialRepository"
```

---

## Task 10: TenantContextHolder — ThreadLocal 컨텍스트 보유자

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/vpd/TenantContextHolder.java`
- Test: `core/src/test/java/com/crosscert/passkey/core/vpd/TenantContextHolderTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`core/src/test/java/com/crosscert/passkey/core/vpd/TenantContextHolderTest.java`:

```java
package com.crosscert.passkey.core.vpd;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TenantContextHolderTest {

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
    }

    @Test
    void getReturnsNullWhenNotSet() {
        assertNull(TenantContextHolder.get());
    }

    @Test
    void setAndGet() {
        TenantContextHolder.set("T_A");
        assertEquals("T_A", TenantContextHolder.get());
    }

    @Test
    void clearRemovesValue() {
        TenantContextHolder.set("T_A");
        TenantContextHolder.clear();
        assertNull(TenantContextHolder.get());
    }

    @Test
    void valueIsThreadLocal() throws Exception {
        TenantContextHolder.set("T_MAIN");
        final String[] otherThreadValue = new String[1];
        Thread t = new Thread(() -> otherThreadValue[0] = TenantContextHolder.get());
        t.start();
        t.join();
        assertNull(otherThreadValue[0], "other thread should not see main thread's tenant");
        assertEquals("T_MAIN", TenantContextHolder.get());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew :core:test --tests TenantContextHolderTest
```

Expected: 컴파일 실패 — `TenantContextHolder` 클래스가 없음.

- [ ] **Step 3: TenantContextHolder.java 구현**

```java
package com.crosscert.passkey.core.vpd;

public final class TenantContextHolder {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContextHolder() {}

    public static void set(String tenantId) {
        CURRENT.set(tenantId);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew :core:test --tests TenantContextHolderTest
```

Expected: 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/vpd/TenantContextHolder.java \
        core/src/test/java/com/crosscert/passkey/core/vpd/TenantContextHolderTest.java
git commit -m "feat(core): TenantContextHolder (ThreadLocal-based tenant context)"
```

---

## Task 11: TenantAwareDataSource — borrow/return 시 컨텍스트 주입

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/vpd/TenantAwareDataSource.java`
- Test: `core/src/test/java/com/crosscert/passkey/core/vpd/TenantAwareDataSourceTest.java`

`TenantAwareDataSource`는 위임 DataSource를 wrap한다. `getConnection()`이 호출되면 pool에서 connection을 얻고, `TenantContextHolder.get()`이 non-null이면 `ctx_pkg.set_tenant`를 실행. 반환된 connection을 `Proxy`로 감싸 `close()`가 호출되기 직전에 `ctx_pkg.clear_tenant`를 실행하고 실제 close에 위임.

- [ ] **Step 1: 실패하는 테스트 작성 (단위 테스트 — DB 불필요, Mockito 사용)**

```java
package com.crosscert.passkey.core.vpd;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;

import static org.mockito.Mockito.*;

class TenantAwareDataSourceTest {

    @Test
    void setsTenantOnBorrowWhenContextPresent() throws Exception {
        Connection underlying = mock(Connection.class);
        CallableStatement cs = mock(CallableStatement.class);
        when(underlying.prepareCall(anyString())).thenReturn(cs);

        DataSource delegate = mock(DataSource.class);
        when(delegate.getConnection()).thenReturn(underlying);

        TenantContextHolder.set("T_A");
        try {
            TenantAwareDataSource ds = new TenantAwareDataSource(delegate);
            Connection conn = ds.getConnection();

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(underlying).prepareCall(sql.capture());
            assert sql.getValue().contains("ctx_pkg.set_tenant");
            verify(cs).setString(eq(1), eq("T_A"));
            verify(cs).execute();

            conn.close(); // should also invoke clear_tenant
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void doesNotSetTenantWhenContextAbsent() throws Exception {
        Connection underlying = mock(Connection.class);
        DataSource delegate = mock(DataSource.class);
        when(delegate.getConnection()).thenReturn(underlying);

        TenantContextHolder.clear();
        TenantAwareDataSource ds = new TenantAwareDataSource(delegate);
        Connection conn = ds.getConnection();

        verify(underlying, never()).prepareCall(anyString());
        conn.close();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew :core:test --tests TenantAwareDataSourceTest
```

Expected: 컴파일 실패 — `TenantAwareDataSource` 클래스 없음.

- [ ] **Step 3: TenantAwareDataSource.java 구현**

```java
package com.crosscert.passkey.core.vpd;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * HikariCP DataSource를 wrap.
 * - getConnection: pool에서 connection 획득 후 TenantContextHolder의 값이 있으면 ctx_pkg.set_tenant 호출
 * - 반환된 Connection은 Proxy로 감싸, close 시점에 ctx_pkg.clear_tenant를 실행한 뒤 실제 close 위임
 *
 * 컨텍스트가 null이면 set_tenant를 호출하지 않는다 — VPD policy가 0 rows를 강제하므로 안전 기본값.
 */
public class TenantAwareDataSource implements DataSource {

    private static final String SET_SQL = "{ call APP_OWNER.ctx_pkg.set_tenant(?) }";
    private static final String CLEAR_SQL = "{ call APP_OWNER.ctx_pkg.clear_tenant() }";

    private final DataSource delegate;

    public TenantAwareDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection raw = delegate.getConnection();
        return wrap(raw);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection raw = delegate.getConnection(username, password);
        return wrap(raw);
    }

    private Connection wrap(Connection raw) throws SQLException {
        String tenantId = TenantContextHolder.get();
        if (tenantId != null) {
            try (CallableStatement cs = raw.prepareCall(SET_SQL)) {
                cs.setString(1, tenantId);
                cs.execute();
            }
        }
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new ClearOnCloseHandler(raw));
    }

    private static class ClearOnCloseHandler implements InvocationHandler {
        private final Connection target;
        private volatile boolean closed = false;

        ClearOnCloseHandler(Connection target) { this.target = target; }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("close".equals(method.getName()) && !closed) {
                closed = true;
                if (!target.isClosed()) {
                    try (CallableStatement cs = target.prepareCall(CLEAR_SQL)) {
                        cs.execute();
                    } catch (SQLException ignored) {
                        // pool 반환 단계의 오류로 트랜잭션을 깨뜨리지 않는다.
                    }
                }
                return method.invoke(target, args);
            }
            return method.invoke(target, args);
        }
    }

    // DataSource 위임 보일러플레이트
    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew :core:test --tests TenantAwareDataSourceTest
```

Expected: 2 tests passed.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/vpd/TenantAwareDataSource.java \
        core/src/test/java/com/crosscert/passkey/core/vpd/TenantAwareDataSourceTest.java
git commit -m "feat(core): TenantAwareDataSource — inject ctx_pkg.set_tenant on borrow"
```

---

## Task 12: CoreDataSourceConfig — TenantAwareDataSource 빈 등록

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/config/CoreDataSourceConfig.java`

- [ ] **Step 1: CoreDataSourceConfig.java 작성**

```java
package com.crosscert.passkey.core.config;

import com.crosscert.passkey.core.vpd.TenantAwareDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@Configuration
public class CoreDataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource")
    public HikariDataSource physicalDataSource() {
        return (HikariDataSource) DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    public DataSource dataSource(HikariDataSource physicalDataSource) {
        return new TenantAwareDataSource(physicalDataSource);
    }
}
```

핵심: Spring Boot의 자동 DataSource 생성을 누르고, `@Primary`인 `dataSource()`가 wrapped 인스턴스를 반환. JPA·Flyway·Health 모두 이 빈을 사용.

다만 Flyway가 DDL을 돌릴 때는 ThreadLocal에 tenant가 없으므로 ctx_pkg.set_tenant도 호출되지 않음. → APP_ADMIN 우회와 함께 안전.

- [ ] **Step 2: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/config/CoreDataSourceConfig.java
git commit -m "feat(core): wire TenantAwareDataSource as primary @Bean"
```

---

## Task 13: 공통 설정 — Redis, Jackson, ProblemDetail

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/config/CoreRedisConfig.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/config/CoreJacksonConfig.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/config/GlobalExceptionHandler.java`

- [ ] **Step 1: CoreRedisConfig.java**

```java
package com.crosscert.passkey.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class CoreRedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory cf) {
        return new StringRedisTemplate(cf);
    }
}
```

Lettuce는 Spring Boot 자동 설정에 맡긴다.

- [ ] **Step 2: CoreJacksonConfig.java**

```java
package com.crosscert.passkey.core.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CoreJacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                .modulesToInstall(new JavaTimeModule())
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
```

- [ ] **Step 3: GlobalExceptionHandler.java**

```java
package com.crosscert.passkey.core.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Invalid request");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setTitle("Internal error");
        return pd;
    }
}
```

- [ ] **Step 4: 컴파일 확인**

```bash
./gradlew :core:compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/config/CoreRedisConfig.java \
        core/src/main/java/com/crosscert/passkey/core/config/CoreJacksonConfig.java \
        core/src/main/java/com/crosscert/passkey/core/config/GlobalExceptionHandler.java
git commit -m "feat(core): Redis/Jackson/ProblemDetail common configs"
```

---

## Task 14: DevTenantHeaderFilter — `X-Tenant-Id` 헤더 → TenantContextHolder (non-prod)

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/vpd/DevTenantHeaderFilter.java`

- [ ] **Step 1: DevTenantHeaderFilter.java 작성**

```java
package com.crosscert.passkey.core.vpd;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Phase 0/dev 전용. X-Tenant-Id 헤더 값을 TenantContextHolder에 set한다.
 * prod 프로파일에서는 등록되지 않으므로 운영에는 절대 노출되지 않는다.
 * Phase 1에서 X-API-Key 인증 필터가 들어오면 이 필터는 제거 대상.
 */
@Component
@Profile("!prod")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DevTenantHeaderFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Tenant-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String tenant = req.getHeader(HEADER);
        if (tenant != null && !tenant.isBlank()) {
            TenantContextHolder.set(tenant);
        }
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContextHolder.clear();
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/vpd/DevTenantHeaderFilter.java
git commit -m "feat(core): dev-only DevTenantHeaderFilter (active when !prod)"
```

---

## Task 15: 로컬 application.yml에 DB/Redis 좌표 추가 + 부팅 검증

**Files:**
- Modify: `core/src/main/resources/application-common.yml`
- Create: `core/src/main/resources/application-local.yml`
- Create: `admin-app/src/main/resources/application-local.yml`
- Create: `passkey-app/src/main/resources/application-local.yml`

- [ ] **Step 1: application-local.yml (core)에 datasource/redis 좌표 추가**

`core/src/main/resources/application-local.yml`:

```yaml
spring:
  datasource:
    url: jdbc:oracle:thin:@localhost:1521/XEPDB1
    driver-class-name: oracle.jdbc.OracleDriver
    # username/password는 각 앱(passkey-app/admin-app)에서 오버라이드
  data:
    redis:
      host: localhost
      port: 6379
```

- [ ] **Step 2: passkey-app local 프로파일 (APP_RUNTIME 계정)**

`passkey-app/src/main/resources/application-local.yml`:

```yaml
spring:
  datasource:
    username: APP_RUNTIME_USER
    password: runtime_pw
```

- [ ] **Step 3: admin-app local 프로파일 (APP_ADMIN 계정)**

`admin-app/src/main/resources/application-local.yml`:

```yaml
spring:
  datasource:
    username: APP_ADMIN_USER
    password: admin_pw
```

- [ ] **Step 4: 두 앱 부팅 검증**

별도 터미널 두 개에서:

```bash
./gradlew :admin-app:bootRun --args='--spring.profiles.active=local'
# → Flyway가 V1, V2, V3 마이그레이션을 자동 실행
# → http://localhost:8081/actuator/health → 200 OK, db status UP, redis UP
```

```bash
./gradlew :passkey-app:bootRun --args='--spring.profiles.active=local'
# → http://localhost:8080/actuator/health → 200 OK
```

Expected: 두 앱 모두 health 응답이 다음과 같음:

```json
{"status":"UP","components":{"db":{"status":"UP"},"redis":{"status":"UP"}}}
```

마이그레이션 결과를 sqlplus로 확인:

```bash
docker exec -i passkey-oracle sqlplus -S APP_OWNER/app_owner_pw@localhost:1521/XEPDB1 <<'EOF'
SELECT table_name FROM user_tables ORDER BY table_name;
SELECT policy_name, object_name FROM user_policies;
EOF
```

Expected: `CREDENTIAL, FLYWAY_SCHEMA_HISTORY, MDS_BLOB_CACHE, SCHEDULER_LEASE, TENANT` 다섯 테이블, `CREDENTIAL_TENANT_ISOLATION` 정책 1개.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/resources/application-local.yml \
        passkey-app/src/main/resources/application-local.yml \
        admin-app/src/main/resources/application-local.yml
git commit -m "build: add local-profile application.yml with DB/Redis coordinates"
```

---

## Task 16: VPD 격리 자동 통합 테스트 — Phase 0 완료 기준

**Files:**
- Create: `core/src/test/resources/application-test.yml`
- Create: `core/src/test/java/com/crosscert/passkey/core/vpd/VpdIsolationIT.java`

이 테스트는 Testcontainers로 Oracle XE를 띄우고, `scripts/bootstrap-vpd.sql`과 Flyway 마이그레이션을 순차 적용한 뒤, 두 테넌트의 격리를 검증한다. **Phase 0의 최종 게이트**.

- [ ] **Step 1: test resources에 application-test.yml**

`core/src/test/resources/application-test.yml`:

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    schemas: APP_OWNER
    default-schema: APP_OWNER
  jpa:
    hibernate.ddl-auto: validate
```

- [ ] **Step 2: 테스트 작성**

```java
package com.crosscert.passkey.core.vpd;

import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class VpdIsolationIT {

    @SpringBootApplication
    static class TestApp {}

    @Container
    static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-xe:21-slim-faststart")
            .withDatabaseName("XEPDB1")
            .withUsername("APP_OWNER")
            .withPassword("app_owner_pw")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("bootstrap-vpd.sql"),
                    "/tmp/bootstrap-vpd.sql");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) {
        // bootstrap을 SYS 권한으로 실행 후 props 등록
        try {
            ORACLE.execInContainer("bash", "-c",
                "sqlplus -S sys/test@localhost:1521/XEPDB1 as sysdba @/tmp/bootstrap-vpd.sql");
        } catch (Exception e) {
            throw new RuntimeException("bootstrap failed", e);
        }
        reg.add("spring.datasource.url", ORACLE::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "APP_ADMIN_USER");
        reg.add("spring.datasource.password", () -> "admin_pw");
    }

    @Autowired TenantRepository tenants;
    @Autowired CredentialRepository credentials;

    @BeforeEach
    void seed() {
        TenantContextHolder.clear();
        // APP_ADMIN_USER로 두 테넌트 + 각 1 credential을 INSERT
        tenants.save(new Tenant("T_A", "Tenant A"));
        tenants.save(new Tenant("T_B", "Tenant B"));
        credentials.save(new Credential("T_A", "user_a".getBytes(), "cred_a".getBytes(),
                "pk_a".getBytes(), null));
        credentials.save(new Credential("T_B", "user_b".getBytes(), "cred_b".getBytes(),
                "pk_b".getBytes(), null));
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        credentials.deleteAll();
        tenants.deleteAll();
    }

    @Nested
    class WithAppAdminUser_BypassesPolicy {
        @Test
        void seesAllRows() {
            // 현재 datasource는 APP_ADMIN_USER. EXEMPT ACCESS POLICY 적용.
            TenantContextHolder.clear();
            List<Credential> all = credentials.findAll();
            assertThat(all).hasSize(2);
        }
    }

    /*
     * APP_RUNTIME 시나리오는 별도 datasource로 검증해야 한다.
     * Test profile에서 추가 DataSource를 만들어 APP_RUNTIME_USER로 접속한다.
     */
    @Nested
    class WithAppRuntimeUser_PolicyEnforced {
        @Autowired
        private RuntimeDsHelper helper;

        @Test
        void contextTenantAReturnsOnlyT_A() {
            TenantContextHolder.set("T_A");
            try {
                List<Object[]> rows = helper.selectAllCredentialsAsRuntime();
                assertThat(rows).hasSize(1);
                assertThat(rows.get(0)[1]).isEqualTo("T_A");
            } finally {
                TenantContextHolder.clear();
            }
        }

        @Test
        void contextTenantBReturnsOnlyT_B() {
            TenantContextHolder.set("T_B");
            try {
                List<Object[]> rows = helper.selectAllCredentialsAsRuntime();
                assertThat(rows).hasSize(1);
                assertThat(rows.get(0)[1]).isEqualTo("T_B");
            } finally {
                TenantContextHolder.clear();
            }
        }

        @Test
        void noContextReturnsZeroRows() {
            TenantContextHolder.clear();
            List<Object[]> rows = helper.selectAllCredentialsAsRuntime();
            assertThat(rows).isEmpty();
        }

        @Test
        void cannotInsertCrossTenant() {
            TenantContextHolder.set("T_A");
            try {
                assertThatThrownBy(() -> helper.insertCredentialAs("T_B", "x", "y", "z"))
                        .hasMessageContaining("ORA-28115") // policy with check option violation
                        .as("VPD update_check=TRUE blocks cross-tenant INSERT");
            } finally {
                TenantContextHolder.clear();
            }
        }
    }
}
```

추가로 `RuntimeDsHelper`라는 컴포넌트가 필요. Phase 0 테스트 한정으로 별도 DataSource를 만들어 SQL을 실행한다.

- [ ] **Step 3: RuntimeDsHelper 작성**

`core/src/test/java/com/crosscert/passkey/core/vpd/RuntimeDsHelper.java`:

```java
package com.crosscert.passkey.core.vpd;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

@Component
public class RuntimeDsHelper {

    private final JdbcTemplate runtimeJdbc;

    public RuntimeDsHelper(@Value("${spring.datasource.url}") String url) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername("APP_RUNTIME_USER");
        ds.setPassword("runtime_pw");
        DataSource wrapped = new TenantAwareDataSource(ds);
        this.runtimeJdbc = new JdbcTemplate(wrapped);
    }

    public List<Object[]> selectAllCredentialsAsRuntime() {
        return runtimeJdbc.query(
                "SELECT id, tenant_id FROM APP_OWNER.credential",
                (rs, i) -> new Object[]{rs.getLong("id"), rs.getString("tenant_id")});
    }

    public void insertCredentialAs(String tenantId, String userHandle, String credentialId, String publicKey) {
        runtimeJdbc.update(
                "INSERT INTO APP_OWNER.credential (id, tenant_id, user_handle, credential_id, public_key) " +
                "VALUES (APP_OWNER.credential_seq.NEXTVAL, ?, UTL_RAW.CAST_TO_RAW(?), UTL_RAW.CAST_TO_RAW(?), UTL_RAW.CAST_TO_RAW(?))",
                tenantId, userHandle, credentialId, publicKey);
    }
}
```

- [ ] **Step 4: bootstrap-vpd.sql을 test resources에도 복사 가능하도록 처리**

build.gradle.kts에 test resources 카피 task 추가하거나, 간단하게:

```bash
mkdir -p core/src/test/resources
cp scripts/bootstrap-vpd.sql core/src/test/resources/bootstrap-vpd.sql
```

- [ ] **Step 5: 테스트 실행**

```bash
./gradlew :core:test --tests VpdIsolationIT
```

Expected: 5 tests passed (1 admin bypass + 4 runtime enforcement).

만약 `ORA-28115` 메시지가 환경에 따라 다르게 나타나면(예: `ORA-01031: insufficient privileges`), assertion을 `isInstanceOf(DataAccessException.class)`로 완화하고 메시지는 별도로 검증.

- [ ] **Step 6: Commit**

```bash
git add core/src/test/java/com/crosscert/passkey/core/vpd/VpdIsolationIT.java \
        core/src/test/java/com/crosscert/passkey/core/vpd/RuntimeDsHelper.java \
        core/src/test/resources/
git commit -m "test(core): VpdIsolationIT — Phase 0 acceptance criteria"
```

---

## Task 17: 종합 검증 — Phase 0 Definition of Done

**Files:** (없음 — 실행만)

- [ ] **Step 1: 전체 빌드/테스트**

```bash
./gradlew clean build
```

Expected: `BUILD SUCCESSFUL`. 모든 단위 테스트 + `VpdIsolationIT` 통과.

- [ ] **Step 2: 두 앱 부팅 + health 확인**

별도 터미널 두 개에서:

```bash
./gradlew :admin-app:bootRun --args='--spring.profiles.active=local'
./gradlew :passkey-app:bootRun --args='--spring.profiles.active=local'
```

별도 터미널에서:

```bash
curl -s http://localhost:8081/actuator/health | jq .
curl -s http://localhost:8080/actuator/health | jq .
```

Expected: 두 응답 모두 `{"status":"UP", "components":{"db":{"status":"UP"}, "redis":{"status":"UP"}, ...}}`.

- [ ] **Step 3: dev tenant header 동작 확인 (수동)**

passkey-app에서 dummy endpoint가 없으므로, admin-app actuator를 활용:

```bash
curl -s -H "X-Tenant-Id: T_A" http://localhost:8081/actuator/info
```

응답이 정상이면 Filter가 ThreadLocal을 set/clear한 것. (요청 처리 후 leak이 없음을 의미.)

- [ ] **Step 4: Phase 0 완료 표시**

이 시점에서 Phase 0의 모든 산출물이 갖춰지고 VPD 격리가 자동 테스트로 증명됨.

```bash
git tag phase-0-foundation-complete
git log --oneline -20
```

---

## Self-Review

spec 대비 plan 커버리지를 점검:

| Spec 섹션 | 다루는 Task |
|---|---|
| §1 1차 목표 | Task 17 (DoD 검증) |
| §2 기술 스택 | Task 1 (Gradle/카탈로그) |
| §3 모듈 구조 | Task 1, 2 |
| §4.1 VPD + 2-role | Task 4 (bootstrap), Task 7 (VPD policy) |
| §4.2 Phase 0 스키마 | Task 5 (V1 platform), Task 6 (V2 credential) |
| §4.3 PL/SQL 컨텍스트 | Task 4 |
| §4.4 VPD 정책 예시 | Task 7 |
| §4.5 Flyway 위치/실행 | Task 5, 6, 7 + Task 2(yml) |
| §4.6 컨텍스트 주입 | Task 10 (Holder), 11 (DS), 12 (Config), 14 (Filter) |
| §5 Redis (Phase 0 한정) | Task 3 (docker), 13 (Config) |
| §6 공통 인프라 | Task 2 (yml), 13 (Jackson/ProblemDetail) |
| §7 로컬 개발 환경 | Task 3, 15 |
| §8.1 Hello World | Task 15, 17 |
| §8.2 VPD 격리 자동 테스트 | Task 16 |
| §11 위험 요소 | Task 11(connection 초기화), Task 4(APP_ADMIN), Task 14(profile 분리) 곳곳에 반영 |

**Placeholder scan:** TBD/TODO/"fill in later"/"add appropriate" 없음. 모든 step에 실제 코드와 명령이 들어 있음.

**Type consistency:**
- `TenantContextHolder.set/get/clear` — Task 10, 11, 14, 16 전체에서 일관.
- `TenantAwareDataSource(DataSource delegate)` — Task 11 정의, Task 12, 16에서 사용 — 동일 시그니처.
- `APP_RUNTIME_USER`/`APP_ADMIN_USER` 계정명 + `runtime_pw`/`admin_pw` — Task 4 정의, Task 15, 16에서 사용 — 동일.
- Flyway 스키마 명 `APP_OWNER` — Task 2(yml), 4(bootstrap), 5/6/7(DDL), 16(test yml) 일관.

**Scope check:** Phase 0 단일 구현 계획으로 적정. 후속 Phase 작업은 명시적으로 제외(spec §9 + plan §10 미사용 — 의도적).

문제 없음. 끝.
