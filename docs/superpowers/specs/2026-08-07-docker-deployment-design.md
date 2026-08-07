# Docker 컨테이너 배포 설계

- 작성일: 2026-08-07
- 상태: 설계 확정 (구현 전)

## 1. 목적

passkey-app / admin-app / rp-app 을 각각 독립 Docker 컨테이너로 기동한다.
DB 는 compose 밖의 외부 Oracle 에 연결하고, 운영(prod)까지 docker compose 로
운영한다.

현재는 `docs/single-instance-deployment.md` 기준으로 호스트에 JDK 17 을 설치하고
`./gradlew bootRun` 으로 3개 서버를 수동 기동한다. 이 설계는 그 절차를 컨테이너
이미지 배포로 대체한다.

### 배경 — 왜 k8s 가 아닌 compose 인가

- 컨테이너 5종 / 호스트 3대 규모로, 멀티노드 스케줄링이 본질인 k8s 의
  손익분기점에 못 미친다.
- `deployment.mode=onprem` 이 존재한다. 고객사에 "docker compose up" 은
  성립하지만 "k8s 클러스터를 준비하라" 는 납품 장벽이 된다.
- 앱이 이미 컨테이너 친화적이다. prod 프로필이 모든 값을 `${ENV:}` 빈 폴백으로
  받아 fail-fast 하고(§4), passkey-app 은 완전 무상태다(§2.1).

compose 로 포기하는 것은 §9 에 명시한다. 설계는 k8s 이전 경로를 막지 않는다 —
설정은 전량 환경변수, 이미지는 무상태, 헬스체크는 `/actuator/health` 라서
ConfigMap/Secret/Probe 로 그대로 옮겨진다.

## 2. 스케일링 정책의 코드 근거

인스턴스 수는 임의 결정이 아니라 코드가 허용하는 범위에서 정했다.

### 2.1 passkey-app — N 개 (유일한 스케일 대상)

완전 무상태다. WebAuthn 챌린지를 전부 Redis 에 저장하고
(`passkey-app/src/main/java/com/crosscert/passkey/app/fido2/challenge/ChallengeStore.java`),
`takeRegistration` / `takeAuthentication` 이 **GETDEL 원자 연산**을 쓴다. 같은
토큰으로 두 요청이 동시에 와도 한쪽만 값을 본다 — 애초에 다중 인스턴스 동시성을
전제한 코드다. 로컬 상태가 없으므로 자유롭게 늘린다.

### 2.2 admin-app — 1 개 고정

다중 인스턴스에서도 안전하긴 하다. 스케줄러 5개(MDS 동기화, 보존 퍼지, 키 회전,
키 만료)가 DB 기반 리스로 중복 실행을 막는다:

```java
if (!leases.tryAcquire(LEASE_NAME, holder, Duration.ofMinutes(5))) {
    log.info("mds sync skipped: reason=lease-held");   // MdsSchedulerService.java:77
```

세션도 Redis 스토어라 인스턴스 간 공유된다. 그러나 트래픽이 운영자 몇 명
수준이라 늘릴 이유가 없다. **1 개로 고정하고 서버 A 에만 배치한다.**

향후 2개 이상으로 늘릴 경우 리스 holder 가 `${passkey.mds.lease-holder:default}`
기본값이라 양쪽 다 `default` 가 되어 소유자 구분이 흐려진다. 지금부터
`PASSKEY_MDS_LEASE_HOLDER=admin-a` 를 명시 주입해 대비한다.

### 2.3 rp-app — QA 전용 1 개

고객사가 참고해 자사에 맞게 변형하는 샘플 앱이다. 소스 형태의 레퍼런스는
`../passkey_rp/` standalone 프로젝트와 README 가 이미 제공하므로, 배포용
이미지를 별도로 만들 이유가 없다. **prod 스택에서 제외**하고 QA 에서만 띄운다.

Redis 를 쓰지 않는 유일한 앱이다(설정에 redis 언급 0건, `spring-session-core`
인메모리 세션).

### 2.4 redis — 1 개 고정 (스케일 금지)

컨테이너를 N개로 늘리면 **서로 데이터를 공유하지 않는 독립 인스턴스 N개**가 된다.
passkey-app 이 챌린지를 redis#1 에 저장했는데 `/finish` 가 redis#2 를 보면
"challenge not found" 로 인증이 실패한다. 세션도 요청마다 풀린다.

