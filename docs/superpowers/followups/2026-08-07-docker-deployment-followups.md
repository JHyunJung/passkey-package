# Docker 배포 — Task 8 검증 결과 및 후속 조치

- 작성일: 2026-08-07
- 관련 스펙: `docs/superpowers/specs/2026-08-07-docker-deployment-design.md`
- 관련 계획: `docs/superpowers/plans/2026-08-07-docker-deployment.md`
- 브랜치: `worktree-docker-deployment`

## 요약 — Task 1~9 전부 완료, 실기동 검증 통과

Docker 데몬 장애로 한동안 막혔으나 복구 후 **Task 8 통합 검증을 완주했다.**

**실기동으로 확인한 것:**

| 항목 | 결과 |
|---|---|
| 이미지 빌드 3종 | ✅ passkey-app 434MB / admin-app 444MB / rp-app 383MB |
| 비루트 uid 1001 | ✅ 3개 모두 |
| SIGTERM 전달(graceful) | ✅ `docker stop` **0초** — `exec` 동작 |
| prod fail-fast | ✅ env 없이 부팅 실패 |
| admin-ui jar 번들 | ✅ 이미지 안 jar 에서 `static/admin` 7개 자산 |
| 스택 기동(proxy,admin) | ✅ 3 컨테이너 healthy |
| nginx 경유 요청 | ✅ `/actuator/health` 200 (DB UP) |
| admin-ui 서빙 | ✅ `/admin/` 200 |
| **`--scale passkey-app=3`** | ✅ **포트 충돌 없이 3개 healthy**, Docker DNS 가 3 IP 응답 |
| QA 구성(rp-app 포함) | ✅ 4 서비스 running |
| Redis 인증 강제 | ✅ 비인증 `NOAUTH`, 인증 `PONG`, healthcheck healthy |

**검증 중 고친 실제 결함 3건**(아래 상세): `.dockerignore` 의 모듈 하위
`.gradle/` 미제외, rp-app `RP_RELAY_SECRET` 누락, Redis 호스트 포트 하드코딩.

**후속 조치 완료**: 빈 스키마에서 passkey-app 이 부팅 실패하던 건
(`Schema-validation: missing table [security_incident]`)은 **V4 마이그레이션으로
해소**했다 — `GRANT SELECT ON security_incident TO PSK_APP_RUNTIME`.

해법 선택 근거: **"DB 스키마가 완전해야 앱이 뜬다"는 성질을 유지**하는 쪽으로
정했다. 대안이던 엔티티 스캔 축소는 그 테이블이 실제로 없어도 passkey-app 이
뜨게 만들고, `ddl-auto: none` 은 스키마 드리프트 감지를 통째로 잃는다.
GRANT 는 검증이 실제 스키마를 확인한 뒤 통과하게 한다. 권한은 SELECT 만 부여
(passkey-app 은 읽지도 쓰지도 않으며 메타데이터 조회만 필요).

## 검증 완료 (실측으로 확인함)

| 항목 | 방법 |
|---|---|
| 시크릿 차단 — git 방어선 | `git check-ignore` / `git add --dry-run` 으로 7형태 확인 |
| 시크릿 차단 — Docker 방어선 | 컨텍스트 이미지 안에서 6형태 부재 확인 |
| 배포 자산 커밋 가능 | `deploy/` 5종 stage 가능, `deploy/*.jar` 는 무시 유지 |
| 빌드 컨텍스트 무결성 | 빌드 필수 파일 18종 존재 확인 |
| nginx 지연 해석 | 백엔드 컨테이너 0개 상태에서 `nginx -t` 성공 |
| nginx 헤더 처리 | envsubst 후 `X-Forwarded-Proto $http_x_forwarded_proto` 확인 |
| compose profile 격리 | 4개 조합 전부 확인 — rp-app 은 `qa` 에서만 등장 |
| passkey-app 포트 비노출 | `--scale` 충돌 조건 부재 확인 |
| Flyway 분리 | admin-app 에만 주입, passkey-app 에 없음 확인 |
| admin-app fail-closed 바인딩 | 변수 미설정 시에도 `host_ip: 127.0.0.1` 확인 |
| Redis fail-closed | 필수 변수 2개 각각 누락 시 거부 확인 |

