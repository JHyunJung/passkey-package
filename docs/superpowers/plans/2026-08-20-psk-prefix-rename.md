# DB 계정·롤 `PSK_` 접두사 전면 반영 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** DB 계정·롤 5개에 `PSK_` 접두사를 붙인 QA DB 에 맞춰 코드베이스 134개 파일을 일괄 정정하고, QA 반입 번들을 재생성한다.

**Architecture:** 기계적 치환(Task 2·6·7)과 설계 변경(Task 3~5)을 분리한다. 스키마명은 하드코딩을 걷어내고 `passkey.db.schema` 프로퍼티로 외부화해 다음 rename 때 코드 수정이 없게 한다. Flyway 는 아직 실행된 적 없으므로 V1/V4 를 직접 수정한다.

**Tech Stack:** Java 17, Spring Boot 3.5, Flyway 11, Oracle, Testcontainers, Gradle, Docker(buildx)

**Spec:** `docs/superpowers/specs/2026-08-20-psk-prefix-rename-design.md`

## Global Constraints

- **작업 위치**: worktree `.claude/worktrees/psk-prefix-rename`, 브랜치 `psk-prefix-rename`. 모든 명령은 이 디렉터리에서 실행한다. 메인 저장소(`/Users/jhyun/Git/10-work/crosscert/Passkey2`)에 쓰지 않는다.
- **식별자 매핑 (5개, 이 값 그대로)**:
  - `PSK_APP_RUNTIME_USER` → `PSK_APP_RUNTIME_USER`
  - `PSK_APP_ADMIN_USER` → `PSK_APP_ADMIN_USER`
  - `PSK_APP_RUNTIME` → `PSK_APP_RUNTIME`
  - `PSK_APP_ADMIN` → `PSK_APP_ADMIN`
  - `PSK_APP_OWNER` → `PSK_APP_OWNER`
- **치환 순서**: 반드시 위 순서(긴 이름 우선). `PSK_APP_ADMIN` 은 `PSK_APP_ADMIN_USER` 의 부분문자열이다.
- **이중 접두사 금지**: 이미 `PSK_` 가 붙은 토큰은 재치환하지 않는다(`(?<!PSK_)` lookbehind).
- **파일명은 바꾸지 않는다**: `V4__grant_security_incident_to_app_runtime.sql`, `reset-app-owner*.sql` 등 내용만 수정한다.
- **프로퍼티명**: `passkey.db.schema` (기존 `passkey.admin`, `passkey.key-envelope` 등과 같은 계층)
- **기본값**: `${PASSKEY_DB_SCHEMA:PSK_APP_OWNER}`
- **범위 밖**: 비밀번호 변경, 테이블/컬럼명 변경, `passkey_rp` 별도 저장소.

---

## File Structure

**신규 생성**

| 파일 | 책임 |
|---|---|
| `scripts/rename-psk-prefix.py` | 5개 식별자 일괄 치환 도구(순서·경계·lookbehind 보장). Task 2 에서 만들고 Task 6·7 에서 재사용 후 Task 10 에서 제거 |
| `core/src/main/java/com/crosscert/passkey/core/config/DbSchemaProperties.java` | `passkey.db.schema` 값 보유 + 식별자 패턴 검증 |
| `core/src/test/java/com/crosscert/passkey/core/config/DbSchemaPropertiesTest.java` | 위 검증 로직 단위 테스트 |

**수정**

| 파일 | 변경 내용 |
|---|---|
| `core/src/main/resources/db/migration/V1__baseline_schema.sql` | GRANT 대상 롤명 134건 |
| `core/src/main/resources/db/migration/V4__grant_security_incident_to_app_runtime.sql` | GRANT 대상 롤명 1건 |
| `core/src/main/resources/application-common.yml` | `hibernate.default_schema` + `passkey.db.schema` 추가 |
| `admin-app/src/main/resources/application.yml` | Flyway `schemas`/`default-schema`/`user` |
| `admin-app/.../application-{dev,local}.yml`, `passkey-app/.../application-dev.yml` | datasource `username` |
| `{core,admin-app,passkey-app}/src/test/resources/application-test.yml` | Flyway/hibernate 스키마 |
| Java 7개 (아래 Task 4·5) | `PSK_APP_OWNER.` 하드코딩 → 주입된 스키마명 |
| `scripts/` 9개 | DDL·bootstrap·reset |
| 테스트 29개 + `docs/` 70개 + `deploy/`·루트 4개 | 기계적 치환 |

---

### Task 1: 치환 도구 작성

치환은 134개 파일에 걸쳐 있고 부분문자열 충돌 위험이 있다. 손으로 하거나 `sed` 로 하면 반드시 깨진다. 도구를 먼저 만들고 그 도구를 테스트한다.

**Files:**
- Create: `scripts/rename-psk-prefix.py`
- Test: 도구 자체를 `--self-test` 로 검증(별도 테스트 파일 없음 — 일회성 도구이며 Task 10 에서 삭제한다)

**Interfaces:**
- Produces: `python3 scripts/rename-psk-prefix.py --self-test` (검증), `python3 scripts/rename-psk-prefix.py <path>...` (치환 수행, 변경된 파일 수 출력)

- [ ] **Step 1: 도구 작성**

`scripts/rename-psk-prefix.py` 를 아래 내용 그대로 생성한다.

