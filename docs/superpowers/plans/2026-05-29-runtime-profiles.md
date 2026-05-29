# Runtime Profiles (dev / qa / prod) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** admin-app + passkey-app 에 dev/qa/prod 프로필을 명시적으로 분리하고 local/local-shared 는 dev 가 흡수해서 제거한다.

**Architecture:** 모듈마다 dev/qa/prod 3개 yml 파일을 self-contained 로 둔다 (Spring `config.import` chain 없음). dev 는 localhost 하드코딩, qa/prod 는 모든 시크릿/환경 특화 값을 `${VAR:}` 빈 default placeholder 로 환경변수 주입. prod 는 추가로 Flyway 안전장치 3종(baseline-on-migrate=false, validate-on-migrate=true, clean-disabled=true) + root WARN 로깅.

**Tech Stack:**
- Spring Boot 3.x profile resolution (`SPRING_PROFILES_ACTIVE`)
- 기존 `core/application-common.yml` 그대로 (모든 jar 가 자동 import)
- 환경변수 placeholder: `${VAR:default}` — 빈 default 면 미설정 시 부팅 실패

**Spec:** `docs/superpowers/specs/2026-05-29-runtime-profiles-design.md`

---

## File Structure

### Create

| 파일 | 책임 |
|---|---|
| `admin-app/src/main/resources/application-qa.yml` | qa 환경 — datasource/redis/key-envelope 환경변수, INFO 로깅 |
| `admin-app/src/main/resources/application-prod.yml` | prod 환경 — qa 와 동일 + Flyway 안전장치 3종 + root WARN |
| `passkey-app/src/main/resources/application-qa.yml` | qa 환경 — admin 과 동일 패턴 + `passkey.id-token.issuer-base` 환경변수 |
| `passkey-app/src/main/resources/application-prod.yml` | prod 환경 — qa 와 동일 + root WARN |

### Modify

| 파일 | 변경 |
|---|---|
| `admin-app/src/main/resources/application-dev.yml` | local + local-shared 흡수, `spring.config.import` 제거, self-contained |
| `passkey-app/src/main/resources/application-dev.yml` | 동일 패턴 |
| `admin-app/src/test/resources/application-test.yml` | 주석의 `local-shared default` 표현을 `dev profile default` 로 갱신 |
| `passkey-app/src/test/resources/application-test.yml` | 동일 |
| `README.md` | 시작하기 섹션의 프로필 설명 갱신 (3개 프로필 명시) |
| `docs/dev-setup.md` | profile 변경 사항 반영 |
| `docs/onprem-deployment.md` | `SPRING_PROFILES_ACTIVE=prod,onprem` 가 실제 동작함을 명시 |

### Delete

| 파일 | 이유 |
|---|---|
| `core/src/main/resources/application-local-shared.yml` | dev 가 흡수 |
| `admin-app/src/main/resources/application-local.yml` | dev 가 흡수 |
| `passkey-app/src/main/resources/application-local.yml` | dev 가 흡수 |

---

## Task Decomposition

### Phase P1 — dev 통합 (local + local-shared 흡수)

dev 부팅이 깨지지 않게 한 commit 으로 묶어 처리. 기존 local/local-shared 가 dev import chain 의 일부이므로 동시에 처리해야 함.

---

### Task P1.1: admin-app dev profile 자급 (self-contained)

**Files:**
- Modify: `admin-app/src/main/resources/application-dev.yml`

- [ ] **Step 1: 현재 dev 와 local 내용 확인**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/runtime-profiles
cat admin-app/src/main/resources/application-dev.yml
echo "---"
cat admin-app/src/main/resources/application-local.yml
echo "---"
cat core/src/main/resources/application-local-shared.yml
```

- [ ] **Step 2: application-dev.yml 전체 교체**

`admin-app/src/main/resources/application-dev.yml` 의 내용을 다음으로 전체 교체:

```yaml
# dev profile — 로컬 개발자 환경
#
# 활성화: SPRING_PROFILES_ACTIVE=dev
#
# 포함: localhost Oracle/Redis + APP_ADMIN_USER credential + dev key-envelope
#       master + DEBUG 로깅 + db/dev 시드 (R__dev_seed.sql)
#
# 기존 local / local-shared 가 흡수됨 — 더 이상 spring.config.import chain 사용 안 함.