## 부분 검증 — 호스트 Gradle 빌드로 확인함 (2026-08-07 추가)

Docker 데몬이 계속 죽어 있어, 이미지 빌드의 핵심인 `bootJar` 를 **호스트에서 직접**
실행해 검증했다. 이미지 빌드 실패 원인이 앱 코드/Gradle 쪽일 가능성은 배제된다.

| 항목 | 결과 |
|---|---|
| `:passkey-app:bootJar` | ✅ 성공 → `deploy/passkey-app.jar` 78M |
| `:rp-app:bootJar` | ✅ 성공 → `deploy/rp-app.jar` 29M |
| `:admin-app:bootJar` | ✅ 성공 → `deploy/admin-app.jar` 87M (BUILD SUCCESSFUL in 21s) |
| **admin-ui jar 번들** | ✅ **7건 확인** — `BOOT-INF/classes/static/admin/` 아래 index.html, assets/index-*.js(412KB), assets/index-*.css(16KB), favicon.png, 로고 |
| jar 이름·위치가 Dockerfile `COPY --from=build` 경로와 일치 | ✅ 세 모듈 모두 `deploy/<module>.jar` |

즉 **"앱이 빌드되는가"는 해결됐고, 남은 미검증은 "컨테이너로 잘 감싸지는가"로 좁혀졌다.**
빌드 경고 2건(deprecated API — `PasskeyResponseErrorHandler`, `RequestLoggingFilter`)은
기존 코드 이슈로 이 작업과 무관하다.

## Task 8 검증 완료 항목 (실기동으로 확인)

아래는 모두 **실제로 실행해 확인**했다. 재검증이 필요하면 같은 절차를 쓴다.

### 1. 이미지 빌드 3종

```bash
docker build -t passkey-app:0.0.1-SNAPSHOT -f passkey-app/Dockerfile .
docker build -t admin-app:0.0.1-SNAPSHOT   -f admin-app/Dockerfile .
docker build -t rp-app:0.0.1-SNAPSHOT      -f rp-app/Dockerfile .
```
- [x] 세 이미지 빌드 성공 (434MB / 444MB / 383MB)
- [x] 런타임 uid **1001** (`docker run --rm --entrypoint id <img> -u`)
- [x] jar 존재 (79M / 88M / 30M)
- [x] **admin-ui 번들 7건** — 이미지에서 jar 를 꺼내 확인:
      `docker create` → `docker cp :/app/app.jar` → `unzip -l | grep static/admin`
      (JRE 이미지에는 unzip/jar/python3 이 없어 컨테이너 안에서는 확인 불가)

**전제:** 빌드 전에 `docker pull eclipse-temurin:17-jre` 를 따로 실행해 캐시에
올려둘 것(아래 환경 함정 ① 참고).

### 2. graceful shutdown (SIGTERM 전달)

- [x] `docker stop` 이 **0초**에 완료 — `exec` 가 동작해 SIGTERM 이 JVM 에 직접
      전달된다. 로그에도 `Commencing graceful shutdown` 이 찍힌다.
      (`exec` 가 없으면 sh 가 PID 1 이 되어 10초 후 SIGKILL 된다.)

### 3. prod 프로필 fail-fast

- [x] env 미주입 시 부팅 실패 확인 (`DataSource: not ... Error ... Stopping`)

### 4. Redis 실기동

```bash
export REDIS_BIND_ADDR=127.0.0.1 REDIS_PASSWORD=<pw> REDIS_HOST_PORT=16379
docker compose -f docker-compose.redis.yml up -d
```
- [x] 비인증 `redis-cli ping` → **`NOAUTH Authentication required.`**
- [x] 인증 `redis-cli -a <pw> ping` → **`PONG`**
- [x] healthcheck → **healthy** (실제 상태 반영)