```python
#!/usr/bin/env python3
"""DB 계정·롤에 PSK_ 접두사를 붙이는 일괄 치환 도구.

핵심 제약 두 가지를 코드로 보장한다.
  1) 긴 이름 우선 — PSK_APP_ADMIN 은 PSK_APP_ADMIN_USER 의 부분문자열이라
     짧은 것부터 치환하면 PSK_APP_ADMIN_USER 가 아니라 깨진 이름이 된다.
  2) 이중 접두사 금지 — 이미 PSK_ 가 붙은 토큰은 건너뛴다.
"""
import re
import sys

# 순서가 곧 우선순위다. 긴 이름부터.
MAPPING = [
    ("PSK_APP_RUNTIME_USER", "PSK_APP_RUNTIME_USER"),
    ("PSK_APP_ADMIN_USER",   "PSK_APP_ADMIN_USER"),
    ("PSK_APP_RUNTIME",      "PSK_APP_RUNTIME"),
    ("PSK_APP_ADMIN",        "PSK_APP_ADMIN"),
    ("PSK_APP_OWNER",        "PSK_APP_OWNER"),
]

# (?<!PSK_) : 이미 접두사가 붙은 토큰 제외
# (?<![A-Z0-9_]) / (?![A-Z0-9_]) : 단어 경계. \b 는 밑줄을 단어문자로 봐서
#   PSK_APP_ADMIN_USER 안의 PSK_APP_ADMIN 을 걸러내지 못하므로 직접 명시한다.
PATTERNS = [
    (re.compile(r"(?<!PSK_)(?<![A-Z0-9_])" + old + r"(?![A-Z0-9_])"), new)
    for old, new in MAPPING
]


def convert(text: str) -> str:
    for pattern, new in PATTERNS:
        text = pattern.sub(new, text)
    return text


def self_test() -> int:
    cases = [
        # (입력, 기대)
        ("GRANT SELECT ON t TO PSK_APP_ADMIN;", "GRANT SELECT ON t TO PSK_APP_ADMIN;"),
        ("GRANT PSK_APP_ADMIN TO PSK_APP_ADMIN_USER;", "GRANT PSK_APP_ADMIN TO PSK_APP_ADMIN_USER;"),
        ("username: PSK_APP_RUNTIME_USER", "username: PSK_APP_RUNTIME_USER"),
        ("FROM PSK_APP_OWNER.mds_sync_history", "FROM PSK_APP_OWNER.mds_sync_history"),
        # 이미 치환된 것은 그대로 (멱등성)
        ("TO PSK_APP_ADMIN_USER;", "TO PSK_APP_ADMIN_USER;"),
        ("PSK_APP_OWNER.tenant", "PSK_APP_OWNER.tenant"),
        # 부분문자열이 아닌 다른 식별자는 건드리지 않는다
        ("MY_APP_ADMINISTRATOR", "MY_APP_ADMINISTRATOR"),
        ("APP_ADMINX", "APP_ADMINX"),
    ]
    failed = 0
    for src, expected in cases:
        got = convert(src)
        if got != expected:
            print(f"FAIL: {src!r}\n  expected {expected!r}\n  got      {got!r}")
            failed += 1
    # 멱등성: 두 번 돌려도 같아야 한다
    sample = "GRANT PSK_APP_ADMIN TO PSK_APP_ADMIN_USER; FROM PSK_APP_OWNER.t"
    once = convert(sample)
    twice = convert(once)
    if once != twice:
        print(f"FAIL(idempotent): {once!r} != {twice!r}")
        failed += 1
    print("self-test: FAILED" if failed else f"self-test: OK ({len(cases)} cases + idempotency)")
    return 1 if failed else 0


def main(argv):
    if "--self-test" in argv:
        return self_test()
    paths = [a for a in argv if not a.startswith("-")]
    if not paths:
        print("usage: rename-psk-prefix.py <file>...  |  --self-test", file=sys.stderr)
        return 2
    changed = 0
    for path in paths:
        try:
            with open(path, "r", encoding="utf-8") as fh:
                original = fh.read()
        except (UnicodeDecodeError, IsADirectoryError):
            continue  # 바이너리·디렉터리는 건너뛴다
        converted = convert(original)
        if converted != original:
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(converted)
            changed += 1
    print(f"changed: {changed}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
```

- [ ] **Step 2: self-test 실행 — 통과해야 한다**

Run: `python3 scripts/rename-psk-prefix.py --self-test`
Expected: `self-test: OK (8 cases + idempotency)`

`FAIL` 이 하나라도 나오면 다음 Task 로 넘어가지 않는다. 특히 `GRANT PSK_APP_ADMIN TO PSK_APP_ADMIN_USER;` 케이스가 부분문자열 충돌을 잡는 핵심이다.

- [ ] **Step 3: 커밋**

```bash
git add scripts/rename-psk-prefix.py
git commit -m "chore(scripts): PSK_ 접두사 일괄 치환 도구 추가

부분문자열 충돌(PSK_APP_ADMIN ⊂ PSK_APP_ADMIN_USER)과 이중 접두사를
코드로 막는다. self-test 8케이스 + 멱등성 검증 포함."
```

---

### Task 2: Flyway 마이그레이션 V1 / V4

Flyway 가 아직 한 번도 실행되지 않았으므로 직접 수정한다(체크섬 문제 없음).

**Files:**
- Modify: `core/src/main/resources/db/migration/V1__baseline_schema.sql`
- Modify: `core/src/main/resources/db/migration/V4__grant_security_incident_to_app_runtime.sql`

**Interfaces:**
- Consumes: Task 1 의 `scripts/rename-psk-prefix.py`
- Produces: `PSK_APP_ADMIN` / `PSK_APP_RUNTIME` 롤에 GRANT 하는 마이그레이션