spring:
  datasource:
    url: jdbc:oracle:thin:@localhost:1521/XEPDB1
    driver-class-name: oracle.jdbc.OracleDriver
    username: APP_ADMIN_USER
    password: admin_pw
  data:
    redis:
      host: localhost
      port: 6379
  flyway:
    locations: classpath:db/migration,classpath:db/dev

passkey:
  key-envelope:
    # Local dev only — 32 zero bytes base64.
    # Production sets PASSKEY_KEY_ENVELOPE_MASTER_KEY environment variable.
    master-key: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

logging:
  level:
    com.crosscert.passkey: DEBUG
    org.hibernate.SQL: DEBUG
```

- [ ] **Step 3: 부팅 smoke test (수동)**

이 task 단독으로는 local-shared/local 이 아직 존재하므로 dev 부팅이 작동해야 함. 단, dev yml 이 `spring.config.import` 를 더 이상 사용하지 않으므로 dev 부팅이 self-contained 인지만 확인:

```bash
# Oracle + Redis 가 docker compose 로 떠 있다고 가정
SPRING_PROFILES_ACTIVE=dev timeout 60s ./gradlew :admin-app:bootRun 2>&1 | tail -20
```

Expected: `Started AdminApplication in N seconds` (인프라 컨테이너 의존). 인프라 미가용 시 datasource connection refused — 정상.

해당 테스트는 어렵다면 대신 컴파일/리소스 검증:

```bash
./gradlew :admin-app:processResources
```

Expected: BUILD SUCCESSFUL — yml 문법 오류 없음.

- [ ] **Step 4: 보고 (commit 아직 안 함 — Task P1.4 에서 묶어 commit)**

변경된 파일 + 다음 task 로 진행 가능 상태 보고.

---

### Task P1.2: passkey-app dev profile 자급

**Files:**
- Modify: `passkey-app/src/main/resources/application-dev.yml`

- [ ] **Step 1: passkey-app dev/local 내용 확인**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/runtime-profiles
cat passkey-app/src/main/resources/application-dev.yml
echo "---"
cat passkey-app/src/main/resources/application-local.yml
```

- [ ] **Step 2: application-dev.yml 전체 교체**

`passkey-app/src/main/resources/application-dev.yml` 의 내용을 다음으로 전체 교체:

```yaml
# dev profile — 로컬 개발자 환경
#
# 활성화: SPRING_PROFILES_ACTIVE=dev ./gradlew :passkey-app:bootRun \
#         --args="--passkey.id-token.issuer-base=http://localhost:8080"
#
# 포함: localhost Oracle/Redis + APP_RUNTIME_USER credential + dev key-envelope
#       master + DEBUG 로깅
#
# 기존 local / local-shared 가 흡수됨 — 더 이상 spring.config.import chain 사용 안 함.

spring:
  datasource:
    url: jdbc:oracle:thin:@localhost:1521/XEPDB1
    driver-class-name: oracle.jdbc.OracleDriver
    username: APP_RUNTIME_USER
    password: runtime_pw
  data:
    redis:
      host: localhost
      port: 6379

passkey:
  key-envelope:
    # Local dev only — 32 zero bytes base64.
    # Production sets PASSKEY_KEY_ENVELOPE_MASTER_KEY environment variable.
    master-key: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

logging:
  level:
    com.crosscert.passkey: DEBUG
    org.hibernate.SQL: DEBUG
```

- [ ] **Step 3: 리소스 검증**

```bash
./gradlew :passkey-app:processResources
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: 보고 (commit 아직 안 함)**

---

### Task P1.3: local + local-shared 파일 삭제

**Files:**
- Delete: `core/src/main/resources/application-local-shared.yml`
- Delete: `admin-app/src/main/resources/application-local.yml`
- Delete: `passkey-app/src/main/resources/application-local.yml`

- [ ] **Step 1: 3개 파일 삭제**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/runtime-profiles
rm core/src/main/resources/application-local-shared.yml
rm admin-app/src/main/resources/application-local.yml
rm passkey-app/src/main/resources/application-local.yml
```

- [ ] **Step 2: 컴파일 + 리소스 검증**

```bash
./gradlew compileJava processResources
```

Expected: BUILD SUCCESSFUL — `spring.config.import` 가 더 이상 local-shared 를 참조하지 않으므로 빌드 성공.

