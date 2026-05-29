# Runtime Profiles (dev / qa / prod) Design Spec

**Date**: 2026-05-29
**Status**: Draft (awaiting user review)
**Scope**: admin-app + passkey-app 의 Spring Boot 프로필을 dev / qa / prod 로 명시적으로 분리
**Out of scope**: sample-rp (고객 RP 데모, 환경변수 패턴 유지), CI 파이프라인 변경, 시크릿 관리 시스템 통합

---

## 배경

현재 admin-app/passkey-app 은 `local` / `dev` 두 프로필만 갖고 있고 prod 프로필이 존재하지 않는다. 운영 가이드(`docs/onprem-deployment.md`, README) 는 `SPRING_PROFILES_ACTIVE=prod` 를 가정하지만 해당 yml 파일이 없어서 실제로는 base + common 만 적용되며 운영자가 모든 시크릿/환경 특화 값을 환경변수로 주입해야 한다 — 가이드와 코드가 어긋난 상태.

또한 `local` 과 `local-shared` 의 분리는 Spring Boot 의 ClassLoader 단일 리소스 제약을 우회하기 위한 history 였지만 dev 프로필이 거의 동일한 역할을 하므로 중복이다.

---

## 결정 사항 요약

| 항목 | 결정 |
|---|---|
| 새 프로필 | `dev` / `qa` / `prod` (3개) |
| 기존 `local` / `local-shared` | 제거. `dev` 가 흡수 |
| 적용 모듈 | admin-app + passkey-app |
| sample-rp | 변경 없음 (기존 환경변수 패턴 유지) |
| 시크릿 주입 | 100% 환경변수 (`${VAR:}` placeholder, 빈 default = 미설정 시 부팅 실패) |
| 환경 특화 수준 | 소폭 (계정·logging·Flyway 안전장치 위주) |
| `deployment.mode` 와 관계 | 완전 독립 (`SPRING_PROFILES_ACTIVE` ⊥ `PASSKEY_DEPLOYMENT_MODE`) |

---

## 1. 파일 구조

### Before

```
core/src/main/resources/
├── application-common.yml
└── application-local-shared.yml             # 제거 대상

admin-app/src/main/resources/
├── application.yml                          # base 유지
├── application-dev.yml                      # 변경
└── application-local.yml                    # 제거 대상

passkey-app/src/main/resources/
├── application.yml                          # base 유지
├── application-dev.yml                      # 변경
└── application-local.yml                    # 제거 대상
```

### After

```
core/src/main/resources/
└── application-common.yml                   # 변경 없음

admin-app/src/main/resources/
├── application.yml                          # 변경 없음
├── application-dev.yml                      # local + local-shared 흡수
├── application-qa.yml                       # 신규
└── application-prod.yml                     # 신규

passkey-app/src/main/resources/
├── application.yml                          # 변경 없음
├── application-dev.yml                      # local + local-shared 흡수
├── application-qa.yml                       # 신규
└── application-prod.yml                     # 신규
```

### 제거되는 파일

- `core/src/main/resources/application-local-shared.yml`
- `admin-app/src/main/resources/application-local.yml`
- `passkey-app/src/main/resources/application-local.yml`

### 호환성 (Breaking)

`SPRING_PROFILES_ACTIVE=local` 또는 `local,dev` 를 쓰던 기존 명령은 깨진다. README / docs/dev-setup.md / IDE Run Configuration 을 함께 갱신해 `dev` 단독 사용으로 안내한다.

---

## 2. application-dev.yml — 로컬 개발자 환경

기존 `local` + `local-shared` + `dev` 의 모든 dev 설정을 한 파일에 흡수. `spring.config.import` chain 없이 self-contained.

### admin-app/src/main/resources/application-dev.yml

```yaml
# dev profile — 로컬 개발자 환경
#
# 활성화: SPRING_PROFILES_ACTIVE=dev
#
# 포함: localhost Oracle/Redis + APP_ADMIN_USER credential + dev key-envelope
#       master + DEBUG 로깅 + db/dev 시드 (R__dev_seed.sql)

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
    master-key: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

logging:
  level:
    com.crosscert.passkey: DEBUG
    org.hibernate.SQL: DEBUG
```

### passkey-app/src/main/resources/application-dev.yml

```yaml
# dev profile — 로컬 개발자 환경
#
# 활성화: SPRING_PROFILES_ACTIVE=dev ./gradlew :passkey-app:bootRun \
#         --args="--passkey.id-token.issuer-base=http://localhost:8080"
#
# 포함: localhost Oracle/Redis + APP_RUNTIME_USER credential + dev key-envelope
#       master + DEBUG 로깅

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
    master-key: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

logging:
  level:
    com.crosscert.passkey: DEBUG
    org.hibernate.SQL: DEBUG
```

