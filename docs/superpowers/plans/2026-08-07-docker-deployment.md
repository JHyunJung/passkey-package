# Docker 컨테이너 배포 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** passkey-app / admin-app / rp-app 을 각각 독립 Docker 컨테이너로 빌드·기동하고, 외부 Oracle DB 와 별도 호스트 Redis 에 연결해 docker compose 로 운영(prod)까지 배포할 수 있게 한다.

**Architecture:** 앱당 멀티스테이지 Dockerfile(JDK build → JRE runtime) 1개씩. 배포용 compose 는 `deploy/` 아래 신규 생성하고 루트 `docker-compose.yml`(로컬 개발용)은 건드리지 않는다. compose profile(`proxy`/`admin`/`qa`)로 서버 A·B·QA 의 역할을 하나의 파일에서 분기한다. 앱 코드는 수정하지 않는다 — prod 프로필이 이미 모든 값을 환경변수 fail-fast 로 받는다.

**Tech Stack:** Docker 29.x / Docker Compose v5, eclipse-temurin 17 (JDK+JRE), Gradle 8.10 wrapper, nginx alpine, redis 7 alpine, Spring Boot 3.5

## Global Constraints

- 설계 근거 문서: `docs/superpowers/specs/2026-08-07-docker-deployment-design.md` — 충돌 시 스펙이 우선한다.
- **앱 코드(`*/src/main/**`)를 수정하지 않는다.** 이 작업은 배포 인프라만 추가한다.
- **루트 `docker-compose.yml` 을 수정하지 않는다.** `scripts/init-dev-db.sh` 등 로컬 개발 흐름이 의존한다.
- Java toolchain 은 17 고정 (`build.gradle.kts` 의 `JavaLanguageVersion.of(17)`).
- bootJar 산출물 이름은 세 모듈 모두 `<module>.jar` 이고 출력 위치는 `rootProject/deploy/` 다 (각 모듈 `build.gradle.kts` 의 `archiveFileName` / `destinationDirectory`).
- 이미지 태그는 `<module>:0.0.1-SNAPSHOT` 형태로 고정한다. **`latest` 금지.**
- 컨테이너는 비루트 사용자(uid 1001)로 실행한다.
- `X-Forwarded-Proto` 는 nginx 에서 `$http_x_forwarded_proto`(LB 원본)로 전달한다. **`$scheme` 금지** — WebAuthn origin 검증이 깨진다.
- 커밋 메시지는 한국어 본문 + Conventional Commits 프리픽스를 쓴다 (기존 이력 관례).

## 사전 확인된 사실 (조사 완료 — 재조사 불필요)

| 사실 | 근거 |
|---|---|
| `forward-headers-strategy: framework` 가 **이미 base yml 에 설정됨** | `passkey-app/src/main/resources/application.yml:19`, `admin-app/.../application.yml:52` |
| admin-ui 는 이미 admin-app jar 에 번들됨 | `admin-app/build.gradle.kts` 의 `buildUi` → `processResources` → `static/admin` |
| rp-app 포트는 9090, Redis 미사용 | `rp-app/src/main/resources/application.yml:2` |
| `.gitignore:20` 이 `deploy/` 전체를 무시 | Task 1 에서 해소 |
| `/actuator/health` 사용 가능 | actuator 가 `core/build.gradle.kts:15` 에 있고 Boot 기본 노출이 health |

**스펙과의 차이 1건:** 스펙 §8 은 `SERVER_FORWARD_HEADERS_STRATEGY=FRAMEWORK` 주입을 지시하나, 위 표대로 base yml 에 이미 있으므로 **주입하지 않는다.** `.env.example` 에 주석으로만 남긴다.

## File Structure

| 파일 | 책임 |
|---|---|
| `.dockerignore` | 빌드 컨텍스트 축소 (전 이미지 공용) |
| `.gitignore` (수정) | `deploy/` 예외 규칙 — compose/nginx 는 커밋, `.env` 는 무시 |
| `passkey-app/Dockerfile` | passkey-app 이미지 |
| `admin-app/Dockerfile` | admin-app 이미지 (Node 는 Gradle 이 자동 다운로드) |
| `rp-app/Dockerfile` | rp-app 이미지 (QA 전용) |
| `deploy/docker-compose.yml` | 앱 스택 — profile 로 서버 역할 분기 |
| `deploy/docker-compose.redis.yml` | Redis 전용 (서버 C) |
| `deploy/nginx/nginx.conf` | 서브도메인 라우팅, 헤더 전달 |
| `deploy/.env.example` | 환경변수 템플릿 (커밋 대상) |
| `deploy/README.md` | 배포 절차·운영 런북 |
| `docs/single-instance-deployment.md` (수정) | 레거시 표기 |
| `README.md` (수정) | 컨테이너 배포 경로 추가 |

---

### Task 1: `.gitignore` 예외 규칙과 `.dockerignore`

**Files:**
- Modify: `.gitignore:19-20`
- Create: `.dockerignore`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces: `deploy/docker-compose.yml`, `deploy/nginx/`, `deploy/.env.example` 이 git 에 커밋 가능한 상태. 이후 모든 태스크가 이에 의존한다.

이 태스크가 먼저인 이유: 현재 `.gitignore:20` 이 `deploy/` 디렉터리 전체를 무시한다. 이 상태로 Task 5 에서 compose 파일을 만들면 **커밋되지 않고 조용히 누락된다.**

- [ ] **Step 1: 현재 무시 상태를 확인 (실패하는 테스트에 해당)**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
mkdir -p deploy/nginx && touch deploy/docker-compose.yml deploy/.env.example deploy/nginx/nginx.conf
git check-ignore -v deploy/docker-compose.yml deploy/.env.example deploy/nginx/nginx.conf
```

Expected: 세 파일 모두 `.gitignore:20:deploy/` 규칙에 걸려 출력됨 (= 커밋 불가 상태 확인)

- [ ] **Step 2: `.gitignore` 에 예외 규칙 추가**

`.gitignore:19-20` 의 다음 두 줄을

```gitignore
# bootJar 배포 산출물(루트 deploy/ 로 모음)
deploy/
```

아래 블록으로 교체한다:

```gitignore
# bootJar 배포 산출물(루트 deploy/ 로 모음)
# 주의: 'deploy/' 처럼 디렉터리 자체를 무시하면 git 이 그 안을 순회하지 않아
# 아래 '!' 예외 규칙이 전혀 동작하지 않는다(gitignore(5): 부모 디렉터리가
# 제외되면 그 안의 파일은 재포함 불가). 그래서 디렉터리가 아닌 '내용물'을 무시한다.
deploy/*
# 단, 컨테이너 배포 자산은 버전 관리한다
# (deploy/.env 는 상위 *.env 로도 잡히지만, ! 재포함 목록 옆에 두어
#  "이것만은 예외가 아니다" 라는 의도를 명확히 한다)
!deploy/docker-compose.yml
!deploy/docker-compose.redis.yml
!deploy/.env.example
!deploy/README.md
!deploy/nginx/
deploy/.env
```

**`deploy/` 가 아니라 `deploy/*` 여야 하는 이유(실측 확인):** `deploy/` 로
디렉터리 노드를 무시하면 git 이 순회 단계에서 통째로 잘라내므로 이후의 `!`
패턴이 평가조차 되지 않는다. 실제로 `deploy/` 를 쓰고 Step 3 를 실행하면
`.gitignore:20:deploy/` 로 여전히 차단된다. `deploy/*` 는 내용물만 무시하므로
git 이 디렉터리 안으로 들어가 negation 을 적용할 수 있다.

`!deploy/nginx/` 로 디렉터리를 재포함하면 그 아래 파일은 별도 패턴이 다시
제외하지 않는 한 자동으로 추적 대상이 되므로 `!deploy/nginx/*` 는 불필요하다.

- [ ] **Step 3: 규칙이 의도대로 동작하는지 검증**

```bash
touch deploy/.env
git check-ignore -v deploy/docker-compose.yml deploy/.env.example deploy/nginx/nginx.conf ; echo "exit=$?"
git check-ignore -v deploy/.env
```

Expected:
- 첫 명령: 아무 출력 없이 `exit=1` (= 더 이상 무시되지 않음 → 커밋 가능)
- 둘째 명령: `.gitignore:...:deploy/.env` 로 출력 (= 시크릿은 여전히 무시됨)

- [ ] **Step 4: `.dockerignore` 작성**

```
# 빌드 컨텍스트 축소 — 리포지토리 루트가 컨텍스트라 필수
.git/
.gradle/
build/
*/build/
**/node_modules/
admin-ui/dist/
deploy/*.jar
.idea/
.claude/
.claire/
.gstack/
.superpowers/
docs/
scripts/
*.md

# 시크릿 — .gitignore 로는 막을 수 없다(git 은 커밋만 막고 빌드 컨텍스트는
# 그대로 실린다). 운영 서버에서 build 하면 그 서버의 deploy/.env 가 이미지
# 레이어에 남으므로 여기서도 반드시 제외한다.
**/.env
**/.env.*
!**/.env.example