### 5. 전체 스택 기동 + 스케일

- [x] `COMPOSE_PROFILES=proxy,admin up -d` → 3 컨테이너 healthy
- [x] admin-app Flyway 마이그레이션 완료 (`Started AdminApplication in 7.3s`)
- [x] nginx 경유 `curl -H "Host: localhost" :18080/actuator/health` → **200**
      (본문에 `"status":"UP"`, db Oracle UP)
- [x] `curl :8081/admin/` → **200** (admin-ui 서빙)
- [x] **`--scale passkey-app=3` 포트 충돌 없이 3개 healthy**
- [x] Docker DNS 가 `passkey-app` 을 **3개 IP 로 응답**(172.23.0.3/5/6) —
      nginx 의 resolver+변수 방식이 이걸 받아 요청마다 분산한다
- [x] 3개 인스턴스 각각 직접 호출 시 200
- [x] `COMPOSE_PROFILES=proxy,admin,qa` → **rp-app 포함 4 서비스 running**

### 6. 회귀 가드

- [x] 세 Dockerfile 에 `# syntax=` 지시자 없음 (`grep -c "syntax=" → 0`)

## Task 8 실행 중 발견 (2026-08-07)

### 실제 결함 1건 — `.dockerignore` 모듈 하위 `.gradle/` 미제외

admin-app 이미지 빌드가 실패했다:
```
Execution failed for task ':admin-app:nodeSetup'.
> Couldn't follow symbolic link
  '/src/admin-app/.gradle/nodejs/node-v18.20.0-darwin-arm64/bin/npx'
```
기존 규칙이 루트 `.gradle/` 만 제외해, 호스트(macOS)에서 `bootJar` 를 돌릴 때
생긴 **darwin-arm64 Node 바이너리**가 컨텍스트에 실려 리눅스 컨테이너로 들어갔다.
Gradle 이 "이미 받았다"고 판단해 재사용하려다 깨진 심볼릭 링크에서 실패한다.
`**/.gradle/` 로 일반화해 수정(커밋 `952e1c69`).

**파일 검토·문법 검증으로는 드러나지 않는 결함이었다.** 실제 빌드가 잡았다.

### 실제 결함 2건 — rp-app `RP_RELAY_SECRET` 누락 / Redis 포트 하드코딩

**① rp-app 이 부팅을 거부했다:**
```
IllegalStateException: rp.relay.secret 이 데모 기본 키입니다.
운영(또는 프로필 미지정) 환경에서는 RP_RELAY_SECRET 로 강한 키를 주입하세요.
```
등록 relay 토큰 HMAC 키(`rp-app/src/main/resources/application.yml:39`)가
compose 와 `.env.example` 양쪽에 누락돼 있었다. **QA 에서 rp-app 을 띄우려면
반드시 필요한 변수**다. 추가 후 정상 기동 확인(커밋 `559466d7`).

**② Redis compose 의 호스트 포트가 6379 하드코딩**이라 그 포트가 이미 쓰이는
환경에서 `port is already allocated` 로 기동 실패했다. `REDIS_HOST_PORT` 로
변수화(기본 6379 유지). 전용 호스트에서는 기본값으로 충분하지만, 검증 환경이나
한 호스트에 임시로 올릴 때 필요하다.

### 🔴 기존 결함 — passkey-app 이 안 쓰는 엔티티까지 스키마 검증한다

**이 작업(컨테이너화) 범위 밖의 기존 문제이며, 배포 전 반드시 수정해야 한다.**

`V1__baseline_schema.sql` 의 GRANT 섹션(123건)에서 두 테이블이 `PSK_APP_RUNTIME` 에
누락돼 있다:

| 테이블 | PSK_APP_ADMIN | PSK_APP_RUNTIME |
|---|---|---|
| `security_incident` | ✅ SELECT/INSERT/UPDATE | ❌ **없음** |
| `mds_sync_history` | ✅ (seq 는 RUNTIME 에 있음) | ❌ **없음** |

`mds_sync_history_seq` 는 `PSK_APP_RUNTIME` 에 GRANT 돼 있는데 **정작 테이블은 빠져
있다** — 시퀀스만 주고 테이블을 안 준 형태라 단순 누락으로 보인다.

**증상:** passkey-app 이 `PSK_APP_RUNTIME_USER` 로 접속하고 `hibernate.ddl-auto: validate`
(`core/src/main/resources/application-common.yml:31`)를 쓰므로, 엔티티는 `core` 에
있어 스캔되는데 권한이 없어 테이블이 안 보인다:
```
Schema-validation: missing table [security_incident]
```
**부팅이 아예 실패한다.**

**왜 지금까지 안 드러났나(추정):** 로컬 dev 는 볼륨을 재생성하지 않고 오래 써온
DB 를 쓰거나, `PSK_APP_OWNER` 로 접속해 admin-app 만 띄우는 흐름이 많았을 수 있다.
**빈 스키마 + `PSK_APP_RUNTIME_USER` + prod/dev 조합에서는 재현된다**(이번에 재현함).
dev 프로필도 같은 유저·같은 validate 설정을 쓰므로 동일하게 실패할 것이다.

**임시 조치(검증용으로만 적용함):**
```sql
GRANT SELECT, INSERT ON security_incident TO PSK_APP_RUNTIME;
GRANT SELECT, INSERT ON mds_sync_history  TO PSK_APP_RUNTIME;
```

**추가 조사 결과 — 실제 문제는 엔티티 1개뿐이고, GRANT 추가는 잘못된 해법이다.**

먼저 범위를 정정한다. 위 표는 권한 조사 쿼리 결과라 테이블 2개를 나열했지만,
**스키마 검증에 실제로 걸리는 것은 `security_incident` 하나다:**

| 테이블 | JPA 엔티티 | 스키마 검증 대상 |
|---|---|---|
| `security_incident` | ✅ `core/entity/SecurityIncident.java` | **예 — 부팅 실패 원인** |
| `mds_sync_history` | ❌ 없음 (raw-JDBC 전용, `MdsHistoryService`) | 아니오 |

`mds_sync_history` 는 엔티티가 없어 Hibernate 검증 대상이 아니다. 검증 중
예방적으로 GRANT 했으나 불필요했다.

그리고 passkey-app 이 `SecurityIncident` 를 쓰는지 확인했다:

| 확인 | 결과 |
|---|---|
| passkey-app 코드의 `SecurityIncident` 참조 | **0건** |
| `SecurityIncidentRepository` 사용 모듈 | **admin-app 만**(`SecurityIncidentService`, `AuditChainMonitorController`) |
| passkey-app 이 쓰는 core 리포지토리 | `ApiKeyScope`, `Credential`, `Tenant` **3개뿐** |
| `SecurityIncident` 의 다른 엔티티와의 연관관계 | **없음** (V1 주석: "독립 — 실DB에서 tenant FK 없음") |

즉 **passkey-app 은 이 테이블을 읽지 않고, 빼도 다른 매핑이 깨지지 않는다.**
GRANT 를 주면 안 쓰는 테이블에 불필요한 권한을 열어주는 셈이라 최소권한
원칙에 어긋난다. V1 의 GRANT 누락은 **의도된 설계로 보인다** — 보안 인시던트는
관리 기능이므로 런타임 서버가 볼 이유가 없다.

**진짜 문제는 passkey-app 이 자기가 쓰지 않는 엔티티까지 스키마 검증한다는 것이다.**
엔티티가 `core` 에 있고 passkey-app 이 `@EntityScan` 으로 core 를 통째로 스캔하기
때문이다(`PasskeyApplication.java`).