compose 의 `--scale` 은 서비스별 지정이라 자동 복제되지는 않지만,
`deploy: replicas: 1` 을 명시해 의도를 못박고 운영 스크립트를
`--scale passkey-app=N` 으로 고정한다.

## 3. 배포 토폴로지

```
                    앞단 LB (기존 인프라, TLS 종료)
              ┌───────────┴───────────┐
              ▼                       ▼
   ┌─────────────────────┐  ┌─────────────────────┐
   │ 서버 A               │  │ 서버 B               │
   │  nginx (평문 :80)    │  │  nginx (평문 :80)    │
   │  passkey-app × N    │  │  passkey-app × N    │
   │  admin-app × 1      │  │  (admin 없음)        │
   └──────────┬──────────┘  └──────────┬──────────┘
              └────────┬───────────────┘
                       │
        ┌──────────────┴──────────────┐
        ▼                             ▼
  ┌───────────────┐          ┌────────────────┐
  │ 서버 C: Redis  │          │  외부 Oracle DB │
  │ (독립 compose) │          │  (compose 밖)   │
  └───────────────┘          └────────────────┘
```

| 구성요소 | 위치 | 개수 | 비고 |
|---|---|---|---|
| 앞단 LB | 기존 인프라 | — | TLS 종료 담당 |
| nginx | 서버 A·B | 각 1 | 컨테이너. TLS 없음(평문) |
| passkey-app | 서버 A·B | 각 N | 유일한 스케일 대상 |
| admin-app | 서버 A | 1 | 내부망 전용, 외부 미노출 |
| rp-app | QA 호스트 | 1 | prod 제외 |
| redis | 서버 C | 1 | 별도 호스트, 독립 compose |
| Oracle DB | 외부 | — | 앱별 다른 유저 |

### 3.1 Redis 를 별도 호스트에 두는 이유

두 서버의 passkey-app 이 **같은 Redis** 를 봐야 한다. 서버 A 에서 챌린지를 만들고
`/finish` 가 서버 B 로 가면 인증이 실패하기 때문이다.

서버 A 에 얹는 방안은 배포 사고 위험이 있다. A 의 앱을 배포할 때
`docker compose down` 을 치면 Redis 가 같이 내려가 **양쪽 서버의 인증이 멈춘다.**
앱 배포는 자주 일어나므로 사고 확률이 실질적이다. 별도 호스트 + 독립 compose
파일로 생명주기를 물리적으로 분리한다.

Sentinel/Cluster 는 채택하지 않는다. Redis 에 담기는 것이 전부 재생성 가능한
휘발성 상태이기 때문이다:

| 용도 | 날아가면 | 심각도 |
|---|---|---|
| WebAuthn 챌린지 (TTL 5분) | 진행 중 등록/인증만 실패, 재시도로 해소 | 낮음 |
| 관리자 세션 | 운영자 재로그인 | 낮음 |
| MDS 캐시 | 외부에서 재생성 | 낮음 |
| rate limit 카운터 | 초기화(일시적으로 제한 느슨) | 낮음 |

영구 데이터는 전부 Oracle 에 있다. 5분짜리 챌린지를 위해 3노드 클러스터를
운영하는 것은 비용 대비 효과가 맞지 않는다. Redis 가 SPOF 라는 점은 §9 에
명시한다.

### 3.2 서브도메인 분리 유지

WebAuthn `rpId` 는 도메인 기준으로 묶이고 포트를 무시한다. 같은 도메인에 포트만
다르게 두면 RP 서버와 Passkey 서버가 같은 `rpId` 를 공유해 신뢰 경계와 ID Token
issuer 가 모호해진다. `docs/single-instance-deployment.md` 에서 이미 검증된
제약이며 컨테이너로 옮겨도 그대로 유지한다. nginx 가 `server_name` 으로
라우팅한다.

## 4. 애플리케이션 변경 없음

prod 프로필이 이미 컨테이너 친화적이다. 3개 앱 모두 DB/Redis/포트/시크릿을
`${ENV:}` **빈 폴백**으로 받는다 — 미주입 시 부팅이 즉시 실패하는 의도된
fail-fast 다. 따라서 이 작업은 **애플리케이션 코드를 수정하지 않는다.**