- [ ] **Step 1: 치환 전 건수 기록**

```bash
grep -c 'TO PSK_APP_ADMIN;' core/src/main/resources/db/migration/V1__baseline_schema.sql
grep -c 'TO PSK_APP_RUNTIME;' core/src/main/resources/db/migration/V1__baseline_schema.sql
grep -c 'TO PSK_APP_RUNTIME;' core/src/main/resources/db/migration/V4__grant_security_incident_to_app_runtime.sql
```
Expected: `93`, `41`, `1`

- [ ] **Step 2: 치환 실행**

```bash
python3 scripts/rename-psk-prefix.py \
  core/src/main/resources/db/migration/V1__baseline_schema.sql \
  core/src/main/resources/db/migration/V4__grant_security_incident_to_app_runtime.sql
```
Expected: `changed: 2`

- [ ] **Step 3: 건수 보존 확인**

```bash
grep -c 'TO PSK_APP_ADMIN;' core/src/main/resources/db/migration/V1__baseline_schema.sql
grep -c 'TO PSK_APP_RUNTIME;' core/src/main/resources/db/migration/V1__baseline_schema.sql
grep -c 'TO PSK_APP_RUNTIME;' core/src/main/resources/db/migration/V4__grant_security_incident_to_app_runtime.sql
```
Expected: `93`, `41`, `1` — Step 1 과 정확히 같아야 한다.

- [ ] **Step 4: 잔존·오염 검사**

```bash
grep -nE '(^|[^A-Z0-9_])APP_(OWNER|RUNTIME|ADMIN)([^A-Z0-9_]|$)' core/src/main/resources/db/migration/*.sql; echo "exit=$?"
grep -nE 'PSK_PSK_|PSK_APP_(ADMIN|RUNTIME)_USER_USER' core/src/main/resources/db/migration/*.sql; echo "exit=$?"
```
Expected: 둘 다 출력 없음 + `exit=1` (grep 이 아무것도 못 찾았다는 뜻)

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/resources/db/migration/
git commit -m "fix(db): V1/V4 GRANT 대상 롤을 PSK_ 접두사로 정정

Flyway 미실행 상태라 체크섬 문제 없이 직접 수정한다.
V1: TO PSK_APP_ADMIN 93건, TO PSK_APP_RUNTIME 41건. V4: 1건."
```

---

### Task 3: 스키마 프로퍼티 도입

`PSK_APP_OWNER` 는 스키마 소유자다. 이름이 바뀌면 스키마명이 바뀌고, JPA 전체(`hibernate.default_schema`)와 raw SQL 14곳이 함께 깨진다. 값을 한 곳에 모으고 주입한다.

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/config/DbSchemaProperties.java`
- Create: `core/src/test/java/com/crosscert/passkey/core/config/DbSchemaPropertiesTest.java`
- Modify: `core/src/main/resources/application-common.yml`

**Interfaces:**
- Produces: `DbSchemaProperties.schema()` → `String` (검증된 스키마 식별자). Task 4·5 가 이 값을 주입받는다. 프로퍼티 키는 `passkey.db.schema`, 기본값 `PSK_APP_OWNER`.

- [ ] **Step 1: 실패하는 테스트 작성**

`core/src/test/java/com/crosscert/passkey/core/config/DbSchemaPropertiesTest.java`:

```java
package com.crosscert.passkey.core.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DbSchemaPropertiesTest {

    @Test
    void acceptsValidIdentifier() {
        assertThat(new DbSchemaProperties("PSK_APP_OWNER").schema()).isEqualTo("PSK_APP_OWNER");
    }

    @Test
    void acceptsLegacyIdentifier() {
        // 이름은 배포자가 정한다. PSK_ 를 강제하지 않는다.
        assertThat(new DbSchemaProperties("PSK_APP_OWNER").schema()).isEqualTo("PSK_APP_OWNER");
    }

    @Test
    void rejectsSqlInjectionAttempt() {
        // 값의 출처는 설정 파일이지만, 오설정을 부팅 시점에 잡는다.
        assertThatThrownBy(() -> new DbSchemaProperties("PSK_APP_OWNER; DROP TABLE tenant"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passkey.db.schema");
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> new DbSchemaProperties("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsLeadingDigit() {
        assertThatThrownBy(() -> new DbSchemaProperties("1APP"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests '*DbSchemaPropertiesTest*'`
Expected: FAIL — `DbSchemaProperties` 클래스가 없어 컴파일 에러

- [ ] **Step 3: 구현**

`core/src/main/java/com/crosscert/passkey/core/config/DbSchemaProperties.java`:

```java
package com.crosscert.passkey.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 스키마 소유자 계정명(= 스키마명)을 한 곳에서 관리한다.
 *
 * <p>raw SQL 에 스키마명을 하드코딩하면 계정명이 바뀔 때마다 코드를 고쳐야 하고,
 * 컴파일은 통과하므로 런타임에야 깨진다. 값을 설정으로 빼면 다음 rename 은
 * 환경변수 교체로 끝난다.
 *
 * <p>값의 출처는 배포자가 통제하는 설정이며 사용자 입력이 아니다. 그래도
 * 식별자 패턴을 부팅 시점에 검증해 오타·오설정을 조기에 드러낸다.
 */
@Component
public class DbSchemaProperties {

    private static final Pattern VALID = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    private final String schema;

    public DbSchemaProperties(@Value("${passkey.db.schema}") String schema) {
        String trimmed = schema == null ? "" : schema.trim();
        if (!VALID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "passkey.db.schema 가 올바른 식별자가 아닙니다: '" + schema + "'");
        }
        this.schema = trimmed;
    }

    /** 검증된 스키마 식별자. raw SQL 의 테이블 prefix 로 쓴다. */
    public String schema() {
        return schema;
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test --tests '*DbSchemaPropertiesTest*'`
Expected: PASS (5 tests)