- [ ] **Step 3: 보고 (commit 아직 안 함)**

---

### Task P1.4: P1 통합 commit

**Files:** (변경된 파일 + 삭제된 파일)
- Modify: `admin-app/src/main/resources/application-dev.yml`
- Modify: `passkey-app/src/main/resources/application-dev.yml`
- Delete: `core/src/main/resources/application-local-shared.yml`
- Delete: `admin-app/src/main/resources/application-local.yml`
- Delete: `passkey-app/src/main/resources/application-local.yml`

- [ ] **Step 1: 전체 staging + 상태 확인**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/runtime-profiles
git add -A \
  admin-app/src/main/resources/application-dev.yml \
  passkey-app/src/main/resources/application-dev.yml \
  core/src/main/resources/application-local-shared.yml \
  admin-app/src/main/resources/application-local.yml \
  passkey-app/src/main/resources/application-local.yml
git status
```

Expected: 2 modified + 3 deleted, 다른 변경 없음.

- [ ] **Step 2: Commit**

```bash
git commit -m "$(cat <<'EOF'
refactor(profiles): dev 가 local + local-shared 흡수 (P1)

기존 spring.config.import chain (dev → local → local-shared) 을 제거하고
admin-app + passkey-app 의 application-dev.yml 을 self-contained 로
재작성. dev 부팅 동작은 동일하지만 yml 한 파일만 보면 dev 환경 전체를
이해할 수 있다.

* application-dev.yml (admin-app, passkey-app) — 전체 교체
  - datasource URL/credential, redis host, key-envelope master,
    dev 시드 (admin-app 만) 모두 한 파일에
* application-local.yml (admin-app, passkey-app) — 삭제
* application-local-shared.yml (core) — 삭제

기존 SPRING_PROFILES_ACTIVE=local 또는 local,dev 사용자는 dev 단독으로
이동 필요 (README, docs/dev-setup.md 갱신은 P4 에서).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 3: 보고**

`git log -1 --oneline` 결과 + commit SHA.

---

## Phase P2 — qa 프로필 신규

dev 가 안정화된 후 qa 신규. 별 모듈씩 1 commit 으로 분리하면 review 가 쉬움.

---

### Task P2.1: admin-app qa profile 신규

**Files:**
- Create: `admin-app/src/main/resources/application-qa.yml`

- [ ] **Step 1: 파일 신규 작성**

`admin-app/src/main/resources/application-qa.yml`:

```yaml
# qa profile — 내부 QA / 통합 테스트 환경
#
# 활성화: SPRING_PROFILES_ACTIVE=qa
#
# 모든 시크릿과 환경 특화 값은 환경변수로 주입. 빈 default (${VAR:}) 는
# 운영자가 반드시 값을 설정해야 함을 표시 (미설정 시 부팅 실패).

spring:
  datasource:
    url:               ${SPRING_DATASOURCE_URL:}
    driver-class-name: oracle.jdbc.OracleDriver
    username:          ${SPRING_DATASOURCE_USERNAME:}
    password:          ${SPRING_DATASOURCE_PASSWORD:}
  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST:}
      port: ${SPRING_DATA_REDIS_PORT:6379}
  flyway:
    # qa 도 baseline-on-migrate 는 base 의 true 그대로 — 일부 qa 환경에서
    # 새 schema 로 시작 가능해야 함.
    locations: classpath:db/migration

server:
  port: ${SERVER_PORT:8081}

passkey:
  key-envelope:
    # qa 는 dev 의 zero-bytes 와 달리 실제 키 주입 필수.
    master-key: ${PASSKEY_KEY_ENVELOPE_MASTER_KEY:}

logging:
  level:
    com.crosscert.passkey: INFO
```

- [ ] **Step 2: 리소스 검증**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/runtime-profiles
./gradlew :admin-app:processResources
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: 부팅 실패 검증 (필수 env 미설정 시)**

```bash
SPRING_PROFILES_ACTIVE=qa timeout 30s ./gradlew :admin-app:bootRun 2>&1 | tail -15
```

Expected: 부팅 실패. `spring.datasource.url` 등이 빈 문자열이라 Hikari 가 connection 실패 또는 Flyway 가 schema 식별 실패. 부팅 성공해버리면 `${VAR:}` 빈 default 가 의도대로 안 됨 — yml 재검토.