`management.endpoints.web.exposure` 가 설정에 없으나 Spring Boot 기본값이
`health` 노출이므로 `/actuator/health` 는 동작한다. actuator 는 `core` 에 있어
3개 앱 모두 사용 가능하다.

## 5. 파일 배치

```
docker-compose.yml                    ← 기존. 로컬 개발용(Oracle+Redis). 수정하지 않음
deploy/
  docker-compose.yml                  ← 신규: 앱 스택 (서버 A·B / QA)
  docker-compose.redis.yml            ← 신규: Redis 전용 (서버 C)
  .env.example                        ← 신규: 환경변수 템플릿 (커밋)
  .env                                ← 각 서버에서 생성. .gitignore 대상
  nginx/nginx.conf                    ← 신규: 서브도메인 라우팅
passkey-app/Dockerfile                ← 신규
admin-app/Dockerfile                  ← 신규
rp-app/Dockerfile                     ← 신규
.dockerignore                         ← 신규
```

**루트 `docker-compose.yml` 은 건드리지 않는다.** `scripts/init-dev-db.sh` 등
로컬 개발 흐름이 이 파일에 의존하므로, 덮어쓰면 개발 환경이 깨진다. 배포용은
`deploy/` 아래 신규 생성한다.

`deploy/` 에는 현재 빌드 산출물 jar 3개가 있다. 이미지 빌드가 멀티스테이지로
바뀌면 이 jar 들은 배포에 쓰이지 않으나, 삭제는 이 작업 범위 밖이다.

### 5.1 `.gitignore` 수정이 선행되어야 한다

현재 `.gitignore:20` 이 `deploy/` **디렉터리 전체**를 무시한다(bootJar 산출물을
모으는 용도). 이대로면 `deploy/docker-compose.yml` 과 `deploy/.env.example` 이
커밋되지 않는다. 다음 예외 규칙을 추가한다:

```gitignore
# 'deploy/' 가 아니라 'deploy/*' 여야 한다 — 아래 설명 참고
deploy/*
!deploy/docker-compose.yml
!deploy/docker-compose.redis.yml
!deploy/.env.example
!deploy/README.md
!deploy/nginx/
deploy/.env
```

**디렉터리가 아니라 내용물을 무시해야 한다(구현 중 실측 확인).** `deploy/` 로
디렉터리 노드를 무시하면 git 이 순회 단계에서 그 디렉터리를 통째로 잘라내므로
이후의 `!` 예외 패턴이 평가조차 되지 않는다 — gitignore(5) 의 "It is not
possible to re-include a file if a parent directory of that file is excluded"
가 이것이다. `deploy/*` 는 내용물만 무시하므로 git 이 안으로 들어가 negation 을
적용할 수 있다. `deploy/*` 로 바꿔도 bootJar 산출물(`deploy/*.jar`)은 그대로
무시되므로 원래 목적은 보존된다.

또한 기존 `*.env` 패턴은 `foo.env` 형태만 매칭하고 `.env` 파일 자체는 잡지
않는다. `deploy/.env` 를 명시적으로 추가해야 시크릿이 커밋되지 않는다.

검증에는 `git add --dry-run` 을 쓴다. `git check-ignore -v` 는 여러 경로를
한꺼번에 넘기면 negation 이 마지막 매칭일 때 종료코드 해석이 헷갈릴 수 있다 —
실제로 스테이징 가능한지가 우리가 알고 싶은 것이므로 `git add --dry-run` 이
모호하지 않다.

## 6. 이미지 빌드 전략

앱당 Dockerfile 1개, 총 3개. nginx / redis 는 공식 이미지 + 설정 마운트라
Dockerfile 이 없다.

```dockerfile
# --- build stage ---
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
# 의존성 레이어 캐싱: 빌드 스크립트만 먼저 복사
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY <module>/build.gradle.kts <module>/
RUN ./gradlew :<module>:dependencies --no-daemon
# 소스 복사 후 실제 빌드
COPY . .
RUN ./gradlew :<module>:bootJar --no-daemon

# --- runtime stage ---
FROM eclipse-temurin:17-jre
RUN useradd -r -u 1001 appuser
COPY --from=build /src/deploy/<module>.jar /app/app.jar
USER appuser
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
```

**설계 판단:**