- [ ] **Step 5: 프로퍼티 등록 + hibernate 스키마 정정**

`core/src/main/resources/application-common.yml` 에서 `hibernate.default_schema` 값을 바꾸고, 주변 주석의 계정명도 함께 정정한다.

```yaml
      # The app connects as PSK_APP_RUNTIME_USER (passkey) or PSK_APP_ADMIN_USER
      # (admin). All entity tables live in PSK_APP_OWNER schema, so Hibernate
      # must validate and issue DML against PSK_APP_OWNER, not the login
      # user's default schema. This mirrors Flyway's default-schema
      # setting (admin-app application.yml).
      hibernate.default_schema: ${passkey.db.schema}
```

같은 파일 최상위(`spring:` 과 같은 레벨)에 `passkey.db.schema` 를 추가한다. 이미 `passkey:` 블록이 있으면 그 안에 `db:` 를 넣고, 없으면 블록째 추가한다.

```yaml
passkey:
  db:
    # 스키마 소유자 계정명. 계정명이 바뀌면 이 값(또는 PASSKEY_DB_SCHEMA)만 바꾼다.
    schema: ${PASSKEY_DB_SCHEMA:PSK_APP_OWNER}
```

- [ ] **Step 6: core 테스트 전체 확인**

Run: `./gradlew :core:test --tests '*DbSchemaPropertiesTest*'`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add core/src/main/java/com/crosscert/passkey/core/config/DbSchemaProperties.java \
        core/src/test/java/com/crosscert/passkey/core/config/DbSchemaPropertiesTest.java \
        core/src/main/resources/application-common.yml
git commit -m "feat(core): 스키마명을 passkey.db.schema 프로퍼티로 외부화

hibernate.default_schema 를 프로퍼티 참조로 바꾸고, 식별자 패턴을
부팅 시점에 검증하는 DbSchemaProperties 를 추가한다."
```

---

### Task 4: `core` / `passkey-app` raw SQL 스키마 주입

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/jwt/SigningKeyProvider.java` (실행 SQL 1곳, 주석 2곳)
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyLookupService.java` (실행 SQL 2곳, 주석 2곳)

**Interfaces:**
- Consumes: `DbSchemaProperties.schema()` (Task 3)
- Produces: 두 클래스 모두 생성자에 `DbSchemaProperties` 를 받는다. `SigningKeyProvider` 의 기존 4-arg 테스트 생성자는 시그니처를 유지한다.

- [ ] **Step 1: SigningKeyProvider 수정**

`@Autowired` 6-arg 생성자에 파라미터를 추가하고 필드에 보관한다.

```java
    private final JwksAssembler jwksAssembler;
    private final String schema;
    private volatile RSAKey cachedActive;

    @org.springframework.beans.factory.annotation.Autowired
    public SigningKeyProvider(SigningKeyRepository repo,
                              KeyEnvelope envelope,
                              ObjectMapper mapper,
                              Clock clock,
                              JdbcTemplate jdbc,
                              JwksAssembler jwksAssembler,
                              DbSchemaProperties dbSchema) {
        this.repo = repo;
        this.envelope = envelope;
        this.mapper = mapper;
        this.clock = clock;
        this.jdbc = jdbc;
        this.jwksAssembler = jwksAssembler;
        this.schema = dbSchema.schema();
    }
```

기존 4-arg 생성자는 **시그니처를 그대로 두고** 위임만 고친다. `jdbc` 가 null 인 경로(PL/SQL 미사용 단위 테스트)이므로 스키마는 기본값으로 채운다.

```java
    public SigningKeyProvider(SigningKeyRepository repo,
                              KeyEnvelope envelope,
                              ObjectMapper mapper,
                              Clock clock) {
        this(repo, envelope, mapper, clock, null, new JwksAssembler(repo),
             new DbSchemaProperties("PSK_APP_OWNER"));
    }
