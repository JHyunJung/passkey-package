# Docker 배포 — 미완 검증 (Task 8)

- 작성일: 2026-08-07
- 관련 스펙: `docs/superpowers/specs/2026-08-07-docker-deployment-design.md`
- 관련 계획: `docs/superpowers/plans/2026-08-07-docker-deployment.md`
- 브랜치: `worktree-docker-deployment` (17커밋)

## 요약

계획의 Task 1~7, 9 는 완료했다. **Task 8(실제 기동 통합 검증)만 미완이다.**

구현 도중 Docker 데몬이 응답 불능 상태가 되어(`_ping` Internal Server Error →
`context canceled`) 이미지 빌드와 컨테이너 기동을 한 번도 하지 못했다. 프로세스
(`com.docker.backend`)와 소켓(`~/.docker/run/docker.sock`)은 살아 있으나 API 가
응답하지 않는다. Docker Desktop 재시작 후에도 20분 이상 동일했다.

**이 브랜치의 배포 자산은 "작성 완료 / 실행 미검증" 상태다.** 실제 배포 전에
반드시 아래 항목을 확인해야 한다.

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

## 미검증 — Task 8 에서 반드시 확인할 것

### 1. 이미지 빌드 (3개 모두 한 번도 빌드된 적 없음)

※ 위 "부분 검증" 에 따라 Gradle 단계는 확인됨. 남은 것은 Docker 레이어
(베이스 이미지 pull, COPY, 런타임 스테이지) 다.

```bash
docker build -t passkey-app:0.0.1-SNAPSHOT -f passkey-app/Dockerfile .
docker build -t admin-app:0.0.1-SNAPSHOT   -f admin-app/Dockerfile .
docker build -t rp-app:0.0.1-SNAPSHOT      -f rp-app/Dockerfile .
```

확인할 것:
- [ ] 세 이미지 모두 빌드 성공
- [ ] `COPY` 경로 오타 없음 (빌드 실패로 드러남)
- [ ] 런타임 사용자 uid 가 **1001** 인지 (`docker run --rm <img> id -u`)
- [ ] jar 이 올바른지 (`ls -l /app/app.jar`)
- [x] ~~**admin-app: `unzip -l /app/app.jar | grep static/admin` 이 1건 이상**~~ —
      **호스트 빌드로 확인 완료(7건)**. 이미지 안에서도 같은지는 Docker 복구 후
      재확인하되, `COPY --from=build` 는 jar 를 통째로 옮기므로 실패 가능성은 낮다.

### 2. graceful shutdown (SIGTERM 전달)

`ENTRYPOINT ["sh", "-c", "exec java ..."]` 의 `exec` 가 목적한 대로 동작하는지.
`exec` 가 없으면 `sh` 가 PID 1 이 되어 SIGTERM 이 JVM 에 전달되지 않고 10초 후
SIGKILL 된다. 무중단 배포 절차(`deploy/README.md` §5)가 이것에 의존한다.

- [ ] `docker run -d` 후 `docker stop` 이 10초를 다 쓰지 않고 빠르게 끝나는지
- [ ] 또는 컨테이너 안에서 PID 1 이 `java` 인지 확인

### 3. prod 프로필 fail-fast

env 미주입 시 부팅이 **실패해야** 정상이다(의도된 동작). 조용히 뜨면 그게 버그다.

- [ ] `docker run --rm -e SPRING_PROFILES_ACTIVE=prod passkey-app:...` → 부팅 실패

### 4. Redis 실기동

- [ ] 비인증 `redis-cli ping` → `NOAUTH Authentication required`
- [ ] 인증 후 `redis-cli -a <pw> ping` → `PONG`
- [ ] healthcheck 가 실제로 상태를 반영하는지 (항상 통과하는 healthcheck 는 무의미)
- [ ] `appendonly` 볼륨이 재기동 후 데이터를 보존하는지

### 5. 전체 스택 기동 + 스케일

계획 Task 8 절차대로. 로컬 `docker-compose.yml` 의 Oracle 을 외부 DB 대역으로 쓴다.

- [ ] `COMPOSE_PROFILES=proxy,admin docker compose up -d` → 3개 컨테이너 healthy
- [ ] admin-app 이 Flyway 마이그레이션을 완료하는지
- [ ] nginx 경유 요청 200 (`curl -H "Host: localhost" .../actuator/health`)
- [ ] `curl http://127.0.0.1:8081/admin/` → 200 (admin-ui 서빙)
- [ ] **`--scale passkey-app=3` 시 포트 충돌 없이 3개 기동** — 실패하면 compose 에
      `ports:` 매핑이 살아 있는 것
- [ ] nginx 가 3개로 분산하는지
- [ ] `COMPOSE_PROFILES=proxy,admin,qa` → rp-app 포함 4개

### 6. 회귀 가드

- [ ] 세 Dockerfile 에 `# syntax=` 지시자가 재도입되지 않았는지
      (이 환경에서 프론트엔드 이미지 pull 이 `DeadlineExceeded` 로 실패해 빌드를 막는다)

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