1. **빌드 컨텍스트는 리포지토리 루트** — 멀티모듈이라 `core`, `webauthn`,
   `sdk-java` 소스가 필요하다. Dockerfile 은 각 모듈에 두되 `context: .` 로
   지정한다. `.dockerignore` 로 `build/`, `.git/`, `node_modules/`, `.gradle/`
   을 제외해 컨텍스트 전송을 줄인다.

2. **의존성 레이어 분리** — 빌드 스크립트만 먼저 복사해 의존성을 받아두면 소스만
   바뀐 재빌드에서 캐시된다. 초회 5~10분, 이후 1~2분대.

3. **admin-app 은 Node 설치가 불필요** — `admin-app/build.gradle.kts` 의
   `buildUi` 태스크가 `node.download=true` 로 Node 18 을 자동 다운로드해
   admin-ui 를 빌드하고 `static/admin` 으로 복사한다. **admin-ui 는 이미
   admin-app jar 에 번들되므로 별도 nginx 컨테이너가 필요 없다.** npm 캐시를
   위해 `admin-ui/package*.json` 을 의존성 레이어에 포함시킨다.

4. **JRE 런타임 + 비루트 사용자** — 최종 이미지는 JDK 가 아닌 JRE 기반이라 크기가
   절반 이하다. 비루트 실행은 컨테이너 탈출 시 피해를 줄인다.

5. **버전 태그 고정** — `passkey-app:0.0.1-SNAPSHOT` 형태. `latest` 는 금지한다.
   서버 2대에 같은 태그를 배포해야 버전 일치가 보장되고, `latest` 는 어느 서버가
   무엇을 돌리는지 추적을 불가능하게 만든다.

6. **JVM 메모리** — 컨테이너에서 JVM 이 호스트 전체 메모리를 보고 힙을 잡으면
   OOM Kill 이 난다. compose 에 `mem_limit: 1g`(기본값, `.env` 조정 가능)을 걸고
   `JAVA_OPTS=-XX:MaxRAMPercentage=75` 로 그 한도를 인식하게 한다. 한 서버에
   passkey-app N개를 띄우므로 특히 중요하다.

## 7. compose 구성

### 7.1 profile 로 서버별 역할 분담

파일 하나를 양쪽 서버에 배포하고 profile 과 `.env` 만 다르게 둔다.

| profile | 서비스 | 서버 A | 서버 B | QA |
|---|---|---|---|---|
| (기본) | passkey-app, (redis 미포함) | ✅ | ✅ | ✅ |
| `admin` | admin-app | ✅ | ❌ | ✅ |
| `proxy` | nginx | ✅ | ✅ | ✅ |
| `qa` | rp-app | ❌ | ❌ | ✅ |

```bash
# 서버 A
COMPOSE_PROFILES=proxy,admin docker compose up -d --scale passkey-app=3
# 서버 B (.env 의 REDIS_HOST 는 동일하게)
COMPOSE_PROFILES=proxy       docker compose up -d --scale passkey-app=3
# QA (단일 호스트)
COMPOSE_PROFILES=proxy,admin,qa docker compose up -d
# nginx 를 빼고 호스트 nginx 를 쓸 경우
COMPOSE_PROFILES=admin docker compose up -d
```

compose profile 은 **미지정 시 시작되지 않는 것이 기본 동작**이라, prod 에서
`qa` 를 빼면 rp-app 이 구조적으로 배제된다. 누락이 아니라 안전장치다.

### 7.2 nginx 제거 가능성

nginx 는 `profiles: ["proxy"]` 한 줄로 on/off 된다. 제거 시 호스트 nginx 가
앱에 접근해야 하므로, 앱 포트 노출을 `.env` 변수로 갈라둔다:

- nginx 사용 시: 앱 포트를 호스트에 노출하지 않음(compose 내부 통신)
- nginx 제거 시: `PASSKEY_HOST_PORT` 등으로 노출을 켬

파일 수정이 아니라 `.env` 한 줄로 전환되게 한다.

### 7.3 앱 포트 비노출

nginx 를 쓰는 기본 구성에서 passkey-app 에 `ports:` 매핑을 하지 않는다. 이유는
두 가지다:

- `--scale` 시 호스트 포트 충돌이 나지 않는다 (스케일이 동작하는 전제)
- 앱이 외부에 직접 노출되지 않는다