```

`import com.crosscert.passkey.core.config.DbSchemaProperties;` 를 추가한다.

실행 SQL(129행 부근)을 문자열 결합으로 바꾼다.

```java
                    "{ call " + schema + ".signing_key_bootstrap_pkg.bootstrap_active(?,?,?,?,?) }")) {
```

주석 2곳(`PSK_APP_OWNER.signing_key_bootstrap_pkg`, `PSK_APP_ADMIN or PSK_APP_RUNTIME`)의 계정명도 `PSK_` 형태로 정정한다.

- [ ] **Step 2: ApiKeyLookupService 수정**

생성자에 파라미터를 추가한다.

```java
    private final String schema;

    public ApiKeyLookupService(DataSource dataSource, DbSchemaProperties dbSchema) {
        this.dataSource = dataSource;
        this.schema = dbSchema.schema();
    }
```

`import com.crosscert.passkey.core.config.DbSchemaProperties;` 를 추가한다.

실행 SQL 2곳을 결합으로 바꾼다(65행·86행 부근).

```java
              + "FROM " + schema + ".api_key WHERE key_prefix = ? AND revoked_at IS NULL"
```
```java
                    "UPDATE " + schema + ".api_key SET last_used_at = ? WHERE id = ?"
```

주석 2곳의 계정명도 정정한다.

- [ ] **Step 3: 컴파일 + 단위 테스트**

Run: `./gradlew :core:compileJava :passkey-app:compileJava :core:test --tests '*SigningKeyProviderTest*' --tests '*IdTokenIssuerTest*'`
Expected: 컴파일 성공, 테스트 PASS. 4-arg 생성자를 쓰는 기존 테스트 2개가 그대로 통과해야 한다.

- [ ] **Step 4: 잔존 검사**

```bash
grep -n 'PSK_APP_OWNER' core/src/main/java/com/crosscert/passkey/core/jwt/SigningKeyProvider.java \
                    passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyLookupService.java | grep -v PSK_
echo "exit=$?"
```
Expected: 출력 없음 + `exit=1`

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/java/com/crosscert/passkey/core/jwt/SigningKeyProvider.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyLookupService.java
git commit -m "refactor(core,passkey-app): raw SQL 스키마명을 주입값으로 교체

SigningKeyProvider 1곳, ApiKeyLookupService 2곳. 4-arg 테스트
생성자는 시그니처를 유지해 기존 단위 테스트를 깨지 않는다."
```

---

### Task 5: `admin-app` raw SQL 스키마 주입

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsHistoryService.java` (실행 SQL 6곳, 주석 3곳)
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditChainBackfillService.java` (2곳)
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/audit/AuditLogService.java` (1곳)
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsBlobStore.java` (1곳)
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsAdminController.java` (1곳)

**Interfaces:**
- Consumes: `DbSchemaProperties.schema()` (Task 3)
- Produces: 다섯 클래스 모두 생성자 마지막 파라미터로 `DbSchemaProperties` 를 받는다.

- [ ] **Step 1: 다섯 클래스에 스키마 주입**

각 클래스에 `import com.crosscert.passkey.core.config.DbSchemaProperties;` 를 추가하고, `private final String schema;` 필드와 생성자 파라미터를 더한다. 패턴은 동일하다.

```java
    private final String schema;

    public MdsHistoryService(JdbcTemplate jdbc, DbSchemaProperties dbSchema) {
        this.jdbc = jdbc;
        this.schema = dbSchema.schema();
    }
```

```java
    public MdsBlobStore(JdbcTemplate jdbc, Clock clock, DbSchemaProperties dbSchema) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.schema = dbSchema.schema();
    }
```

`AuditChainBackfillService`, `AuditLogService`, `MdsAdminController` 도 각자 기존 생성자 파라미터 뒤에 `DbSchemaProperties dbSchema` 를 추가하고 `this.schema = dbSchema.schema();` 를 넣는다.

- [ ] **Step 2: 실행 SQL 11곳 치환**

`"... PSK_APP_OWNER.<table> ..."` 형태를 `"... " + schema + ".<table> ..."` 로 바꾼다. 대상은 아래와 같다.

| 파일 | 위치(대략) | 테이블 |
|---|---|---|
| MdsHistoryService | 37, 57, 59, 78, 86, 96 | `mds_sync_history`, `mds_sync_history_seq` |
| AuditChainBackfillService | 45, 85 | `scheduler_lease`, `audit_log` |
| AuditLogService | 117 | `scheduler_lease` |
| MdsBlobStore | 42 | `mds_blob_cache` |
| MdsAdminController | 73 | `mds_blob_cache` |

주의: `MdsHistoryService` 59행은 한 문자열에 `PSK_APP_OWNER.mds_sync_history_seq` 가 들어 있다. 시퀀스도 같은 스키마이므로 동일하게 치환한다.

주석 3곳(MdsHistoryService)의 계정명도 정정한다.

- [ ] **Step 3: 컴파일**

Run: `./gradlew :admin-app:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 잔존 검사**

```bash
grep -rn 'PSK_APP_OWNER' admin-app/src/main/java | grep -v PSK_; echo "exit=$?"
```
Expected: 출력 없음 + `exit=1`

- [ ] **Step 5: 커밋**

```bash
git add admin-app/src/main/java
git commit -m "refactor(admin-app): raw SQL 스키마명을 주입값으로 교체

MdsHistoryService 6곳, AuditChainBackfillService 2곳,
AuditLogService/MdsBlobStore/MdsAdminController 각 1곳."
```

---

### Task 6: 설정 YAML

Java 는 Task 3~5 로 끝났다. 남은 설정값을 정리한다.

**Files:**
- Modify: `admin-app/src/main/resources/application.yml` (Flyway `schemas`/`default-schema`/`user`)
- Modify: `admin-app/src/main/resources/application-{dev,local,prod,qa}.yml`
- Modify: `passkey-app/src/main/resources/application-dev.yml`
- Modify: `{core,admin-app,passkey-app}/src/test/resources/application-test.yml`

**Interfaces:**
- Consumes: Task 1 의 치환 도구
- Produces: Flyway 가 `PSK_APP_OWNER` 스키마를 대상으로 동작

- [ ] **Step 1: 치환 실행**

```bash
python3 scripts/rename-psk-prefix.py \
  admin-app/src/main/resources/application*.yml \
  passkey-app/src/main/resources/application*.yml \
  core/src/main/resources/application*.yml \
  admin-app/src/test/resources/application-test.yml \
  passkey-app/src/test/resources/application-test.yml \
  core/src/test/resources/application-test.yml
```
Expected: `changed:` 뒤에 1 이상

- [ ] **Step 2: Flyway 설정 확인**