!admin-ui/package.json
!admin-ui/package-lock.json
```

`docs/`·`scripts/`·`*.md` 제외 주의: `admin-app/build.gradle.kts` 의 `processTestResources` 가 `scripts/bootstrap-schema.sql` 을 참조하지만 이는 **테스트 리소스**이고, 이미지 빌드는 `bootJar` 만 실행하므로 영향이 없다.

**`.env` 제외는 보안상 필수다(실측 확인).** 이 규칙이 없으면
`docker build` 시 `deploy/.env` 가 컨텍스트에 실려 이미지 안에서
`cat /ctx/deploy/.env` 로 마스터키가 평문 노출된다. 구현 시 아래로 검증한다:

```bash
mkdir -p deploy && echo "MASTER_KEY=SECRET_TEST" > deploy/.env
printf 'FROM busybox\nCOPY . /ctx\n' > .ctxtest.Dockerfile
docker build -q -f .ctxtest.Dockerfile -t ctxtest . >/dev/null
docker run --rm ctxtest sh -c 'cat /ctx/deploy/.env 2>/dev/null || echo "(없음 — 안전)"'
# 기대: "(없음 — 안전)"
rm -f deploy/.env .ctxtest.Dockerfile; rmdir deploy; docker rmi -f ctxtest
```

`.ctxtest.Dockerfile` 은 반드시 리포지토리 안에 만든다 — `/tmp` 에 두면 Docker 가
`/tmp` 전체를 읽으려다 xattr 권한 오류로 실패한다.

- [ ] **Step 5: 임시 파일 정리 후 커밋**

```bash
rm -f deploy/docker-compose.yml deploy/.env.example deploy/nginx/nginx.conf deploy/.env
rmdir deploy/nginx 2>/dev/null || true
git add .gitignore .dockerignore
git commit -m "chore(deploy): deploy/ 예외 규칙 + .dockerignore 추가

deploy/ 가 통째로 gitignore 되어 컨테이너 배포 자산(compose/nginx/.env.example)이
커밋되지 않는 문제를 예외 규칙으로 해소. 기존 *.env 패턴은 '.env' 자체를
매칭하지 못하므로 deploy/.env 를 명시적으로 무시 대상에 추가."
```

---

### Task 2: passkey-app Dockerfile

**Files:**
- Create: `passkey-app/Dockerfile`

**Interfaces:**
- Consumes: Task 1 의 `.dockerignore`
- Produces: 이미지 `passkey-app:0.0.1-SNAPSHOT`. 내부 포트 8080. `JAVA_OPTS` 환경변수를 받는다. Task 5 의 compose 가 이 이미지명과 포트를 참조한다.

- [ ] **Step 1: Dockerfile 작성**

```dockerfile
# syntax=docker/dockerfile:1
# passkey-app — WebAuthn 등록/인증 서버. 무상태이므로 N개로 스케일 가능.
# 빌드 컨텍스트는 리포지토리 루트다(멀티모듈: core/webauthn 소스가 필요).
#   docker build -t passkey-app:0.0.1-SNAPSHOT -f passkey-app/Dockerfile .

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

# 의존성 레이어 캐싱: 빌드 스크립트만 먼저 복사해 의존성을 받아두면
# 소스만 바뀐 재빌드에서 이 레이어가 재사용된다.
COPY gradlew ./
COPY gradle/ gradle/
COPY settings.gradle.kts build.gradle.kts ./
COPY core/build.gradle.kts core/
COPY webauthn/build.gradle.kts webauthn/
COPY passkey-app/build.gradle.kts passkey-app/
COPY admin-app/build.gradle.kts admin-app/
COPY rp-app/build.gradle.kts rp-app/
COPY sdk-java/build.gradle.kts sdk-java/
RUN ./gradlew :passkey-app:dependencies --no-daemon --console=plain || true

# 소스 복사 후 실제 빌드. bootJar 는 deploy/passkey-app.jar 로 떨어진다
# (passkey-app/build.gradle.kts 의 archiveFileName/destinationDirectory).
COPY . .
RUN ./gradlew :passkey-app:bootJar --no-daemon --console=plain

FROM eclipse-temurin:17-jre
# 비루트 실행 — 컨테이너 탈출 시 피해 축소
RUN useradd -r -u 1001 -m appuser
WORKDIR /app
COPY --from=build /src/deploy/passkey-app.jar /app/app.jar
USER appuser
EXPOSE 8080
# JAVA_OPTS 로 힙 상한을 컨테이너 메모리에 맞춘다(compose 의 mem_limit 인식).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
```

`:passkey-app:dependencies` 뒤의 `|| true`: 이 단계는 의존성 캐시 워밍이 목적이고, 모듈 간 프로젝트 의존성이 아직 소스 없이 해석되지 않아 실패할 수 있다. 실패해도 다운로드된 의존성은 Gradle 캐시에 남으므로 빌드를 막지 않는다.

`ENTRYPOINT` 의 `exec`: 이게 없으면 `sh` 가 PID 1 이 되어 `docker stop` 의 SIGTERM 이 JVM 에 전달되지 않고 10초 후 SIGKILL 된다. Spring 의 graceful shutdown 이 동작하려면 필수다.

- [ ] **Step 2: 이미지 빌드 (실제 검증)**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
docker build -t passkey-app:0.0.1-SNAPSHOT -f passkey-app/Dockerfile . 2>&1 | tail -20
```

Expected: `Successfully tagged passkey-app:0.0.1-SNAPSHOT` 또는 `naming to docker.io/library/passkey-app:0.0.1-SNAPSHOT done`. 초회 5~10분 소요.

- [ ] **Step 3: 이미지가 비루트로 실행되고 jar 가 존재하는지 확인**

```bash
docker run --rm passkey-app:0.0.1-SNAPSHOT sh -c 'id -u; ls -l /app/app.jar'
```

Expected: `1001` 출력 후 `/app/app.jar` 파일 정보 (수십 MB)

- [ ] **Step 4: env 미주입 시 fail-fast 하는지 확인**

```bash
docker run --rm -e SPRING_PROFILES_ACTIVE=prod passkey-app:0.0.1-SNAPSHOT 2>&1 | tail -15
```

Expected: DB URL 이 비어 부팅 실패 (`Failed to configure a DataSource` 또는 데이터소스/Flyway 관련 오류). **이것이 정상 동작이다** — prod 프로필의 의도된 fail-fast 를 확인하는 단계다. 컨테이너가 조용히 뜨면 오히려 문제다.

- [ ] **Step 5: 커밋**

```bash
git add passkey-app/Dockerfile
git commit -m "feat(deploy): passkey-app 멀티스테이지 Dockerfile 추가

JDK17 빌드 스테이지 → JRE 런타임 스테이지로 이미지 축소. 비루트(uid 1001)
실행, JAVA_OPTS 로 컨테이너 메모리 인식, exec 로 SIGTERM 전달 보장."
```