외부에 열리는 것은 nginx 의 80 뿐이다. admin-app 은 내부망 전용이라 nginx
라우팅에서도 제외하거나 내부 IP 제한을 건다.

### 7.4 nginx 설정 — 2단 프록시 헤더 처리

```nginx
server {
    listen 80;
    server_name dev-passkey.crosscert.com;
    location / {
        proxy_pass http://passkey-app:8080;   # Docker DNS 가 N개로 라운드로빈
        proxy_set_header Host              $host;
        proxy_set_header X-Forwarded-Proto $http_x_forwarded_proto;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    }
}
```

`proxy_pass` 에 compose 서비스명을 쓰면 Docker 내장 DNS 가 떠 있는 컨테이너
IP 전부로 응답한다. `--scale passkey-app=5` 로 늘려도 nginx 설정을 고칠 필요가
없다 — 컨테이너 nginx 를 택한 결정적 이유다.

**`X-Forwarded-Proto` 는 LB 가 보낸 값을 그대로 전달한다.** `$scheme` 을 쓰면
nginx 가 받은 `http` 로 덮어써서 앱이 평문으로 오판하고, secure 쿠키와 WebAuthn
origin 검증이 깨진다. LB → nginx → 앱 2단 프록시의 핵심 함정이다.

## 8. 환경변수 설계

앱별로 DB 유저가 다르다 (`docs/single-instance-deployment.md` 의 분리를 유지).

| 변수 | passkey-app | admin-app |
|---|---|---|
| `SPRING_DATASOURCE_URL` | 외부 Oracle | 외부 Oracle |
| `SPRING_DATASOURCE_USERNAME` | `APP_RUNTIME_USER` | `APP_ADMIN_USER` |
| `SPRING_FLYWAY_USER` / `_PASSWORD` | — (주입 금지) | `APP_OWNER` |
| `SPRING_DATA_REDIS_HOST` | 서버 C | 서버 C |
| `SPRING_DATA_REDIS_PASSWORD` | 필수 | 필수 |
| `PASSKEY_ID_TOKEN_ISSUER_BASE` | `https://dev-passkey...` | — |
| `PASSKEY_KEY_ENVELOPE_MASTER_KEY` | 공통 (동일 값 필수) | 공통 (동일 값 필수) |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `FRAMEWORK` | `FRAMEWORK` |
| `PASSKEY_MDS_LEASE_HOLDER` | — | `admin-a` |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75` | 동일 |

**주의 지점 3가지:**

1. **마스터키는 양쪽 서버 동일해야 한다.** 다르면 한쪽에서 암호화한 데이터를
   다른 쪽이 복호화하지 못한다. `.env` 를 서버마다 따로 만들다 어긋나기 쉬운
   지점이라 배포 체크리스트에 넣는다.

2. **Flyway 는 admin-app 에만 주입한다.** 서버 A 의 admin-app 이 마이그레이션을
   담당하고, passkey-app 에는 Flyway 설정을 주지 않는다. `--scale` 로 늘어난
   passkey-app 여러 개가 동시에 마이그레이션을 시도하는 사고를 원천 차단한다.

3. **`SERVER_FORWARD_HEADERS_STRATEGY` 는 앞단 프록시가 클라이언트발
   `X-Forwarded-*` 를 스트립한다는 전제 위에서만 안전하다.** LB 설정에서 이를
   확인해야 한다. 위조 헤더를 그대로 신뢰하면 origin 판정이 조작될 수 있다.

## 9. 한계 (명시적 수용)

compose 를 택하면서 포기하는 것들이다.

| 항목 | 상태 | 완화책 |
|---|---|---|
| 롤링 업데이트 | 자동 없음 | §10.2 수동 블루/그린 |
| 호스트 장애 | 해당 서버 인스턴스 전부 중단 | LB 가 다른 서버로 넘김 |
| **Redis SPOF** | 죽으면 인증 중단 | 데이터 손실은 없음(영구 데이터는 Oracle). `appendonly` + `restart: unless-stopped` |
| 오토스케일 | 없음 | 수동 `--scale`. 이 규모에선 무의미 |
| 시크릿 관리 | `.env` 평문 | 파일 권한(600) + `.gitignore` |
| "떠 있지만 응답 없음" | compose 가 못 잡음 | healthcheck 로 상태 노출, 수동 대응 |

**단일 호스트 내 N개의 의미**: 성능 확장이 아니라 배포 유연성과 프로세스 격리다.
호스트 자원이 상한이므로 N 을 늘린다고 처리량이 비례해 늘지 않는다.

## 10. 배포 절차

### 10.1 이미지 배포

```bash
# 레지스트리 있음
docker build -t <registry>/passkey-app:0.0.1 -f passkey-app/Dockerfile .
docker push <registry>/passkey-app:0.0.1
# 각 서버에서
docker compose pull && docker compose up -d