(인프라 컨테이너가 떠 있고 실수로 환경변수가 셸 환경에 남아있을 수 있으니, 새 셸 또는 `env -i SPRING_PROFILES_ACTIVE=qa ...` 로 실행.)

- [ ] **Step 4: Commit**

```bash
git add admin-app/src/main/resources/application-qa.yml
git commit -m "$(cat <<'EOF'
feat(profiles): admin-app qa profile (P2.1)

내부 QA / 통합 테스트 환경. 모든 시크릿과 환경 특화 값은 환경변수
placeholder (${VAR:}) — 빈 default 는 미설정 시 부팅 실패를 의도.

* datasource url/username/password: 환경변수 필수
* redis host: 환경변수 필수, port 는 6379 기본
* server.port: 8081 기본 (override 가능)
* key-envelope master-key: 환경변수 필수 (dev 의 zero-bytes 와 달리)
* logging: INFO (dev DEBUG 와 prod WARN 의 중간)
* Flyway db/dev 시드 미포함

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: 보고**

commit SHA + 부팅 실패 메시지 (한 줄) 보고.

---

### Task P2.2: passkey-app qa profile 신규

**Files:**
- Create: `passkey-app/src/main/resources/application-qa.yml`

- [ ] **Step 1: 파일 신규 작성**

`passkey-app/src/main/resources/application-qa.yml`:

```yaml
# qa profile — 내부 QA / 통합 테스트 환경
#
# 활성화: SPRING_PROFILES_ACTIVE=qa
#
# 모든 시크릿과 환경 특화 값은 환경변수로 주입.

spring:
  datasource:
    url:               ${SPRING_DATASOURCE_URL:}
    driver-class-name: oracle.jdbc.OracleDriver
    username:          ${SPRING_DATASOURCE_USERNAME:}
    password:          ${SPRING_DATASOURCE_PASSWORD:}
  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST:}
      port: ${SPRING_DATA_REDIS_PORT:6379}

server:
  port: ${SERVER_PORT:8080}

passkey:
  key-envelope:
    master-key: ${PASSKEY_KEY_ENVELOPE_MASTER_KEY:}
  id-token:
    issuer-base: ${PASSKEY_ID_TOKEN_ISSUER_BASE:}

logging:
  level:
    com.crosscert.passkey: INFO
```

- [ ] **Step 2: 리소스 검증**

```bash
./gradlew :passkey-app:processResources
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add passkey-app/src/main/resources/application-qa.yml
git commit -m "$(cat <<'EOF'
feat(profiles): passkey-app qa profile (P2.2)

admin-app qa 와 동일 패턴 + passkey 고유 설정:
* server.port: 8080 기본
* passkey.id-token.issuer-base: 환경변수 필수 (admin-app 에는 없음)
* 그 외 datasource/redis/key-envelope/logging 은 admin-app qa 와 동일

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: 보고**

commit SHA.

---

## Phase P3 — prod 프로필 신규

qa 와 거의 동일하되 Flyway 안전장치 3종 + root WARN 추가.

---

### Task P3.1: admin-app prod profile 신규

**Files:**
- Create: `admin-app/src/main/resources/application-prod.yml`

- [ ] **Step 1: 파일 신규 작성**

`admin-app/src/main/resources/application-prod.yml`:

```yaml
# prod profile — 프로덕션 환경 (SaaS 또는 on-prem)
#
# 활성화: SPRING_PROFILES_ACTIVE=prod
#         deployment.mode 와는 독립 — onprem 배포라면
#         + PASSKEY_DEPLOYMENT_MODE=onprem 추가
#
# 모든 시크릿과 환경 특화 값은 환경변수로 주입.

spring:
  datasource:
    url:               ${SPRING_DATASOURCE_URL:}
    driver-class-name: oracle.jdbc.OracleDriver
    username:          ${SPRING_DATASOURCE_USERNAME:}
    password:          ${SPRING_DATASOURCE_PASSWORD:}
  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST:}
      port: ${SPRING_DATA_REDIS_PORT:6379}
  flyway:
    locations: classpath:db/migration
    # prod 에서는 baseline 자동 생성 비활성 — schema 가 비정상이면 부팅
    # 실패가 정답 (의도된 fail-fast). 정상 prod schema 라면 V1+ 가 이미
    # 적용돼 있어야 한다.
    baseline-on-migrate: false
    # 이미 적용된 마이그레이션 파일의 checksum 사후 수정 차단.
    validate-on-migrate: true
    # `flyway clean` 호출 차단 — prod schema 전체 drop 사고 방지.
    clean-disabled: true

server:
  port: ${SERVER_PORT:8081}

passkey:
  key-envelope:
    master-key: ${PASSKEY_KEY_ENVELOPE_MASTER_KEY:}

logging:
  level:
    root: WARN
    com.crosscert.passkey: INFO
```