```bash
grep -nE 'schemas|default-schema|user:' admin-app/src/main/resources/application.yml
```
Expected: `schemas: PSK_APP_OWNER`, `default-schema: PSK_APP_OWNER`, `user: ${SPRING_FLYWAY_USER:PSK_APP_OWNER}`

- [ ] **Step 3: hibernate.default_schema 가 프로퍼티 참조인지 확인**

Task 3 에서 `${passkey.db.schema}` 로 바꿨으므로 치환 도구가 건드릴 문자열이 남아 있으면 안 된다.

```bash
grep -n 'default_schema' core/src/main/resources/application-common.yml
```
Expected: `hibernate.default_schema: ${passkey.db.schema}`

- [ ] **Step 4: 잔존 검사**

```bash
grep -rn 'PSK_APP_OWNER\|PSK_APP_ADMIN\|PSK_APP_RUNTIME' \
  {core,admin-app,passkey-app}/src/{main,test}/resources/*.yml | grep -v PSK_; echo "exit=$?"
```
Expected: 출력 없음 + `exit=1`

- [ ] **Step 5: 커밋**

```bash
git add {core,admin-app,passkey-app}/src/main/resources/*.yml \
        {core,admin-app,passkey-app}/src/test/resources/application-test.yml
git commit -m "fix(config): datasource·Flyway 계정/스키마를 PSK_ 접두사로 정정"
```

---

### Task 7: DDL 스크립트 · 테스트 · 문서 일괄 치환

기계적 치환만 남았다. 한 Task 로 묶되 검증은 촘촘히 한다.

**Files:**
- Modify: `scripts/` 9개 (`full-schema-part1-accounts.sql`, `full-schema-part2-schema.sql`, `bootstrap-schema.sql`, `bootstrap-external.sql`, `bootstrap-external-body.sql`, `reset-app-owner.sql`, `reset-app-owner-external.sql`, `init-db-external.sh`, `init-dev-db.sh`)
- Modify: 테스트 Java 29개 (`{core,admin-app,passkey-app}/src/test/java/**`)
- Modify: `docs/` 70개
- Modify: `deploy/README.md`, `deploy/docker-compose.yml`, `README.md`, `docker-compose.yml`

**Interfaces:**
- Consumes: Task 1 의 치환 도구
- Produces: 저장소 전체에서 구 이름 0건

- [ ] **Step 1: 치환 전 전체 건수 기록**

```bash
git ls-files -z | xargs -0 grep -ohE '\bAPP_(OWNER|RUNTIME_USER|ADMIN_USER|RUNTIME|ADMIN)\b' \
  | sort | uniq -c | sort -rn
```
기대 분포(참고): `PSK_APP_OWNER` 856, `PSK_APP_ADMIN` 371, `PSK_APP_RUNTIME` 201, `PSK_APP_ADMIN_USER` 192, `PSK_APP_RUNTIME_USER` 58. Task 2~6 에서 일부가 이미 치환됐으므로 숫자는 이보다 작다. **이 시점의 값을 적어 둔다.**

- [ ] **Step 2: 남은 파일 전부 치환**

`git ls-files` 를 쓰면 `build/`·`.git/` 이 자동 제외된다. 치환 도구 자신은 MAPPING 상수를 갖고 있으므로 제외한다.

```bash
git ls-files -z | grep -zv '^scripts/rename-psk-prefix.py$' \
  | xargs -0 python3 scripts/rename-psk-prefix.py
```
Expected: `changed:` 뒤에 100 이상

- [ ] **Step 3: 잔존 검사 — 0건이어야 한다**

```bash
git ls-files -z | grep -zv '^scripts/rename-psk-prefix.py$' \
  | xargs -0 grep -nE '(^|[^A-Z0-9_])APP_(OWNER|RUNTIME|ADMIN)([^A-Z0-9_]|$)'; echo "exit=$?"
```
Expected: 출력 없음 + `exit=1`

- [ ] **Step 4: 오염 검사 — 전부 0건이어야 한다**

```bash
git ls-files -z | xargs -0 grep -nE 'PSK_PSK_|PSK_APP_(ADMIN|RUNTIME)_USER_USER|PSK_APP_(ADMIN|RUNTIME)_PSK_'
echo "exit=$?"
```
Expected: 출력 없음 + `exit=1`

- [ ] **Step 5: 건수 보존 확인**

```bash
git ls-files -z | xargs -0 grep -ohE '\bPSK_APP_(OWNER|RUNTIME_USER|ADMIN_USER|RUNTIME|ADMIN)\b' \
  | sort | uniq -c | sort -rn
```
Step 1 에서 적어 둔 분포와 접두사만 다르고 **건수가 같아야 한다**. 다르면 치환이 뭔가를 먹었다는 뜻이므로 되돌리고 원인을 찾는다.

- [ ] **Step 6: DDL 핵심 식별자 육안 확인**

```bash
grep -nE "CREATE (ROLE|USER)" scripts/full-schema-part1-accounts.sql
```
Expected: `PSK_APP_RUNTIME`, `PSK_APP_ADMIN` 롤 2개와 `PSK_APP_OWNER`, `PSK_APP_RUNTIME_USER`, `PSK_APP_ADMIN_USER` 계정 3개

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "fix: DDL·테스트·문서의 계정/롤명을 PSK_ 접두사로 정정