---

## 3. application-qa.yml (신규) — 내부 QA 환경

모든 값을 환경변수로 받음. 빈 default (`${VAR:}`) 는 운영자가 값을 설정해야 함을 표시.

### admin-app/src/main/resources/application-qa.yml

```yaml
# qa profile — 내부 QA / 통합 테스트 환경
#
# 활성화: SPRING_PROFILES_ACTIVE=qa
#
# 모든 시크릿과 환경 특화 값은 환경변수로 주입. 빈 default (${VAR:}) 는
# 운영자가 반드시 값을 설정해야 함을 표시.

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

server:
  port: ${SERVER_PORT:8081}

passkey:
  key-envelope:
    master-key: ${PASSKEY_KEY_ENVELOPE_MASTER_KEY:}

logging:
  level:
    com.crosscert.passkey: INFO
```

### passkey-app/src/main/resources/application-qa.yml

```yaml
# qa profile — 내부 QA / 통합 테스트 환경

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

### qa 의 특징

- `datasource url/username/password` — 빈 default, 미설정 시 부팅 실패
- `redis port` — 6379 기본 (대부분 환경 동일)
- `server.port` — 모듈 기본값(8080/8081) 가능, 환경변수로 override
- `key-envelope master-key` — 필수, dev 의 zero-bytes 와 달리 진짜 키 주입
- `passkey-app issuer-base` — passkey-app 만 필요
- `logging` — INFO (dev DEBUG 와 prod WARN 의 중간)
- `Flyway db/dev` 시드 미포함

---

## 4. application-prod.yml (신규) — 프로덕션 환경

qa 와 거의 동일하되 안전장치 강화.

### admin-app/src/main/resources/application-prod.yml

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
    # prod 에서는 baseline 자동 생성 비활성 — schema 가 비정상이면 부팅 실패가 정답
    baseline-on-migrate: false
    # 이미 적용된 마이그레이션 파일이 사후 수정되면 실패
    validate-on-migrate: true
    # `flyway clean` 으로 prod schema drop 차단
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

### passkey-app/src/main/resources/application-prod.yml

```yaml
# prod profile — 프로덕션 환경 (SaaS 또는 on-prem)

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

### prod vs qa 차이점

| 항목 | qa | prod |
|---|---|---|
| `spring.flyway.baseline-on-migrate` | (base 의 `true` 그대로) | **`false`** |
| `spring.flyway.validate-on-migrate` | (Flyway default `true`) | **명시적 `true`** |
| `spring.flyway.clean-disabled` | (Flyway default `false`) | **`true`** |
| `logging.level.root` | (common 의 `INFO`) | **`WARN`** |
| `logging.level.com.crosscert.passkey` | `INFO` | `INFO` |
| 그 외 datasource/redis/key-envelope/issuer-base | 환경변수 | 환경변수 (동일) |

### Flyway 안전장치 의미

- **`baseline-on-migrate: false`** — prod schema 에서 V0 자동 생성 안 함. 이미 V1+ 가 적용돼 있어야 함. 비정상 schema 면 시작 실패 (의도된 fail-fast).
- **`validate-on-migrate: true`** — 적용된 migration 파일의 checksum 검증. 누군가 V13 을 사후 수정하면 부팅 실패.
- **`clean-disabled: true`** — `flyway clean` 호출 시 예외. prod 에서 schema 전체 drop 사고 방지.

---

## 5. 환경변수 매트릭스