- [ ] **Step 2: 리소스 검증**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/runtime-profiles
./gradlew :admin-app:processResources
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: 부팅 실패 검증 (필수 env 미설정 시)**

```bash
env -i SPRING_PROFILES_ACTIVE=prod PATH=/usr/bin:/bin HOME=$HOME timeout 30s ./gradlew :admin-app:bootRun 2>&1 | tail -15
```

Expected: 부팅 실패 — datasource URL 빈 문자열로 Hikari 또는 Flyway 단계에서 fail.

- [ ] **Step 4: Commit**

```bash
git add admin-app/src/main/resources/application-prod.yml
git commit -m "$(cat <<'EOF'
feat(profiles): admin-app prod profile (P3.1)

프로덕션 환경. qa 와 동일 패턴 + 안전장치 강화:

* spring.flyway.baseline-on-migrate: false
  - prod schema 가 비정상(V0 미존재 등) 이면 baseline 자동 생성 대신 부팅 실패
* spring.flyway.validate-on-migrate: true
  - 이미 적용된 마이그레이션 파일을 사후 수정하면 부팅 실패 (checksum 검증)
* spring.flyway.clean-disabled: true
  - flyway clean 호출 시 예외 — prod schema 전체 drop 사고 차단
* logging.level.root: WARN (qa 의 INFO 보다 더 조용)
* logging.level.com.crosscert.passkey: INFO 유지

deployment.mode (saas/onprem) 와 완전 독립.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: 보고**

commit SHA + 부팅 실패 메시지 한 줄.

---

### Task P3.2: passkey-app prod profile 신규

**Files:**
- Create: `passkey-app/src/main/resources/application-prod.yml`

- [ ] **Step 1: 파일 신규 작성**

`passkey-app/src/main/resources/application-prod.yml`:

```yaml
# prod profile — 프로덕션 환경 (SaaS 또는 on-prem)
#
# 활성화: SPRING_PROFILES_ACTIVE=prod
#         deployment.mode 와는 독립 — onprem 배포라면
#         + PASSKEY_DEPLOYMENT_MODE=onprem 추가
#
# 모든 시크릿과 환경 특화 값은 환경변수로 주입.

spring:
  datasource:
    url:               ${SPRING_DATASOURCE_URL:}
    driver-class-name: oracle.jdbc.OracleDriver
    username:          ${SPRING_DATASOURCE_USERNAME:}
    password:          ${SPRING_DATASOURCE_PASSWORD:}
  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST:}
      port: ${SPRING_DATA_REDIS_PORT:6379}

server:
  port: ${SERVER_PORT:8080}

passkey:
  key-envelope:
    master-key: ${PASSKEY_KEY_ENVELOPE_MASTER_KEY:}
  id-token:
    issuer-base: ${PASSKEY_ID_TOKEN_ISSUER_BASE:}

logging:
  level:
    root: WARN
    com.crosscert.passkey: INFO
```

passkey-app 은 Flyway 자체가 비활성(`flyway.enabled: false` in base) 이므로 Flyway 안전장치 3종 명시 불필요. 그 외 로깅/환경변수 패턴은 admin-app prod 와 동일.

- [ ] **Step 2: 리소스 검증**

```bash
./gradlew :passkey-app:processResources
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add passkey-app/src/main/resources/application-prod.yml
git commit -m "$(cat <<'EOF'
feat(profiles): passkey-app prod profile (P3.2)

admin-app prod 와 같은 환경변수 패턴 + logging:
* server.port: 8080 기본
* passkey.id-token.issuer-base: 환경변수 필수
* root WARN, com.crosscert.passkey INFO

Flyway 안전장치는 미적용 — passkey-app 은 base 에서 spring.flyway.enabled=false
이므로 Flyway 자체가 작동하지 않는다.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: 보고**