---

### Task 3: admin-app Dockerfile

**Files:**
- Create: `admin-app/Dockerfile`

**Interfaces:**
- Consumes: Task 1 의 `.dockerignore`
- Produces: 이미지 `admin-app:0.0.1-SNAPSHOT`. 내부 포트 8081. admin-ui 가 jar 내부 `static/admin` 에 번들된 상태.

Task 2 와 구조는 같지만 **admin-ui 빌드가 추가로 일어난다.** `buildUi` 태스크가 `node.download=true` 로 Node 18 을 받아 `npm run build` 를 실행하므로 초회 빌드가 가장 느리다(10분 이상 가능). Node 설치 명령을 Dockerfile 에 넣을 필요는 없다 — Gradle 이 처리한다.

- [ ] **Step 1: Dockerfile 작성**

```dockerfile
# syntax=docker/dockerfile:1
# admin-app — 운영 콘솔 + Flyway 마이그레이션 책임. 1개만 기동한다(스케줄러 리스).
# admin-ui(React)는 build.gradle.kts 의 buildUi 태스크가 Node 18 을 자동
# 다운로드해 빌드하고 jar 의 static/admin 에 번들한다 — 별도 Node 설치 불필요.
#   docker build -t admin-app:0.0.1-SNAPSHOT -f admin-app/Dockerfile .

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

COPY gradlew ./
COPY gradle/ gradle/
COPY settings.gradle.kts build.gradle.kts ./
COPY core/build.gradle.kts core/
COPY webauthn/build.gradle.kts webauthn/
COPY passkey-app/build.gradle.kts passkey-app/
COPY admin-app/build.gradle.kts admin-app/
COPY rp-app/build.gradle.kts rp-app/
COPY sdk-java/build.gradle.kts sdk-java/
# npm 의존성도 캐시 레이어에 포함 — 소스만 바뀔 때 npm install 재실행 방지
COPY admin-ui/package.json admin-ui/package-lock.json admin-ui/
RUN ./gradlew :admin-app:dependencies --no-daemon --console=plain || true

COPY . .
RUN ./gradlew :admin-app:bootJar --no-daemon --console=plain

FROM eclipse-temurin:17-jre
RUN useradd -r -u 1001 -m appuser
WORKDIR /app
COPY --from=build /src/deploy/admin-app.jar /app/app.jar
USER appuser
EXPOSE 8081
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
```

- [ ] **Step 2: 이미지 빌드**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
docker build -t admin-app:0.0.1-SNAPSHOT -f admin-app/Dockerfile . 2>&1 | tail -20
```

Expected: 빌드 성공. 로그에 `> Task :admin-app:buildUi` 와 npm 빌드 출력이 보여야 한다.

- [ ] **Step 3: admin-ui 가 jar 에 번들됐는지 확인 (핵심 검증)**

```bash
docker run --rm admin-app:0.0.1-SNAPSHOT sh -c \
  'cd /tmp && unzip -l /app/app.jar | grep -c "static/admin"'
```

Expected: 1 이상의 숫자 (index.html, assets/*.js 등). **0 이면 UI 번들 실패** — `buildUi` 가 안 돌았거나 `.dockerignore` 가 `admin-ui/` 를 과도하게 제외한 것이다.

- [ ] **Step 4: 커밋**

```bash
git add admin-app/Dockerfile
git commit -m "feat(deploy): admin-app 멀티스테이지 Dockerfile 추가

buildUi 태스크가 Node 18 을 자동 다운로드해 admin-ui 를 jar 의 static/admin 에
번들하므로 Dockerfile 에 Node 설치가 불필요. npm 의존성도 캐시 레이어에 포함."
```

---

### Task 4: rp-app Dockerfile

**Files:**
- Create: `rp-app/Dockerfile`

**Interfaces:**
- Consumes: Task 1 의 `.dockerignore`
- Produces: 이미지 `rp-app:0.0.1-SNAPSHOT`. 내부 포트 9090 (`rp-app/src/main/resources/application.yml:2`). QA 에서만 사용.

- [ ] **Step 1: Dockerfile 작성**

```dockerfile
# syntax=docker/dockerfile:1
# rp-app — 고객사 연동 샘플 앱. QA 환경에서만 기동한다(prod 스택 제외).
# 고객사는 이 앱을 참고해 자사 서비스에 맞게 변형하므로 배포용 이미지가 아니다.
#   docker build -t rp-app:0.0.1-SNAPSHOT -f rp-app/Dockerfile .

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

COPY gradlew ./
COPY gradle/ gradle/
COPY settings.gradle.kts build.gradle.kts ./
COPY core/build.gradle.kts core/
COPY webauthn/build.gradle.kts webauthn/
COPY passkey-app/build.gradle.kts passkey-app/
COPY admin-app/build.gradle.kts admin-app/
COPY rp-app/build.gradle.kts rp-app/
COPY sdk-java/build.gradle.kts sdk-java/
RUN ./gradlew :rp-app:dependencies --no-daemon --console=plain || true

COPY . .
RUN ./gradlew :rp-app:bootJar --no-daemon --console=plain

FROM eclipse-temurin:17-jre
RUN useradd -r -u 1001 -m appuser
WORKDIR /app
COPY --from=build /src/deploy/rp-app.jar /app/app.jar
USER appuser
EXPOSE 9090
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
```

- [ ] **Step 2: 이미지 빌드 및 확인**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
docker build -t rp-app:0.0.1-SNAPSHOT -f rp-app/Dockerfile . 2>&1 | tail -10
docker run --rm rp-app:0.0.1-SNAPSHOT sh -c 'id -u; ls -l /app/app.jar'
```

Expected: 빌드 성공, `1001` 출력, jar 존재

- [ ] **Step 3: 커밋**

```bash
git add rp-app/Dockerfile
git commit -m "feat(deploy): rp-app 멀티스테이지 Dockerfile 추가 (QA 전용)

고객사 연동 샘플 앱으로 prod 스택에서는 제외되고 QA 에서만 기동한다."
```

---

### Task 5: nginx 설정

**Files:**
- Create: `deploy/nginx/nginx.conf`

**Interfaces:**
- Consumes: Task 2·4 의 이미지가 노출하는 포트 (passkey-app:8080, rp-app:9090)
- Produces: compose 서비스명 `passkey-app` / `rp-app` 으로 라우팅하는 nginx 설정. Task 6 의 compose 가 이 파일을 `/etc/nginx/conf.d/default.conf` 로 마운트한다.

**⚠️ 실측으로 확정된 제약 (이 계획 작성 중 검증함):**

`upstream` 블록이나 `proxy_pass` 에 호스트명을 **직접** 쓰면 nginx 는 **기동
시점에** DNS 를 해석하고, 대상이 없으면 다음과 같이 기동 자체가 실패한다:

```
[emerg] host not found in upstream "passkey-app:8080" in /etc/nginx/conf.d/default.conf:1
nginx: configuration file /etc/nginx/nginx.conf test failed
```

`depends_on` 이 있어도 이는 운영상 실질적 위험이다 — passkey-app 이 전부 죽은
상태에서 nginx 가 재기동되면 nginx 도 뜨지 못하고, `restart: unless-stopped`
때문에 **무한 재시작 루프**에 빠진다. rp-app 은 prod 에 아예 없으므로 더
확실히 실패한다.

따라서 **두 서버 블록 모두 `resolver` + 변수 방식(지연 해석)을 쓴다.** 변수를
쓰면 nginx 가 요청 시점에 이름을 해석하므로 백엔드 부재가 기동을 막지 않고
502 를 반환할 뿐이다.

- [ ] **Step 1: nginx.conf 작성**