scripts 9개, 테스트 29개, docs 70개, deploy·루트 4개.
치환 도구로 일괄 처리하고 잔존·오염·건수보존 3종 검사를 통과했다."
```

---

### Task 8: 빌드 및 통합 테스트 검증

**Files:**
- 변경 없음 (검증 전용)

**Interfaces:**
- Consumes: Task 2~7 의 모든 변경

- [ ] **Step 1: 전체 컴파일**

Run: `./gradlew :core:compileJava :core:compileTestJava :admin-app:compileJava :admin-app:compileTestJava :passkey-app:compileJava :passkey-app:compileTestJava`
Expected: BUILD SUCCESSFUL

컴파일 실패는 Task 3~5 의 생성자 시그니처 변경을 못 따라온 호출부가 있다는 뜻이다. 해당 파일을 고치고 Task 5 커밋에 amend 하지 말고 별도 커밋한다.

- [ ] **Step 2: 단위 테스트 (Docker 불필요)**

Run: `./gradlew :core:test --tests '*DbSchemaPropertiesTest*' --tests '*SigningKeyProviderTest*' --tests '*IdTokenIssuerTest*'`
Expected: PASS

- [ ] **Step 3: 통합 테스트 (Testcontainers Oracle 필요)**

Run: `./gradlew :core:test :admin-app:test :passkey-app:test`
Expected: PASS

⚠️ **실패 시 판정 절차**: 이 환경은 Testcontainers Oracle 경합으로 전체 빌드가 불안정한 이력이 있다. 실패가 나오면 **이번 변경 탓인지 원래 그런지**를 먼저 가린다.

```bash
# base(main) 에서 같은 테스트를 돌려 대조한다
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
./gradlew :admin-app:test --tests '<실패한 테스트>'
```
main 에서도 같은 이유로 실패하면 pre-existing 이므로 이번 작업의 결함이 아니다. 그 사실을 기록하고 넘어간다. main 에서 통과하는데 여기서만 실패하면 이번 변경의 회귀이므로 반드시 고친다.

- [ ] **Step 4: 검증 결과 기록**

통과/실패와 pre-existing 판정 결과를 커밋 메시지에 남긴다. 변경 파일이 없으면 커밋하지 않고 다음 Task 로 간다.

---

### Task 9: QA 반입 번들 재생성

DDL 과 이미지가 모두 바뀌었으므로 번들을 다시 만든다.

**Files:**
- Modify: `/Users/jhyun/Git/10-work/crosscert/passkey-qa-bundle/**` (저장소 밖)
- Create: `/Users/jhyun/Git/10-work/crosscert/passkey-qa-bundle.tar.gz` (교체)

**Interfaces:**
- Consumes: Task 7 의 `scripts/full-schema-part{1,2}*.sql`
- Produces: `PSK_` 반영된 반입 파일 2개(`tar.gz` + `sha256`)

- [ ] **Step 1: amd64 이미지 3개 재빌드**

개발 Mac 은 arm64 다. 그대로 `docker save` 하면 x86_64 QA 서버에서 `exec format error` 가 난다. 반드시 `--platform linux/amd64` 를 준다.

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/psk-prefix-rename
for app in passkey-app admin-app rp-app; do
  docker buildx build --platform linux/amd64 \
    -t $app:0.0.1-SNAPSHOT -f $app/Dockerfile --load . || exit 1
done
```

- [ ] **Step 2: 아키텍처 검증**

```bash
for i in passkey-app admin-app rp-app nginx:1.27-alpine redis:7-alpine; do
  case $i in *:*) img=$i;; *) img=$i:0.0.1-SNAPSHOT;; esac
  echo -n "$img => "; docker image inspect $img --format '{{.Os}}/{{.Architecture}}'
done
```
Expected: 5개 모두 `linux/amd64`

- [ ] **Step 3: DDL·SQL 갱신본 복사**

```bash
B=/Users/jhyun/Git/10-work/crosscert/passkey-qa-bundle
cp scripts/full-schema-part1-accounts.sql scripts/full-schema-part2-schema.sql "$B/scripts/"
```

`$B/scripts/DBA-요청서.md`, `$B/sql/seed-admin-user.sql`, `$B/deploy/.env.qa-template`, `$B/README.md`, `$B/CHECKLIST.md` 안의 계정명을 치환한다.

```bash
python3 scripts/rename-psk-prefix.py \
  "$B/scripts/DBA-요청서.md" "$B/sql/seed-admin-user.sql" \
  "$B/deploy/.env.qa-template" "$B/deploy/.env.example" \
  "$B/README.md" "$B/CHECKLIST.md"
```

`seed-admin-user.sql` 은 `PSK_APP_OWNER 로 실행` 안내가 `PSK_APP_OWNER` 로 바뀌었는지 확인한다.

- [ ] **Step 4: deploy 설정 동기화**

```bash
cp deploy/docker-compose.yml deploy/docker-compose.redis.yml deploy/README.md "$B/deploy/"
cp deploy/nginx/nginx.conf "$B/deploy/nginx/"
```

- [ ] **Step 5: 이미지 tarball 재생성**

```bash
cd "$B/images"
docker save passkey-app:0.0.1-SNAPSHOT admin-app:0.0.1-SNAPSHOT rp-app:0.0.1-SNAPSHOT \
            nginx:1.27-alpine redis:7-alpine | gzip -1 > passkey-images-qa.tar.gz
shasum -a 256 passkey-images-qa.tar.gz > passkey-images-qa.tar.gz.sha256
shasum -a 256 -c passkey-images-qa.tar.gz.sha256
```
Expected: `passkey-images-qa.tar.gz: OK`

- [ ] **Step 6: tarball 내부 아키텍처 확인**

```bash
cd /tmp && rm -rf tarcheck && mkdir tarcheck
tar xzf "$B/images/passkey-images-qa.tar.gz" -C tarcheck
cd tarcheck && python3 -c "
import json
for e in json.load(open('manifest.json')):
    cfg = json.load(open(e['Config']))
    print(f\"  {e['RepoTags'][0]:35s} {cfg.get('os')}/{cfg.get('architecture')}\")
"
cd /tmp && rm -rf tarcheck
```
Expected: 5줄 모두 `linux/amd64`

- [ ] **Step 7: 잔존 검사 — 번들 안에 구 이름 0건**

```bash
grep -rnE '(^|[^A-Z0-9_])APP_(OWNER|RUNTIME|ADMIN)([^A-Z0-9_]|$)' "$B" \
  --exclude=*.tar.gz --exclude=*.tgz --exclude=docker-compose-linux-x86_64 --exclude=*.jar
echo "exit=$?"
```
Expected: 출력 없음 + `exit=1`

- [ ] **Step 8: 단일 파일 재압축**

```bash
cd /Users/jhyun/Git/10-work/crosscert
rm -f passkey-qa-bundle.tar.gz passkey-qa-bundle.tar.gz.sha256
COPYFILE_DISABLE=1 tar --no-mac-metadata --disable-copyfile \
  -czf passkey-qa-bundle.tar.gz passkey-qa-bundle 2>/dev/null \
  || COPYFILE_DISABLE=1 tar -czf passkey-qa-bundle.tar.gz passkey-qa-bundle
shasum -a 256 passkey-qa-bundle.tar.gz > passkey-qa-bundle.tar.gz.sha256
ls -lh passkey-qa-bundle.tar.gz*
```

- [ ] **Step 9: Linux 추출 검증**

```bash
cd /Users/jhyun/Git/10-work/crosscert
docker run --rm --platform linux/amd64 -v "$PWD":/src:ro -w /src redis:7-alpine sh -c '
  sha256sum -c passkey-qa-bundle.tar.gz.sha256
  cd /tmp && tar xzf /src/passkey-qa-bundle.tar.gz && cd passkey-qa-bundle
  ls -l *.sh | awk "{print \$1, \$NF}"
  grep -c PSK_APP_OWNER scripts/full-schema-part1-accounts.sql
  (cd images && sha256sum -c *.sha256)
'
```
Expected: 체크섬 OK, 스크립트 4개 `-rwxr-xr-x`, `PSK_APP_OWNER` 다수 검출, 내부 체크섬 OK

- [ ] **Step 10: 번들은 저장소 밖이므로 커밋 대상이 아니다**

`passkey-qa-bundle/` 은 git 관리 대상이 아니다. 커밋하지 않는다. 저장소 working tree 가 깨끗한지만 확인한다.

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/psk-prefix-rename
git status --short
```
Expected: 출력 없음

---

### Task 10: 정리 및 머지

**Files:**
- Delete: `scripts/rename-psk-prefix.py`

- [ ] **Step 1: 일회성 도구 제거**

치환은 끝났고 도구는 MAPPING 상수 때문에 구 이름을 계속 담고 있다. 잔존 검사에서 매번 예외 처리해야 하므로 지운다.

```bash
git rm scripts/rename-psk-prefix.py
git commit -m "chore(scripts): 일회성 PSK_ 치환 도구 제거

치환 완료. 도구가 MAPPING 에 구 이름을 담고 있어 잔존 검사의
예외 항목이 되므로 제거한다."
```

- [ ] **Step 2: 최종 잔존 검사 (예외 없이 0건)**

```bash
git ls-files -z | xargs -0 grep -nE '(^|[^A-Z0-9_])APP_(OWNER|RUNTIME|ADMIN)([^A-Z0-9_]|$)'; echo "exit=$?"
```
Expected: 출력 없음 + `exit=1`. 이제 예외 파일이 없으므로 진짜 0건이어야 한다.

- [ ] **Step 3: 커밋 로그 확인**

```bash
git log --oneline main..HEAD
```
Expected: Task 1~10 의 커밋이 순서대로 보인다.

- [ ] **Step 4: main 머지**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git checkout main
git merge --no-ff psk-prefix-rename -m "Merge: DB 계정·롤 PSK_ 접두사 전면 반영

QA Oracle DB 의 계정·롤이 PSK_ 접두사로 생성돼 코드베이스를 맞춘다.
- 식별자 5개, 134개 파일
- 스키마명을 passkey.db.schema 로 외부화(raw SQL 14곳)
- Flyway 미실행 상태라 V1/V4 직접 수정
- QA 반입 번들 재생성(amd64)"
```

- [ ] **Step 5: worktree 정리**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git worktree remove .claude/worktrees/psk-prefix-rename
git worktree list
```
Expected: `psk-prefix-rename` 이 목록에서 사라진다.

---

## 배포 시 주의

- **기존 DB 가 있다면** 이 변경은 계정·롤을 새로 만든 DB 를 전제한다. 구 이름으로 이미 Flyway 가 돌아간 DB 에는 적용할 수 없다(V1 체크섬 불일치). QA 는 신규 생성이므로 해당 없음.
- **`PASSKEY_DB_SCHEMA`** 환경변수로 스키마명을 덮어쓸 수 있다. 기본값은 `PSK_APP_OWNER`.
- **`SPRING_FLYWAY_USER`** 기본값도 `PSK_APP_OWNER` 로 바뀌었다. `deploy/.env` 의 `DB_OWNER_USER` 를 실제 계정명과 맞춘다.