**제약:** core 엔티티 23개가 `core.entity` **한 패키지에 모여 있어**
`@EntityScan("com.crosscert.passkey.core.entity")` 로는 선택적 제외가 안 된다
(passkey-app / admin-app 둘 다 동일하게 통째로 스캔한다).

검토한 세 가지 안:

| 안 | 방법 | 판단 |
|---|---|---|
| A | `SecurityIncident` 를 하위 패키지로 옮기고 passkey-app 스캔에서 제외 | **채택 안 함** — 그 테이블이 실제로 없어도 passkey-app 이 뜨게 된다. "스키마가 완전해야 부팅"이라는 성질이 약해진다 |
| B | passkey-app 만 `ddl-auto: none` | **채택 안 함** — 스키마 드리프트 감지를 통째로 잃는다 |
| **C** | **V4 마이그레이션으로 `PSK_APP_RUNTIME` 에 SELECT GRANT** | **✅ 채택** — 검증이 실제 스키마를 확인한 뒤 통과한다 |

**결정: C.** "DB 스키마가 완전해야 앱이 뜬다"는 성질을 유지하는 것이 판단 기준이었다.
A/B 는 검증을 우회하거나 끄는 방향이라 그 성질을 훼손한다.

권한은 **SELECT 만** 부여했다 — passkey-app 은 이 테이블을 읽지도 쓰지도 않고,
Hibernate 검증이 메타데이터를 조회할 수 있으면 충분하다. INSERT/UPDATE 는 계속
`PSK_APP_ADMIN` 전용으로 남는다.

마이그레이션: `core/src/main/resources/db/migration/V4__grant_security_incident_to_app_runtime.sql`

`mds_sync_history` 는 GRANT 하지 않았다 — JPA 엔티티가 없어(raw-JDBC 전용)
검증 대상이 아니므로 불필요하다. 검증 중 임시로 준 GRANT 는 로컬 DB 에만
있었고 커밋되지 않았다.

권한 누락 전수 조사 방법:
```sql
SELECT t.table_name FROM user_tables t
WHERE NOT EXISTS (SELECT 1 FROM user_tab_privs_made p
                  WHERE p.table_name = t.table_name AND p.grantee = 'PSK_APP_RUNTIME');
```

### 배포 순서 의존성 — 최초 기동 시 passkey-app 이 먼저 실패한다

`COMPOSE_PROFILES=proxy,admin docker compose up -d` 로 한 번에 띄우면 passkey-app 이
반드시 몇 차례 실패하고 재시작한다:
```
Schema-validation: missing table [security_incident]
Schema-validation: missing column [mfa_failed_count] in table [admin_user]
```
**admin-app 이 Flyway 마이그레이션을 끝내기 전에 passkey-app 이 스키마를 검증**하기
때문이다. `restart: unless-stopped` 덕분에 admin-app 이 마이그레이션을 끝내면
passkey-app 도 결국 올라오지만, **최초 기동 로그에 에러가 찍히는 것은 정상 동작**이다.

빈 스키마에 처음 올릴 때는 admin-app 을 먼저 기동해 healthy 를 확인한 뒤
passkey-app 을 띄우는 편이 로그가 깨끗하다:
```bash
COMPOSE_PROFILES=admin docker compose up -d admin-app   # 마이그레이션 수행
# healthy 확인 후
COMPOSE_PROFILES=proxy,admin docker compose up -d
```
`deploy/README.md` 에 반영할 값어치가 있다.

### 검증 중 만난 prod 안전장치 2건 (앱의 의도된 동작 — 결함 아님)

**① `MASTER_KEY` 는 정확히 32바이트여야 한다.** 33바이트를 넣었더니
`KeyEnvelope` 생성자에서 부팅이 실패했다. `openssl rand -base64 32` 로 만든 값
(base64 44자)을 쓴다. 계획서에 넣었던 예시 값이 33바이트라 잘못됐다.