| 환경변수 | dev | qa | prod | 설명 |
|---|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` | `qa` | `prod` | 프로필 활성화 |
| `SPRING_DATASOURCE_URL` | (yml 하드코딩) | **필수** | **필수** | Oracle JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | (yml 하드코딩) | **필수** | **필수** | DB 계정 |
| `SPRING_DATASOURCE_PASSWORD` | (yml 하드코딩) | **필수** | **필수** | DB 비밀번호 |
| `SPRING_DATA_REDIS_HOST` | (yml 하드코딩) | **필수** | **필수** | Redis 호스트 |
| `SPRING_DATA_REDIS_PORT` | (yml 하드코딩) | 6379 기본 | 6379 기본 | Redis 포트 |
| `SERVER_PORT` | (모듈 기본) | 모듈 기본 | 모듈 기본 | 서비스 포트 |
| `PASSKEY_KEY_ENVELOPE_MASTER_KEY` | (yml 하드코딩 zero-bytes) | **필수** | **필수** | 키 봉투 마스터 (base64 32 bytes) |
| `PASSKEY_ID_TOKEN_ISSUER_BASE` | CLI args 권장 | **필수** (passkey-app) | **필수** (passkey-app) | JWT iss prefix |
| `PASSKEY_DEPLOYMENT_MODE` | (saas 기본) | (saas 기본) | (saas 또는 onprem) | 프로필과 독립 |

---

## 6. 호환성 + 마이그레이션

### Breaking changes

- `SPRING_PROFILES_ACTIVE=local` → 깨짐 → `dev` 로 변경
- `SPRING_PROFILES_ACTIVE=local,dev` → 깨짐 → `dev` 단독
- `core/application-local-shared.yml` import 하던 외부 도구 → 깨짐 (사례 없을 것으로 추정, 확인 필요)

### 업데이트할 문서

- `README.md` — 시작하기 섹션의 프로필 설명 갱신
- `docs/onprem-deployment.md` — `SPRING_PROFILES_ACTIVE=prod` 가 실제 동작함을 명시
- `docs/dev-setup.md` — 신규 입사자 가이드, `local` → `dev` 마이그레이션

### 마이그레이션 영향 없는 항목

- 테스트 (`application-test.yml`) — 그대로 유지
- `core/application-common.yml` — 변경 없음
- sample-rp — 변경 없음

---

## 7. 테스트 전략

기존 테스트 영향 평가 위주 — 별도 신규 테스트 불필요.

### 검증할 것

1. **dev 프로필 부팅 smoke**: `SPRING_PROFILES_ACTIVE=dev ./gradlew :admin-app:bootRun` — Oracle/Redis 컨테이너만 떠 있으면 부팅 성공해야 함
2. **qa 프로필 부팅 (환경변수 미설정)**: `SPRING_PROFILES_ACTIVE=qa ./gradlew :admin-app:bootRun` — datasource URL 비어있어 의도된 부팅 실패
3. **qa 프로필 부팅 (환경변수 설정)**: 4개 필수 env var 주입 후 정상 부팅
4. **prod 프로필 부팅 (Flyway 안전장치)**: prod 의 `baseline-on-migrate: false` 가 빈 schema 에서 실패하는지 — 정상 동작 검증
5. **기존 unit/integration 테스트** — `application-test.yml` 기반이라 영향 없음. 전부 통과해야 함

### Smoke test 스크립트

`scripts/smoke-profiles.sh` (신규 — 선택 사항):

```bash
#!/usr/bin/env bash
set -euo pipefail

# dev — 기존 docker compose 인프라 가정
SPRING_PROFILES_ACTIVE=dev timeout 60s ./gradlew :admin-app:bootRun &
PID=$!
sleep 30
curl -fs http://localhost:8081/actuator/health || (kill $PID; exit 1)
kill $PID

# qa — 필수 env var 누락 시 실패 확인
SPRING_PROFILES_ACTIVE=qa timeout 30s ./gradlew :admin-app:bootRun && exit 1 || echo "expected fail OK"
```

(선택 사항이라 plan 에서 포함 여부 결정)

---

## 8. 구현 단계

1. **P1: dev 통합** — local / local-shared 제거 + 각 모듈 application-dev.yml 갱신
2. **P2: qa 신규** — admin-app + passkey-app 의 application-qa.yml 신규
3. **P3: prod 신규** — admin-app + passkey-app 의 application-prod.yml 신규 (Flyway 안전장치 포함)
4. **P4: docs 갱신** — README, onprem-deployment.md, dev-setup.md (있으면) 의 프로필 가이드 갱신
5. **P5: 부팅 smoke 검증** — dev / qa (실패 확인) / qa (성공) 수동 또는 스크립트

P1~P3 는 순차 (파일 변경이 작아 한 commit 에 묶어도 OK), P4~P5 는 P1~P3 완료 후 병렬 가능.

예상 commit 수: 4~6

---

## Open Questions (구현 중 결정)

- `application-test.yml` 이 `local-shared` 를 import 하고 있는가? → 확인 후 import 끊거나 test.yml 에 흡수
- IDE Run Configuration 파일 (.idea 또는 .vscode) 이 git 에 있어 `local` 을 가리키는가? → 확인 후 갱신
- `dev-setup.md` 가 있는가? → 있으면 갱신, 없으면 README 만

---

## 다음 단계

이 spec 의 사용자 리뷰가 완료되면 `writing-plans` 스킬로 implementation plan 작성.