commit SHA.

---

## Phase P4 — 문서 + 주석 갱신

### Task P4.1: test yml 주석에서 local-shared 표현 갱신

**Files:**
- Modify: `admin-app/src/test/resources/application-test.yml`
- Modify: `passkey-app/src/test/resources/application-test.yml`

- [ ] **Step 1: 두 파일에서 `local-shared` 언급 찾기**

```bash
grep -n "local-shared" \
  admin-app/src/test/resources/application-test.yml \
  passkey-app/src/test/resources/application-test.yml
```

Expected:
- `admin-app/.../application-test.yml:25:    # Test profile master key (32 zero bytes b64). Matches local-shared`
- `passkey-app/.../application-test.yml:47:    # local-shared default; production uses PASSKEY_KEY_ENVELOPE_MASTER_KEY.`

- [ ] **Step 2: admin-app/src/test/resources/application-test.yml 의 주석 수정**

기존 `# Test profile master key (32 zero bytes b64). Matches local-shared` 줄을 다음으로 변경:

```yaml
    # Test profile master key (32 zero bytes b64). Matches the dev profile
```

(다음 줄의 `# default; production uses PASSKEY_KEY_ENVELOPE_MASTER_KEY.` 는 그대로 유지.)

- [ ] **Step 3: passkey-app/src/test/resources/application-test.yml 의 주석 수정**

기존 `# local-shared default; production uses PASSKEY_KEY_ENVELOPE_MASTER_KEY.` 줄을 다음으로 변경:

```yaml
    # dev profile default; production uses PASSKEY_KEY_ENVELOPE_MASTER_KEY.
```

- [ ] **Step 4: 리소스 검증 + grep 재확인**

```bash
./gradlew :admin-app:processTestResources :passkey-app:processTestResources
grep -rn "local-shared" admin-app/src/test passkey-app/src/test
```

Expected: BUILD SUCCESSFUL, grep 결과 0 줄.

- [ ] **Step 5: Commit**

```bash
git add admin-app/src/test/resources/application-test.yml \
        passkey-app/src/test/resources/application-test.yml
git commit -m "$(cat <<'EOF'
docs(test): test yml 주석에서 local-shared → dev profile 표현 갱신 (P4.1)

local-shared 파일이 제거됐으므로 test yml 의 주석도 dev profile 을
참조하도록 갱신. 동작 변경 없음 — 주석만 정정.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 6: 보고**

commit SHA.

---

### Task P4.2: docs/dev-setup.md 갱신

**Files:**
- Modify: `docs/dev-setup.md`

- [ ] **Step 1: 현재 dev-setup.md 의 profile 관련 부분 grep**

```bash
grep -n "SPRING_PROFILES_ACTIVE\|local\|profile" docs/dev-setup.md | head -30
```

기존 dev-setup.md 는 이미 `SPRING_PROFILES_ACTIVE=dev` 를 권장하고 있어 큰 변경 불필요. 다음 두 가지만 갱신:

1. **section 2 "dev profile 이 제공하는 것"** 의 첫 문장 — local/local-shared 가 제거됐다는 점 명시
2. **section 끝** 의 변경 이력 - profile 재구성 commit 언급 (선택)

- [ ] **Step 2: section 2 첫 문장 강화**

`docs/dev-setup.md` 의 section "## 2. dev profile 이 제공하는 것" 바로 다음 줄 (기존 `**SPRING_PROFILES_ACTIVE=dev** ...` 시작 문단) 위에 다음 문단을 추가:

```markdown
> **2026-05 변경**: 기존 `local` / `local-shared` 프로필이 dev 로 흡수되어
> 제거됐다. `SPRING_PROFILES_ACTIVE=local` 또는 `local,dev` 를 쓰던 명령은
> `SPRING_PROFILES_ACTIVE=dev` 단독으로 변경. dev 가 datasource URL,
> credential, Redis host, dev key-envelope master 를 모두 self-contained
> 로 제공한다.
```

- [ ] **Step 3: 변경 검증 + commit**

```bash
git add docs/dev-setup.md
git commit -m "$(cat <<'EOF'
docs(dev-setup): local/local-shared 제거 안내 추가 (P4.2)