```nginx
# 앞단 LB 가 TLS 를 종료하고 이 nginx 에는 평문 HTTP 로 전달한다.
# 여기서는 TLS 를 다루지 않고 서브도메인 라우팅과 헤더 전달만 담당한다.
#
# WebAuthn rpId 는 도메인 기준으로 묶이고 포트를 무시하므로, passkey 서버와
# RP 서버를 포트가 아닌 서브도메인으로 분리해야 신뢰 경계가 명확해진다
# (docs/single-instance-deployment.md 참고).
#
# proxy_pass 에 호스트명을 직접 쓰지 않고 resolver + 변수를 쓰는 이유:
# 직접 쓰면 nginx 가 기동 시점에 DNS 를 해석해, 백엔드가 아직/이미 없으면
# 'host not found in upstream' 으로 nginx 자체가 뜨지 못한다(실측 확인).
# 변수를 쓰면 요청 시점에 해석되므로 백엔드 부재는 502 일 뿐 기동을 막지 않는다.

server {
    listen 80;
    server_name ${PASSKEY_SERVER_NAME};

    # 127.0.0.11 은 Docker 내장 DNS. valid=10s 로 재해석 주기를 짧게 두어
    # --scale 로 인스턴스가 늘거나 줄면 10초 내에 반영된다.
    resolver 127.0.0.11 valid=10s ipv6=off;
    # compose 서비스명. Docker DNS 가 --scale 로 뜬 N개 컨테이너 IP 전부로
    # 응답하므로, 스케일을 바꿔도 이 설정은 수정할 필요가 없다.
    set $passkey_upstream "passkey-app:8080";

    location / {
        proxy_pass http://$passkey_upstream;
        proxy_set_header Host $host;
        # X-Forwarded-Proto 는 LB 가 보낸 원본을 그대로 전달한다.
        # $scheme 을 쓰면 nginx 가 받은 'http' 로 덮어써서 앱이 평문으로
        # 오판하고 secure 쿠키/WebAuthn origin 검증이 깨진다.
        proxy_set_header X-Forwarded-Proto $http_x_forwarded_proto;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Real-IP $remote_addr;
    }
}

# --- QA 전용: rp-app. prod 에서는 rp-app 컨테이너가 뜨지 않으며,
# --- 지연 해석 덕분에 이 블록이 있어도 nginx 기동에 영향이 없다(502 반환).
server {
    listen 80;
    server_name ${RP_SERVER_NAME};

    resolver 127.0.0.11 valid=10s ipv6=off;
    set $rp_upstream "rp-app:9090";

    location / {
        proxy_pass http://$rp_upstream;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $http_x_forwarded_proto;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

`${PASSKEY_SERVER_NAME}` / `${RP_SERVER_NAME}`: nginx 공식 이미지는
`/etc/nginx/templates/*.template` 를 `envsubst` 로 치환해
`/etc/nginx/conf.d/` 에 생성한다(실측 확인). Task 6 에서 이 파일을 **템플릿
경로로 마운트**한다.

**주의 — `set` 의 값은 반드시 따옴표로 감싼다.** envsubst 는 `${VAR}` 형태만
치환하므로 `$passkey_upstream` 같은 nginx 변수는 건드리지 않는다. 다만
가독성과 오치환 방지를 위해 따옴표를 유지한다.

- [ ] **Step 2: 백엔드가 하나도 없는 상태에서 nginx 가 뜨는지 검증 (핵심)**

이 검증의 목적은 문법 확인이 아니라 **지연 해석이 실제로 동작하는가**다.
컨테이너가 전혀 없는 격리 환경에서 실행해 백엔드 부재를 재현한다.

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
docker run --rm \
  -e PASSKEY_SERVER_NAME=passkey.example.com \
  -e RP_SERVER_NAME=rp.example.com \
  -v "$PWD/deploy/nginx/nginx.conf:/etc/nginx/templates/default.conf.template:ro" \
  nginx:1.27-alpine nginx -t 2>&1 | tail -10
```

Expected: `nginx: configuration file /etc/nginx/nginx.conf test is successful`

`host not found in upstream` 이 나오면 지연 해석이 적용되지 않은 것이다 —
`proxy_pass` 에 변수(`$passkey_upstream`)를 쓰고 있는지, `resolver` 가
server 블록 안에 있는지 확인한다.

- [ ] **Step 3: 치환된 설정 내용 확인**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
docker run --rm \
  -e PASSKEY_SERVER_NAME=passkey.example.com \
  -e RP_SERVER_NAME=rp.example.com \
  -v "$PWD/deploy/nginx/nginx.conf:/etc/nginx/templates/default.conf.template:ro" \
  --entrypoint sh nginx:1.27-alpine -c \
  '/docker-entrypoint.d/20-envsubst-on-templates.sh >/dev/null 2>&1; cat /etc/nginx/conf.d/default.conf'
```

Expected (실측 확인된 출력):
- `server_name passkey.example.com;` / `server_name rp.example.com;` — 환경변수 치환됨
- `set $passkey_upstream "passkey-app:8080";` — **nginx 변수는 그대로 보존**
  (envsubst 는 `${VAR}` 형태만 치환하므로 `$foo` 는 건드리지 않는다)
- `proxy_set_header X-Forwarded-Proto $http_x_forwarded_proto;` — **`$scheme` 이면 실패**

`grep` 으로 `set $...` 를 찾을 때는 셸이 `$passkey_upstream` 을 빈 문자열로
확장하지 않도록 주의한다. 위처럼 `cat` 으로 전문을 보는 편이 확실하다.

- [ ] **Step 3: 커밋**

```bash
git add deploy/nginx/nginx.conf
git commit -m "feat(deploy): nginx 서브도메인 라우팅 설정 추가

앞단 LB 가 TLS 종료하므로 평문 HTTP 만 처리. X-Forwarded-Proto 는 LB 원본을
보존($scheme 금지 — WebAuthn origin 검증이 깨짐). upstream 에 compose 서비스명을
써서 --scale 시 Docker DNS 가 자동 분산."
```

---

### Task 6: 앱 스택 compose + 환경변수 템플릿

**Files:**
- Create: `deploy/docker-compose.yml`
- Create: `deploy/.env.example`

**Interfaces:**
- Consumes: Task 2·3·4 의 이미지(`passkey-app`/`admin-app`/`rp-app`:0.0.1-SNAPSHOT), Task 5 의 `deploy/nginx/nginx.conf`
- Produces: profile `proxy`/`admin`/`qa` 로 분기되는 앱 스택. Task 7 의 Redis 와 `SPRING_DATA_REDIS_HOST` 로 연결된다.

- [ ] **Step 1: compose 파일 작성**

```yaml
# 배포용 앱 스택 — 서버 A / 서버 B / QA 가 이 파일 하나를 공유하고
# COMPOSE_PROFILES 와 .env 로 역할을 분기한다.
#
#   서버 A: COMPOSE_PROFILES=proxy,admin docker compose up -d --scale passkey-app=3
#   서버 B: COMPOSE_PROFILES=proxy       docker compose up -d --scale passkey-app=3
#   QA    : COMPOSE_PROFILES=proxy,admin,qa docker compose up -d
#
# DB(외부 Oracle)와 Redis(서버 C)는 이 스택 밖에 있다.
# 루트 docker-compose.yml 은 로컬 개발용(Oracle+Redis)이며 이 파일과 무관하다.

name: passkey-deploy

services:
  nginx:
    profiles: ["proxy"]
    image: nginx:1.27-alpine
    restart: unless-stopped
    ports:
      - "${NGINX_HOST_PORT:-80}:80"
    volumes:
      # 공식 이미지가 templates/ 의 파일을 envsubst 로 치환해
      # /etc/nginx/conf.d/ 에 생성한다.
      - ./nginx/nginx.conf:/etc/nginx/templates/default.conf.template:ro
    environment:
      PASSKEY_SERVER_NAME: ${PASSKEY_SERVER_NAME}
      RP_SERVER_NAME: ${RP_SERVER_NAME:-rp.invalid}
    depends_on:
      - passkey-app

  passkey-app:
    image: passkey-app:${IMAGE_TAG:-0.0.1-SNAPSHOT}
    restart: unless-stopped
    # ports 매핑 없음 — nginx 가 compose 내부 네트워크로 접근한다.
    # 호스트 포트를 잡지 않아야 --scale 시 충돌이 나지 않는다.
    # nginx 를 빼고 호스트 nginx 를 쓸 경우에만 아래를 주석 해제한다
    # (단, 그 경우 --scale 은 1 로 제한된다).
    #ports:
    #  - "${PASSKEY_HOST_PORT:-8080}:8080"
    mem_limit: ${PASSKEY_MEM_LIMIT:-1g}
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_DATASOURCE_URL: ${DB_URL}
      SPRING_DATASOURCE_USERNAME: ${DB_RUNTIME_USER}
      SPRING_DATASOURCE_PASSWORD: ${DB_RUNTIME_PASSWORD}
      SPRING_DATA_REDIS_HOST: ${REDIS_HOST}
      SPRING_DATA_REDIS_PORT: ${REDIS_PORT:-6379}
      SPRING_DATA_REDIS_PASSWORD: ${REDIS_PASSWORD}
      PASSKEY_KEY_ENVELOPE_MASTER_KEY: ${MASTER_KEY}
      PASSKEY_ID_TOKEN_ISSUER_BASE: ${ISSUER_BASE}
      JAVA_OPTS: ${PASSKEY_JAVA_OPTS:--XX:MaxRAMPercentage=75}
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health | grep -q UP || exit 1"]
      interval: 15s
      timeout: 5s
      retries: 5
      start_period: 60s

  admin-app:
    profiles: ["admin"]
    image: admin-app:${IMAGE_TAG:-0.0.1-SNAPSHOT}
    restart: unless-stopped
    deploy:
      replicas: 1        # 스케줄러 리스가 있어 늘려도 안전하나, 1개로 고정한다
    ports:
      # 내부망 전용 — 외부 노출 금지. 호스트 내부 IP 에만 바인딩한다.
      - "${ADMIN_BIND_ADDR:-127.0.0.1}:${ADMIN_HOST_PORT:-8081}:8081"
    mem_limit: ${ADMIN_MEM_LIMIT:-1g}
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_DATASOURCE_URL: ${DB_URL}
      SPRING_DATASOURCE_USERNAME: ${DB_ADMIN_USER}
      SPRING_DATASOURCE_PASSWORD: ${DB_ADMIN_PASSWORD}
      # Flyway 는 admin-app 에만 주입한다. passkey-app 에 주면 --scale 로 늘어난
      # 인스턴스들이 동시에 마이그레이션을 시도한다.
      SPRING_FLYWAY_USER: ${DB_OWNER_USER}
      SPRING_FLYWAY_PASSWORD: ${DB_OWNER_PASSWORD}
      SPRING_DATA_REDIS_HOST: ${REDIS_HOST}
      SPRING_DATA_REDIS_PORT: ${REDIS_PORT:-6379}
      SPRING_DATA_REDIS_PASSWORD: ${REDIS_PASSWORD}
      # 서버 A·B 가 같은 값이어야 한다. 다르면 한쪽 암호화 데이터를
      # 다른 쪽이 복호화하지 못한다.
      PASSKEY_KEY_ENVELOPE_MASTER_KEY: ${MASTER_KEY}
      PASSKEY_MDS_LEASE_HOLDER: ${MDS_LEASE_HOLDER:-admin-a}
      ADMIN_INVITE_BASE_URL: ${ADMIN_INVITE_BASE_URL}
      JAVA_OPTS: ${ADMIN_JAVA_OPTS:--XX:MaxRAMPercentage=75}
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8081/actuator/health | grep -q UP || exit 1"]
      interval: 15s
      timeout: 5s
      retries: 5
      start_period: 90s

  rp-app:
    profiles: ["qa"]     # prod 에서는 이 profile 을 지정하지 않아 기동되지 않는다
    image: rp-app:${IMAGE_TAG:-0.0.1-SNAPSHOT}
    restart: unless-stopped
    mem_limit: ${RP_MEM_LIMIT:-1g}
    environment:
      SPRING_PROFILES_ACTIVE: ${RP_PROFILE:-qa}
      PASSKEY_BASE_URL: ${RP_PASSKEY_BASE_URL}
      PASSKEY_ISSUER_BASE: ${ISSUER_BASE}
      PASSKEY_API_KEY: ${RP_API_KEY}
      PASSKEY_TENANT_ID: ${RP_TENANT_ID}
      JAVA_OPTS: ${RP_JAVA_OPTS:--XX:MaxRAMPercentage=75}
```

**`ADMIN_BIND_ADDR` 기본값 `127.0.0.1`:** admin-app 은 내부망 전용이다. 기본값을 `0.0.0.0` 으로 두면 운영자가 모르는 사이 콘솔이 외부에 열린다. 원격 접근이 필요하면 `.env` 에서 내부 IP 를 명시하게 한다(fail-closed).

- [ ] **Step 2: `.env.example` 작성**

```bash
# deploy/.env.example — 각 서버에서 .env 로 복사해 값을 채운다.
#   cp .env.example .env && chmod 600 .env
#
# ⚠️ MASTER_KEY 는 서버 A·B 가 반드시 동일해야 한다.
#    다르면 한쪽에서 암호화한 데이터를 다른 쪽이 복호화하지 못한다.
#
# 참고: SERVER_FORWARD_HEADERS_STRATEGY 는 주입하지 않는다.
#       passkey-app/admin-app 의 base application.yml 에
#       forward-headers-strategy: framework 가 이미 설정돼 있다.

# ---- 이미지 ----
IMAGE_TAG=0.0.1-SNAPSHOT

# ---- 외부 Oracle DB ----
DB_URL=jdbc:oracle:thin:@//db.example.com:1521/ORCLPDB1
DB_RUNTIME_USER=APP_RUNTIME_USER
DB_RUNTIME_PASSWORD=
DB_ADMIN_USER=APP_ADMIN_USER
DB_ADMIN_PASSWORD=
# Flyway 전용(admin-app 만 사용)
DB_OWNER_USER=APP_OWNER
DB_OWNER_PASSWORD=

# ---- Redis (서버 C) ----
REDIS_HOST=10.0.0.30
REDIS_PORT=6379
REDIS_PASSWORD=

# ---- 시크릿 ----
# openssl rand -base64 32
MASTER_KEY=

# ---- 도메인 ----
PASSKEY_SERVER_NAME=dev-passkey.crosscert.com
ISSUER_BASE=https://dev-passkey.crosscert.com
ADMIN_INVITE_BASE_URL=https://admin.crosscert.com

# ---- admin-app (서버 A 에서만 admin profile 지정) ----
ADMIN_BIND_ADDR=127.0.0.1
ADMIN_HOST_PORT=8081
MDS_LEASE_HOLDER=admin-a

# ---- 리소스 ----
PASSKEY_MEM_LIMIT=1g
ADMIN_MEM_LIMIT=1g
RP_MEM_LIMIT=1g

# ---- QA 전용 (prod 에서는 비워둔다) ----
RP_SERVER_NAME=rp-dev.crosscert.com
RP_PROFILE=qa
RP_PASSKEY_BASE_URL=http://passkey-app:8080
RP_API_KEY=
RP_TENANT_ID=
```

- [ ] **Step 3: compose 문법과 profile 분기 검증**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/deploy
cp .env.example .env
# prod(서버 A) 구성: nginx + passkey-app + admin-app, rp-app 없어야 함
COMPOSE_PROFILES=proxy,admin docker compose config --services | sort
```

Expected (정확히 3줄):
```
admin-app
nginx
passkey-app
```

- [ ] **Step 4: 서버 B 와 QA 구성도 검증**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/deploy
echo "--- 서버 B (proxy only) ---"
COMPOSE_PROFILES=proxy docker compose config --services | sort
echo "--- QA (전체) ---"
COMPOSE_PROFILES=proxy,admin,qa docker compose config --services | sort
```

Expected:
- 서버 B: `nginx`, `passkey-app` 2줄 (admin-app 없음)
- QA: `admin-app`, `nginx`, `passkey-app`, `rp-app` 4줄

**rp-app 이 prod 구성에서 나타나면 실패다** — profile 격리가 깨진 것이다.

- [ ] **Step 5: 임시 .env 정리 후 커밋**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
rm -f deploy/.env
git add deploy/docker-compose.yml deploy/.env.example
git status --short   # deploy/.env 가 목록에 없어야 한다
git commit -m "feat(deploy): 앱 스택 compose + 환경변수 템플릿 추가

profile 로 서버 역할 분기(proxy/admin/qa) — 파일 하나를 서버 A·B·QA 가 공유한다.
passkey-app 은 호스트 포트를 잡지 않아 --scale 충돌이 없고, admin-app 은
127.0.0.1 기본 바인딩으로 외부 노출을 fail-closed 로 막는다.
Flyway 는 admin-app 에만 주입해 마이그레이션 중복 실행을 차단."
```

---

### Task 7: Redis 전용 compose (서버 C)

**Files:**
- Create: `deploy/docker-compose.redis.yml`

**Interfaces:**
- Consumes: 없음 (독립 스택)
- Produces: 서버 C 에서 인증이 걸린 Redis 6379. Task 6 의 앱들이 `REDIS_HOST`/`REDIS_PASSWORD` 로 접속한다.

앱 스택과 **파일을 분리하는 이유**: 서버 A 의 앱을 배포할 때 `docker compose down` 이 Redis 를 같이 내리면 양쪽 서버의 인증이 멈춘다. 생명주기를 물리적으로 분리한다.

- [ ] **Step 1: Redis compose 작성**

```yaml
# Redis 전용 스택 — 서버 C 에서 단독 기동한다.
# 앱 스택(docker-compose.yml)과 파일을 분리해 앱 배포가 Redis 를 건드릴 수
# 없게 한다. 서버 A·B 의 passkey-app 이 같은 Redis 를 봐야 하므로
# (챌린지/세션 공유) 절대 여러 개로 늘리지 않는다.
#
#   docker compose -f docker-compose.redis.yml up -d
#
# ⚠️ Redis 기본 설정은 인증이 없다. 네트워크에 노출되므로 requirepass 와
#    방화벽(서버 A·B IP 만 6379 허용)이 필수다.

name: passkey-redis

services:
  redis:
    image: redis:7-alpine
    restart: unless-stopped
    deploy:
      replicas: 1        # 스케일 금지 — 인스턴스가 갈리면 인증이 깨진다
    ports:
      # 기본값을 내부 IP 로 두지 않고 명시를 강제한다(fail-closed).
      - "${REDIS_BIND_ADDR:?REDIS_BIND_ADDR 를 내부 IP 로 지정하세요}:6379:6379"
    command: >
      redis-server
      --requirepass ${REDIS_PASSWORD:?REDIS_PASSWORD 가 필요합니다}
      --appendonly yes
      --maxmemory ${REDIS_MAXMEMORY:-512mb}
      --maxmemory-policy allkeys-lru
    volumes:
      # appendonly 파일 영속 — 재기동해도 세션/챌린지가 보존된다
      - redis-data:/data
    healthcheck:
      test: ["CMD-SHELL", "redis-cli -a \"$$REDIS_PASSWORD\" ping | grep -q PONG"]
      interval: 10s
      timeout: 3s
      retries: 5
    environment:
      REDIS_PASSWORD: ${REDIS_PASSWORD}

volumes:
  redis-data:
```

`${VAR:?message}` 문법: 값이 없으면 compose 가 **에러와 함께 중단**한다. 비밀번호 없이 Redis 가 열리는 사고를 구조적으로 막는다.

- [ ] **Step 2: 비밀번호 없이 기동 시 거부되는지 검증 (fail-closed 확인)**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/deploy
env -u REDIS_PASSWORD -u REDIS_BIND_ADDR \
  docker compose -f docker-compose.redis.yml --env-file /dev/null config 2>&1 | tail -5
```

Expected: `REDIS_BIND_ADDR 를 내부 IP 로 지정하세요` 또는 `REDIS_PASSWORD 가 필요합니다` 에러로 중단. **성공하면 안 된다.**

- [ ] **Step 3: 값을 주면 정상 기동하고 인증이 걸리는지 검증**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/deploy
REDIS_BIND_ADDR=127.0.0.1 REDIS_PASSWORD=testpw123 \
  docker compose -f docker-compose.redis.yml up -d
sleep 5
# 비밀번호 없이 접속 → 거부되어야 한다
docker compose -f docker-compose.redis.yml exec -T redis redis-cli ping 2>&1 | head -2
# 비밀번호로 접속 → PONG
docker compose -f docker-compose.redis.yml exec -T redis redis-cli -a testpw123 ping 2>&1 | grep PONG
```

Expected:
- 첫 번째: `NOAUTH Authentication required` (인증이 실제로 걸림)
- 두 번째: `PONG`

- [ ] **Step 4: 정리 후 커밋**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/deploy
REDIS_BIND_ADDR=127.0.0.1 REDIS_PASSWORD=testpw123 \
  docker compose -f docker-compose.redis.yml down -v
cd ..
git add deploy/docker-compose.redis.yml
git commit -m "feat(deploy): Redis 전용 compose 추가 (서버 C)

앱 스택과 파일을 분리해 앱 배포가 Redis 생명주기를 건드리지 못하게 한다.
requirepass/bind 주소를 \${VAR:?} 로 강제해 인증 없는 노출을 fail-closed 로 차단하고,
appendonly+볼륨으로 재기동 시 세션을 보존한다."
```

---

### Task 8: QA 통합 검증 (실제 기동)

**Files:**
- 없음 (검증 전용 태스크. 문제 발견 시 이전 태스크 파일을 수정한다)

**Interfaces:**
- Consumes: Task 1~7 전부
- Produces: 스택이 실제로 뜬다는 증거. Task 9 문서화의 근거가 된다.

여기까지는 문법·구성 검증만 했다. **이 태스크에서 컨테이너를 실제로 띄운다.** 로컬 Docker(29.6.1 확인됨)와 루트 `docker-compose.yml` 의 Oracle 을 외부 DB 대역으로 사용한다.

- [ ] **Step 1: 로컬 Oracle + Redis 기동 (외부 DB 역할)**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
docker compose -p passkey2 up -d
bash scripts/wait-for-oracle.sh
bash scripts/run-bootstrap.sh
```

Expected: Oracle healthy, 부트스트랩 성공 (APP_OWNER/APP_RUNTIME_USER/APP_ADMIN_USER 생성)

`-p passkey2`: worktree 에서 실행해도 컨테이너 이름이 충돌하지 않게 project name 을 고정한다(`scripts/init-dev-db.sh` 와 동일한 이유).

- [ ] **Step 2: 배포 스택용 .env 작성**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/deploy
cp .env.example .env
chmod 600 .env
# 로컬 검증용 값으로 채운다. host.docker.internal 은 컨테이너에서 호스트를 가리킨다.
cat >> .env <<'EOF'

# ---- 로컬 검증 override ----
DB_URL=jdbc:oracle:thin:@//host.docker.internal:1521/XEPDB1
DB_RUNTIME_PASSWORD=runtime_pw
DB_ADMIN_PASSWORD=admin_pw
DB_OWNER_PASSWORD=app_owner_pw
REDIS_HOST=host.docker.internal
REDIS_PASSWORD=
MASTER_KEY=dGVzdC1tYXN0ZXIta2V5LWZvci1sb2NhbC12ZXJpZnkxMg==
PASSKEY_SERVER_NAME=localhost
ISSUER_BASE=http://localhost
NGINX_HOST_PORT=18080
EOF
```

위 비밀번호는 실측값이다 — `scripts/bootstrap-schema.sql:72` 가
`APP_RUNTIME_USER IDENTIFIED BY runtime_pw`, `:82` 가
`APP_ADMIN_USER IDENTIFIED BY admin_pw` 로 생성한다. `APP_OWNER` 는 루트
`docker-compose.yml` 의 `APP_USER_PASSWORD:-app_owner_pw` 기본값을 따른다.

로컬 Redis 는 인증이 없으므로 `REDIS_PASSWORD=` 를 빈 값으로 둔다.
`host.docker.internal` 은 Docker Desktop(macOS)에서 컨테이너가 호스트를
가리키는 이름이다. Linux 서버에서 검증한다면 `extra_hosts` 를 추가하거나
호스트 내부 IP 를 직접 쓴다.

- [ ] **Step 3: prod 구성(서버 A)으로 실제 기동**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/deploy
COMPOSE_PROFILES=proxy,admin docker compose up -d
sleep 90    # admin-app 의 Flyway 마이그레이션 시간 포함
docker compose ps
```

Expected: nginx / passkey-app / admin-app 3개가 `running`. passkey-app 과 admin-app 은 `healthy` (healthcheck 통과).

`unhealthy` 이거나 재시작 루프면 로그를 확인한다:

```bash
docker compose logs --tail=50 passkey-app
docker compose logs --tail=50 admin-app
```

- [ ] **Step 4: nginx 를 통한 실제 요청 검증 (핵심)**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/deploy
# nginx → passkey-app 라우팅
curl -s -o /dev/null -w "nginx→passkey health: %{http_code}\n" \
  -H "Host: localhost" http://localhost:18080/actuator/health
# admin-app 직접(내부망 바인딩 확인)
curl -s -o /dev/null -w "admin health: %{http_code}\n" \
  http://127.0.0.1:8081/actuator/health
# admin-ui 정적 자산이 서빙되는지
curl -s -o /dev/null -w "admin-ui: %{http_code}\n" http://127.0.0.1:8081/admin/
```

Expected: 세 요청 모두 `200`

`admin-ui: 404` 면 Task 3 Step 3 의 번들 검증을 다시 확인한다.

- [ ] **Step 5: `--scale` 이 실제로 동작하는지 검증 (핵심)**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/deploy
COMPOSE_PROFILES=proxy,admin docker compose up -d --scale passkey-app=3
sleep 60
docker compose ps --format '{{.Service}}' | sort | uniq -c
```

Expected: `passkey-app` 이 3개. **포트 충돌 에러가 나면 안 된다** — 나면 compose 에 `ports:` 매핑이 남아 있는 것이다(Task 6 Step 1 의 주석 처리 확인).

```bash
# nginx 가 3개로 분산하는지 — 여러 번 요청해 서로 다른 컨테이너가 응답하는지 확인
for i in $(seq 1 6); do
  curl -s -H "Host: localhost" http://localhost:18080/actuator/health -o /dev/null -w "%{http_code} "
done; echo
docker compose logs --tail=100 passkey-app | grep -c "GET /actuator/health" || true
```

Expected: 모두 `200`. 로그에 여러 컨테이너의 요청 기록이 남는다.

- [ ] **Step 6: QA 구성(rp-app 포함)으로 기동 검증**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/deploy
COMPOSE_PROFILES=proxy,admin,qa docker compose up -d
sleep 45
docker compose ps --services | sort
```

Expected: `admin-app`, `nginx`, `passkey-app`, `rp-app` 4개

rp-app 은 유효한 API key 가 없으면 일부 기능이 실패하지만, **컨테이너가 뜨고 health 가 UP 이면 이 태스크의 목적은 달성**이다. 실제 연동 검증은 시드된 테넌트가 필요하므로 범위 밖이다.

- [ ] **Step 7: 정리**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/deploy
docker compose --profile proxy --profile admin --profile qa down
rm -f .env
cd .. && docker compose -p passkey2 down
git status --short    # 변경 없어야 한다(검증 전용 태스크)
```

- [ ] **Step 8: 문제가 발견됐다면 수정 후 커밋**

검증에서 실패한 항목이 있으면 해당 태스크의 파일을 고치고 커밋한다. 전부 통과했으면 커밋할 것이 없다 — 그대로 Task 9 로 넘어간다.

```bash
# 수정이 있었던 경우에만
git add -A deploy/ *-app/Dockerfile
git commit -m "fix(deploy): QA 통합 검증에서 발견된 문제 수정

<발견된 문제와 수정 내용을 구체적으로 기술>"
```

---

### Task 9: 배포 문서 작성 및 기존 문서 정합

**Files:**
- Create: `deploy/README.md`
- Modify: `docs/single-instance-deployment.md` (헤더에 레거시 표기)
- Modify: `README.md` (컨테이너 배포 경로 추가)

**Interfaces:**
- Consumes: Task 8 에서 실증된 명령들
- Produces: 운영자가 따라 할 수 있는 배포 절차. 이 플랜의 최종 산출물.

- [ ] **Step 1: `deploy/README.md` 작성**

```markdown
# 컨테이너 배포 가이드

설계 근거: `docs/superpowers/specs/2026-08-07-docker-deployment-design.md`

## 토폴로지

앞단 LB(TLS 종료) → 서버 A·B(nginx + passkey-app×N, A 에만 admin-app)
→ 서버 C(Redis) + 외부 Oracle DB

| 구성요소 | 위치 | 개수 |
|---|---|---|
| nginx | 서버 A·B | 각 1 |
| passkey-app | 서버 A·B | 각 N |
| admin-app | 서버 A | 1 |
| redis | 서버 C | 1 |
| rp-app | QA 만 | 1 |

## 1. 이미지 빌드

리포지토리 루트에서 실행한다(빌드 컨텍스트가 루트다).

```bash
docker build -t passkey-app:0.0.1-SNAPSHOT -f passkey-app/Dockerfile .
docker build -t admin-app:0.0.1-SNAPSHOT   -f admin-app/Dockerfile .
docker build -t rp-app:0.0.1-SNAPSHOT      -f rp-app/Dockerfile .    # QA 만
```

`latest` 태그는 쓰지 않는다 — 서버별로 어떤 버전이 도는지 추적할 수 없게 된다.

## 2. 이미지 전달

레지스트리가 있는 경우:

```bash
docker tag passkey-app:0.0.1-SNAPSHOT <registry>/passkey-app:0.0.1-SNAPSHOT
docker push <registry>/passkey-app:0.0.1-SNAPSHOT
# 각 서버에서
docker compose pull && docker compose up -d
```

레지스트리가 없는 경우:

```bash
docker save passkey-app:0.0.1-SNAPSHOT | ssh 서버A 'docker load'
```

## 3. Redis 기동 (서버 C, 최초 1회)

```bash
export REDIS_BIND_ADDR=<서버C 내부IP>
export REDIS_PASSWORD=<강한 비밀번호>
docker compose -f docker-compose.redis.yml up -d
```

방화벽에서 서버 A·B 의 IP 만 6379 를 허용한다.

## 4. 앱 스택 기동

각 서버에서 `.env.example` 을 복사해 값을 채운다.

```bash
cp .env.example .env && chmod 600 .env
```

**서버 A** (admin-app 포함):
```bash
COMPOSE_PROFILES=proxy,admin docker compose up -d --scale passkey-app=3
```

**서버 B**:
```bash
COMPOSE_PROFILES=proxy docker compose up -d --scale passkey-app=3
```

**QA** (단일 호스트, rp-app 포함):
```bash
COMPOSE_PROFILES=proxy,admin,qa docker compose up -d
```

**nginx 를 빼고 호스트 nginx 를 쓰려면** `proxy` profile 을 빼고,
`docker-compose.yml` 의 passkey-app `ports:` 주석을 해제한다. 단 이 경우
호스트 포트가 고정되므로 `--scale` 은 1 로 제한된다.

## 5. 무중단 배포 (수동 블루/그린)

compose 에는 롤링 업데이트가 없다. passkey-app 이 무상태라 아래가 가능하다.

```bash
docker compose up -d --scale passkey-app=6 --no-recreate   # 신버전 3개 추가
docker compose ps                                          # healthy 확인
docker compose up -d --scale passkey-app=3                 # 구버전 정리
```

admin-app 은 1개라 이 방식이 불가능하다. 재기동 시 수 초~수십 초 중단되나
운영자 콘솔이므로 수용한다.

## 6. 배포 체크리스트

- [ ] 외부 Oracle 에 APP_OWNER / APP_RUNTIME_USER / APP_ADMIN_USER 부트스트랩 완료
      (`scripts/init-db-external.sh` 또는 `scripts/bootstrap-external.sql`)
- [ ] 서버 A·B 의 `MASTER_KEY` 가 **동일한 값**인지 확인
- [ ] 앞단 LB 가 클라이언트발 `X-Forwarded-*` 헤더를 스트립하는지 확인
- [ ] LB → nginx 로 `X-Forwarded-Proto: https` 가 전달되는지 확인
- [ ] 서버 C 방화벽이 서버 A·B 만 6379 허용
- [ ] 서버 A 에만 `admin` profile 지정 (B 에 중복 기동 금지)
- [ ] prod 에서 `qa` profile 미지정 (rp-app 배제)
- [ ] `.env` 권한 600, `git status` 에 나타나지 않음
- [ ] DNS: passkey 서브도메인이 LB 를 가리킴

## 7. 알려진 한계

| 항목 | 상태 |
|---|---|
| 롤링 업데이트 | 자동 없음 — §5 수동 절차 |
| 호스트 장애 | 해당 서버 인스턴스 전부 중단, LB 가 다른 서버로 우회 |
| **Redis SPOF** | 죽으면 인증 중단. 단 영구 데이터는 Oracle 에 있어 손실 없음 |
| 시크릿 | `.env` 평문 — 파일 권한으로 보호 |

## 8. 트러블슈팅

**passkey-app 이 부팅 직후 종료** — prod 프로필은 DB/Redis env 가 비면
의도적으로 fail-fast 한다. `docker compose logs passkey-app` 에서
`Failed to configure a DataSource` 를 확인하고 `.env` 를 점검한다.

**`--scale` 시 포트 충돌** — passkey-app 에 `ports:` 매핑이 살아 있는지
확인한다. nginx 를 쓰는 구성에서는 주석 처리돼 있어야 한다.

**로그인 후 즉시 로그아웃 / WebAuthn origin 오류** — `X-Forwarded-Proto` 가
앱까지 전달되지 않는 경우다. LB 설정과 `nginx.conf` 의
`proxy_set_header X-Forwarded-Proto $http_x_forwarded_proto;` 를 확인한다.
`$scheme` 으로 되어 있으면 안 된다.

**challenge not found** — 서버 A·B 가 서로 다른 Redis 를 보고 있는 경우다.
양쪽 `.env` 의 `REDIS_HOST` 가 같은 서버 C 를 가리키는지 확인한다.
```

- [ ] **Step 2: `docs/single-instance-deployment.md` 에 레거시 표기 추가**

파일 최상단 제목 바로 아래에 삽입한다:

```markdown
> **⚠️ 이 문서는 호스트에 JDK 를 설치하고 `gradlew bootRun` 으로 기동하는
> 단일 인스턴스 테스트 환경 절차입니다.**
> 컨테이너 기반 운영 배포는 [`deploy/README.md`](../deploy/README.md) 를 참고하세요.
> 서브도메인 분리 근거(WebAuthn rpId)와 환경변수 설명은 이 문서가 여전히 유효합니다.
```

- [ ] **Step 3: 루트 `README.md` 에 컨테이너 배포 경로 추가**

`### 1) 인프라 기동` 섹션 앞에 삽입한다:

```markdown
> **운영 배포는 컨테이너로 합니다** — [`deploy/README.md`](deploy/README.md).
> 아래는 로컬 개발 절차입니다(호스트 JDK + `gradlew bootRun`).
```

- [ ] **Step 4: 커밋**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git add deploy/README.md docs/single-instance-deployment.md README.md
git commit -m "docs(deploy): 컨테이너 배포 가이드 추가 + 기존 문서 정합

deploy/README.md 에 이미지 빌드→전달→기동→무중단배포→체크리스트→트러블슈팅을
수록. single-instance-deployment.md 는 호스트 JDK 기동 전제이므로 레거시로
표기하고 컨테이너 경로를 안내. 루트 README 도 동일하게 갱신."
```

---

## Self-Review

**1. 스펙 커버리지**

| 스펙 섹션 | 구현 태스크 |
|---|---|
| §5 파일 배치 | Task 1(gitignore), 2~4(Dockerfile), 5~7(compose/nginx) |
| §5.1 gitignore 예외 | Task 1 |
| §6 이미지 빌드 전략 | Task 2, 3, 4 |
| §7.1 profile 역할 분담 | Task 6 Step 3·4 (검증 포함) |
| §7.2 nginx 제거 가능성 | Task 6 Step 1 (ports 주석), Task 9 §4 문서화 |
| §7.3 앱 포트 비노출 | Task 6 Step 1, Task 8 Step 5 (scale 검증) |
| §7.4 nginx 헤더 처리 | Task 5 |
| §8 환경변수 | Task 6 Step 2 (.env.example) |
| §9 한계 | Task 9 §7 문서화 |
| §10.1 이미지 배포 | Task 9 §2 |
| §10.2 무중단 배포 | Task 9 §5 |
| §10.3 헬스체크 | Task 6 Step 1 (healthcheck 블록) |
| §10.4 Redis 보안 | Task 7 |
| §11 배포 체크리스트 | Task 9 §6 |
| §12 문서 갱신 | Task 9 Step 2·3 |

누락 없음. §4(앱 변경 없음)는 Global Constraints 로 반영.

**2. 플레이스홀더 스캔** — "TBD"/"적절히 처리"/"Task N 과 유사" 없음. 모든 파일 내용을 전문 수록.

**3. 타입/이름 일관성**

- 이미지명: `passkey-app` / `admin-app` / `rp-app` + `:0.0.1-SNAPSHOT` — Task 2~4 와 Task 6 의 `image:` 일치
- compose 서비스명: `passkey-app`, `admin-app`, `rp-app`, `nginx`, `redis` — Task 5 의 `proxy_pass` 대상과 Task 6 서비스명 일치
- 환경변수: Task 6 compose 의 `${...}` 와 `.env.example` 키 일치 확인 (`DB_URL`, `DB_RUNTIME_USER/PASSWORD`, `DB_ADMIN_USER/PASSWORD`, `DB_OWNER_USER/PASSWORD`, `REDIS_HOST/PORT/PASSWORD`, `MASTER_KEY`, `ISSUER_BASE`, `PASSKEY_SERVER_NAME`, `RP_SERVER_NAME`, `ADMIN_BIND_ADDR`, `ADMIN_HOST_PORT`, `MDS_LEASE_HOLDER`, `ADMIN_INVITE_BASE_URL`, `IMAGE_TAG`, `*_MEM_LIMIT`, `*_JAVA_OPTS`, `RP_*`)
- 포트: passkey-app 8080 / admin-app 8081 / rp-app 9090 — 각 앱 `application.yml` 실측값과 일치

**4. 스펙과의 의도적 차이 1건**

스펙 §8 의 `SERVER_FORWARD_HEADERS_STRATEGY=FRAMEWORK` 주입을 **하지 않는다.**
`forward-headers-strategy: framework` 가 base `application.yml` 에 이미 있기
때문이다(`passkey-app:19`, `admin-app:52`). `.env.example` 에 주석으로 근거를 남긴다.