**② `ADMIN_INVITE_BASE_URL` 이 localhost 기본값이면 prod 는 부팅을 거부한다.**
```
IllegalStateException: prod profile requires admin.invite.base-url to be set to
the real front-end origin — refusing to boot with the localhost dev default
(http://localhost:5173). Set ADMIN_INVITE_BASE_URL.
```
초대 메일의 수락 링크가 localhost 로 나가는 사고를 막는 fail-closed 가드다.
`.env.example` 의 값을 반드시 실제 프론트엔드 origin 으로 바꿔야 한다.

### 환경 함정 2건 (코드 결함 아님)

**① 베이스 이미지 pull 이 빌드 중에는 타임아웃된다.** Docker VM 내부 네트워크가
느려 `FROM eclipse-temurin:17-jre` 단계에서 `DeadlineExceeded` 로 실패했다.
호스트 `curl registry-1.docker.io` 는 0.68초로 정상이었다 — VM 내부만 느리다.
**해결: 빌드 전에 `docker pull eclipse-temurin:17-jre` 를 따로 실행**해 캐시에
올려두면 빌드가 그 단계를 건너뛴다. 배포 문서 트러블슈팅에 넣을 값어치가 있다.

**② dev/local 시드가 남은 DB 에 prod 프로필을 붙이면 Flyway 가 거부한다.**
```
Detected applied migration not resolved locally: seed local tenant.
Detected applied migration not resolved locally: seed operators.
```
prod 는 `classpath:db/migration,classpath:db/prod` 만 읽으므로 dev 시드(`R__`)를
찾지 못해 `validate-on-migrate: true` 가 실패시킨다. **이는 prod 프로필의 의도된
동작이다**(스키마 오염 방지). 검증 시에는 볼륨까지 재생성해야 한다:
`docker compose -p passkey2 down -v && up -d` → `wait-for-oracle` → `run-bootstrap`.

실 운영 DB 에 이 문제가 있다면 `flyway repair` 로 해소하되, **왜 dev 시드가
운영 DB 에 적용됐는지**를 먼저 확인해야 한다.

## 배경: 이 구현에서 겪은 함정

향후 유사 작업에서 반복하지 않도록 기록한다.

1. **`.gitignore` 의 `deploy/` 는 `!` 예외를 무력화한다.** 부모 디렉터리가
   제외되면 그 안의 파일은 재포함 불가(gitignore(5)). `deploy/*` 로 내용물만
   무시해야 negation 이 동작한다.
2. **`.gitignore` 와 `.dockerignore` 는 별개 방어선이다.** git 은 커밋만 막고
   Docker 빌드 컨텍스트는 못 막는다. 운영 서버에서 build 하면 그 서버의
   `.env`(마스터키·DB 비밀번호)가 이미지 레이어에 실린다. 실증했다.
3. **시크릿 패턴은 변형까지 열거해야 한다.** `.env` 만 막으면 `.env.local`,
   `.env.production` 이 뚫린다. 이 프로젝트는 `application-secret*.yml` 규약도
   쓴다. 같은 결함이 4라운드에 걸쳐 다른 변형으로 반복 발견됐다.
4. **`# syntax=docker/dockerfile:1` 은 외부 의존성이다.** buildx 가 Docker Hub
   에서 프론트엔드 이미지를 받아오는데, 실패하면 Dockerfile 파싱 전에 빌드가
   막힌다. BuildKit 전용 문법을 쓰지 않는다면 넣을 이유가 없다.
5. **`docker compose config` 는 데몬 없이 동작한다.** 데몬 장애 중에도 compose
   파일 검증(profile 조합, 변수 해석, fail-closed)은 그대로 수행 가능했다.
6. **공유 자원에 파괴적 조치를 하지 말 것.** 구현 중 한 에이전트가 진단 목적으로
   Docker Desktop 을 재시작해 다른 작업의 진행 중 빌드를 끊었다. 데몬 재시작은
   머신 전체에 영향을 주므로 사용자 판단 사항이다.