section 2 첫머리에 2026-05 변경사항으로 local 프로필 제거 + dev 통합을
명시. 기존 SPRING_PROFILES_ACTIVE=local 사용자가 보면 즉시 인지 가능.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: 보고**

commit SHA.

---

### Task P4.3: README + onprem-deployment 갱신

**Files:**
- Modify: `README.md`
- Modify: `docs/onprem-deployment.md`

- [ ] **Step 1: README 의 시작하기 섹션 갱신**

`README.md` 의 `### 2) 3 서버 기동 (dev profile)` 섹션 헤더 위에 다음 짧은 단락 추가 (또는 기존 단락 보완):

```markdown
### 프로필

플랫폼 운영을 위해 세 가지 Spring 프로필이 정의되어 있다:

| Profile | 용도 | 시크릿 |
|---|---|---|
| `dev` | 로컬 개발 (localhost Oracle/Redis + 시드 데이터) | yml 하드코딩 (dev 전용) |
| `qa` | 내부 QA / 통합 테스트 | 환경변수 필수 |
| `prod` | 프로덕션 (SaaS 또는 on-prem) | 환경변수 필수 + Flyway 안전장치 |

`deployment.mode` (saas/onprem) 는 프로필과 독립적인 별도 옵션
(`PASSKEY_DEPLOYMENT_MODE` 환경변수, [docs/onprem-deployment.md](docs/onprem-deployment.md) 참고).
```

위치: 기존 `## 시작하기` 의 `### 2) 3 서버 기동 (dev profile)` 바로 위.

- [ ] **Step 2: docs/onprem-deployment.md 의 env 매트릭스 보강**

`docs/onprem-deployment.md` 의 `## 부팅 환경변수` 섹션의 표:

기존:
```
| `SPRING_PROFILES_ACTIVE` | `prod,onprem` 권장 | — |
```

다음으로 변경:
```
| `SPRING_PROFILES_ACTIVE` | `prod` (필수, 신규 정의됨) | — |
| `PASSKEY_DEPLOYMENT_MODE` | `onprem` (이 가이드 적용 시) | `saas` |
```

(`PASSKEY_DEPLOYMENT_MODE` 가 이미 다른 줄에 있다면 중복 추가하지 말 것 — 기존 표 구조 확인 후 적절히 통합.)

또한 onprem-deployment.md 본문에 `prod,onprem` 같은 콤마 조합 활용 예시가 있다면 `prod` 단독 + 별도 환경변수로 변경 (deployment.mode 와 profile 이 독립이라는 spec 결정 반영).

- [ ] **Step 3: 검증 + commit**

```bash
git add README.md docs/onprem-deployment.md
git commit -m "$(cat <<'EOF'
docs: dev/qa/prod 프로필 + deployment.mode 분리 안내 (P4.3)

* README — '프로필' 표 추가 (3개 프로필 용도 + 시크릿 처리 방식)
* docs/onprem-deployment.md — 부팅 환경변수 표에서 SPRING_PROFILES_ACTIVE
  와 PASSKEY_DEPLOYMENT_MODE 가 독립적임을 명시. prod 가 실제로 정의됐음.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: 보고**

commit SHA.

---

## Phase P5 — 부팅 smoke 검증 + 최종 build

### Task P5.1: 전체 build 검증

**Files:** (없음 — 검증만)

- [ ] **Step 1: clean build (test 포함)**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/runtime-profiles
./gradlew clean build 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. 기존 테스트 회귀 없음 — application-test.yml 의 주석만 갱신했으므로 동작 변화 없음.

- [ ] **Step 2: 결과 보고**

성공 시 단순 BUILD SUCCESSFUL 보고. 실패 시 실패 task + 메시지를 plan 의 P1~P4 중 어디서 비롯됐는지 분석해서 보고 (이 단계는 별도 commit 없음).

---

### Task P5.2: dev 부팅 smoke (수동, docker compose 가용 시만)

**Files:** (없음 — 부팅 검증)

- [ ] **Step 1: docker compose 인프라 가동 확인**

```bash
docker ps --format "{{.Names}}" | grep -E "passkey-oracle|passkey-redis"
```

Expected: 두 컨테이너 모두 running. 없으면 `docker compose up -d` 후 30초 대기.

- [ ] **Step 2: admin-app dev 부팅 smoke**

```bash
SPRING_PROFILES_ACTIVE=dev timeout 90s ./gradlew :admin-app:bootRun 2>&1 | tee /tmp/admin-dev-boot.log | tail -20
```

Expected: `Started AdminApplication in N seconds` 로그가 떠야 함. Flyway V1~V34 migration + R__dev_seed 적용 메시지 보이면 성공.

90초 timeout 이라 자동 종료. Ctrl+C 없이 자동 끝남.

- [ ] **Step 3: 부팅 실패 시 분석**

타임아웃 전에 부팅 실패하면 `/tmp/admin-dev-boot.log` 끝 30 줄을 보고. P1 의 dev yml 변경에서 문제 있을 가능성.

- [ ] **Step 4: passkey-app dev 부팅 smoke (선택)**

선택 사항이지만 가능하면:

```bash
SPRING_PROFILES_ACTIVE=dev timeout 60s ./gradlew :passkey-app:bootRun \
  --args="--passkey.id-token.issuer-base=http://localhost:8080" 2>&1 | tail -15