# 레지스트리 없음
docker save passkey-app:0.0.1 | ssh 서버A 'docker load'
```

레지스트리 유무와 무관하게 이미지 설계는 동일하다. 나중에 레지스트리가 생기면
절차만 바뀐다.

### 10.2 무중단 배포 (수동 블루/그린)

compose 에 롤링 업데이트가 없으므로 절차로 대체한다. passkey-app 이 무상태라
가능하다.

```bash
docker compose up -d --scale passkey-app=6 --no-recreate   # 신버전 3개 추가
# nginx 가 6개로 분산 → /actuator/health 로 신규 인스턴스 확인
docker compose up -d --scale passkey-app=3                 # 구버전 정리
```

admin-app 은 1개라 이 방식이 불가능하다. 재기동 시 수 초~수십 초 중단되나,
운영자 콘솔이므로 수용 가능하다.

### 10.3 헬스체크

`/actuator/health` 를 compose healthcheck 에 연결한다.
`restart: unless-stopped` 로 프로세스 사망 시 자동 복구한다.

### 10.4 Redis 보안 (서버 C)

네트워크에 노출되므로 필수다. Redis 기본 설정은 인증이 없어 열린 6379 는 즉시
공격 대상이 된다.

- `requirepass` 설정 + 앱에 `SPRING_DATA_REDIS_PASSWORD` 주입
- 방화벽에서 서버 A·B 의 IP 만 6379 허용
- `bind` 를 내부 IP 로 한정
- `appendonly yes` + 볼륨 → 재기동 시 세션/챌린지 보존
- `maxmemory` + `maxmemory-policy allkeys-lru` → 메모리 폭주 방지

## 11. 배포 체크리스트

- [ ] 외부 Oracle 에 `APP_OWNER` / `APP_RUNTIME_USER` / `APP_ADMIN_USER` 부트스트랩 완료
      (`scripts/bootstrap-external.sql` 또는 `scripts/init-db-external.sh`)
- [ ] 서버 A·B 의 `PASSKEY_KEY_ENVELOPE_MASTER_KEY` 가 **동일한 값**인지 확인
- [ ] 앞단 LB 가 클라이언트발 `X-Forwarded-*` 헤더를 스트립하는지 확인
- [ ] LB → nginx 로 `X-Forwarded-Proto: https` 가 전달되는지 확인
- [ ] 서버 C 방화벽이 서버 A·B 만 6379 허용하는지 확인
- [ ] 서버 A 에만 `admin` profile 이 지정됐는지 확인 (B 에 중복 기동 금지)
- [ ] prod 에서 `qa` profile 이 지정되지 않았는지 확인 (rp-app 배제)
- [ ] `.env` 파일 권한 600, `.gitignore` 등록 확인 (§5.1 — `deploy/` 예외 규칙 적용
      후 `git check-ignore -v deploy/.env` 로 시크릿이 무시되는지 검증)
- [ ] DNS: passkey 서브도메인이 LB 를 가리키는지 확인

## 12. 문서 갱신 대상

- `docs/single-instance-deployment.md` — 호스트 nginx + `gradlew bootRun` 전제라
  현 구성과 어긋난다. 컨테이너 기반으로 갱신하거나 "레거시(단일 인스턴스 테스트
  환경)" 로 명시한다.
- `README.md` — 기동 절차에 컨테이너 배포 경로를 추가한다. 로컬 개발 절차
  (루트 `docker-compose.yml` + `gradlew bootRun`)는 그대로 유지한다.

## 13. 범위 밖

- 앱 코드 수정 (§4 — 불필요)
- 루트 `docker-compose.yml` 변경 (로컬 개발 환경 보존)
- `deploy/*.jar` 정리
- CI 파이프라인 구축
- k8s 매니페스트
- rp-app 고객사 배포용 이미지 (§2.3)