```

Expected: `Started PasskeyApplication`.

- [ ] **Step 5: 보고**

성공/실패 + 핵심 로그 라인. 이 task 는 별도 commit 없음.

---

### Task P5.3: qa/prod 환경변수 미설정 부팅 실패 검증

**Files:** (없음 — 부팅 실패 검증)

이미 P2.1, P2.2, P3.1, P3.2 에서 각각 부팅 실패를 확인했지만 마지막 round-trip 으로 한 번 더.

- [ ] **Step 1: admin-app qa — env 비움**

```bash
env -i PATH=/usr/bin:/bin HOME=$HOME SPRING_PROFILES_ACTIVE=qa \
  timeout 30s ./gradlew :admin-app:bootRun 2>&1 | tail -15
```

Expected: 부팅 실패 — Hikari connection 실패 또는 Flyway schema 식별 실패.

- [ ] **Step 2: admin-app prod — env 비움**

```bash
env -i PATH=/usr/bin:/bin HOME=$HOME SPRING_PROFILES_ACTIVE=prod \
  timeout 30s ./gradlew :admin-app:bootRun 2>&1 | tail -15
```

Expected: 부팅 실패.

- [ ] **Step 3: 보고**

각 케이스의 실패 메시지 한 줄. 이 task 는 별도 commit 없음.

---

## Self-Review (writer)

**Spec coverage check:**

| Spec 결정 | 구현 task |
|---|---|
| 3 프로필 (dev/qa/prod) | P1 (dev) + P2 (qa) + P3 (prod) |
| local + local-shared 제거 | P1.3 (삭제) + P1.4 (commit) |
| 시크릿 100% 환경변수 | P2.1 / P2.2 / P3.1 / P3.2 의 yml 내용 |
| 환경별 차이 소폭 | qa/prod 의 logging level + prod 의 Flyway 안전장치만 |
| sample-rp 변경 없음 | plan 에서 sample-rp 미언급 (의도) |
| deployment.mode 와 독립 | prod yml 주석 + P4.3 onprem-deployment.md 갱신 |
| README / dev-setup / onprem-deployment 갱신 | P4.2, P4.3 |
| application-test.yml 주석 갱신 | P4.1 |
| 부팅 smoke 검증 | P5.1 / P5.2 / P5.3 |

**Placeholder scan:** 모든 step 에 실제 yml 내용 / 명령 / 기대 결과가 들어 있음. "TBD" 없음.

**Type consistency:**
- 환경변수 이름 (`SPRING_DATASOURCE_URL`, `PASSKEY_KEY_ENVELOPE_MASTER_KEY`, `PASSKEY_ID_TOKEN_ISSUER_BASE` 등) — P2/P3 의 admin-app/passkey-app 양쪽에서 일관.
- 포트 (`8081` admin, `8080` passkey) — base + qa + prod 일관.
- Flyway 안전장치 3종 — P3.1 에만 명시 (admin-app 만 Flyway 사용).

---

**Spec link:** `docs/superpowers/specs/2026-05-29-runtime-profiles-design.md`
**Worktree:** `.claude/worktrees/runtime-profiles` (branch `worktree-runtime-profiles`)
**Total tasks:** 11 (P1: 4, P2: 2, P3: 2, P4: 3, P5: 3) — 약 7~10 commit 예상.
