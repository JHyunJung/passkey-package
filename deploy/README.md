# 컨테이너 배포 가이드

설계 근거: [`docs/superpowers/specs/2026-08-07-docker-deployment-design.md`](../docs/superpowers/specs/2026-08-07-docker-deployment-design.md)

> **상태**: 이 문서는 의도된 배포 절차를 기술한다. 이미지 빌드와 스택 기동은
> 아직 end-to-end 로 실증되지 않았다(Docker 데몬 이슈로 작업 중단). 실제
> 기동 검증은 별도 단계(QA 통합 검증)에서 수행한다. 절차를 따르다 문제가
> 있으면 [7. 트러블슈팅](#7-트러블슈팅)과 실제 로그를 함께 확인한다.

## 토폴로지

```
                      앞단 LB (TLS 종료)
                            │
              ┌─────────────┴─────────────┐
              ▼                           ▼
        서버 A                        서버 B
   nginx + passkey-app×N          nginx + passkey-app×N
   + admin-app (1개)
              │                           │
              └─────────────┬─────────────┘
                             ▼
                    서버 C — Redis (1개)
                             │
                             ▼
                    외부 Oracle DB (기존 인프라)
```

- 서버 A·B 의 모든 passkey-app 인스턴스가 **같은 Redis** 를 봐야 한다 — WebAuthn
  challenge 와 관리자 세션이 Redis 에 있으므로, 서버가 다른 Redis를 보면 등록/인증
  중간에 challenge 를 잃는다.
- admin-app 은 **서버 A 에만 1개**만 둔다 — 스케줄러(MDS 갱신 등)가 DB 리스로
  단일 소유자를 가정한다.
- Oracle DB 는 이 스택 밖의 기존 외부 인프라를 그대로 쓴다. 여기서는 부트스트랩되어
  있다고 전제한다.

| 구성요소 | 위치 | 개수 |
|---|---|---|
| nginx | 서버 A·B | 각 1 |
| passkey-app | 서버 A·B | 각 N (`--scale`) |
| admin-app | 서버 A | 1 |
| redis | 서버 C | 1 |
| rp-app | QA 만 | 1 (prod 미기동) |

profile 로 서버 역할을 하나의 `docker-compose.yml` 에서 나눈다(`deploy/docker-compose.yml` 상단 주석 참고):

| 서버 | `COMPOSE_PROFILES` |
|---|---|
| A (운영 콘솔 포함) | `proxy,admin` |
| B | `proxy` |
| QA (단일 호스트, rp-app 포함) | `proxy,admin,qa` |

## 1. 이미지 빌드

리포지토리 루트에서 실행한다 — 멀티모듈 빌드라 `core`/`webauthn` 소스가 빌드
컨텍스트에 포함돼야 한다.

```bash
docker build -t passkey-app:0.0.1-SNAPSHOT -f passkey-app/Dockerfile .
docker build -t admin-app:0.0.1-SNAPSHOT   -f admin-app/Dockerfile .
docker build -t rp-app:0.0.1-SNAPSHOT      -f rp-app/Dockerfile .    # QA 만
```

각 Dockerfile 은 `eclipse-temurin:17-jdk` 로 Gradle 빌드 → `eclipse-temurin:17-jre`
런타임으로 넘기는 멀티스테이지 구성이다. bootJar 산출물은 `deploy/<module>.jar` 로
떨어지도록 각 모듈 `build.gradle.kts` 에서 지정돼 있고, 런타임 이미지는 비루트
사용자(uid 1001)로 실행한다.

이미지 태그는 `<module>:0.0.1-SNAPSHOT` 처럼 버전을 명시한다. **`latest` 는 쓰지
않는다** — 서버별로 어떤 버전이 도는지 추적할 수 없게 된다. 새 버전을 배포할 때는
`.env` 의 `IMAGE_TAG` 를 올리고 태그를 그 값에 맞춰 빌드한다.

세 Dockerfile 모두 `# syntax=docker/dockerfile:1` 지시자를 **의도적으로 쓰지
않는다.** 이 지시자를 넣으면 buildx 가 `docker.io` 에서 별도 프론트엔드 이미지를
pull 하는데, 이 환경에서는 그 pull 이 `DeadlineExceeded` 로 실패해 Dockerfile
파싱 이전 단계에서 빌드가 막힌다. 세 이미지는 `RUN --mount`, `COPY --link` 같은
BuildKit 전용 문법을 쓰지 않으므로 지시자가 없어도 잃는 기능이 없다. 앞으로
Dockerfile 을 수정할 때도 이 지시자를 다시 넣지 않도록 주의한다.

## 2. 이미지 전달

레지스트리 사용 여부는 아직 정해지지 않았다. 두 경로 모두 문서화한다.

**레지스트리가 있는 경우**

```bash
docker tag passkey-app:0.0.1-SNAPSHOT <registry>/passkey-app:0.0.1-SNAPSHOT
docker push <registry>/passkey-app:0.0.1-SNAPSHOT
# admin-app, rp-app(QA)도 동일하게 tag+push

# 각 서버에서
docker compose --env-file .env pull
docker compose --env-file .env up -d
```

**레지스트리가 없는 경우** — 이미지를 파일로 저장해 서버로 직접 전달한다.

```bash
docker save passkey-app:0.0.1-SNAPSHOT admin-app:0.0.1-SNAPSHOT \
  | ssh 서버A 'docker load'
docker save passkey-app:0.0.1-SNAPSHOT \
  | ssh 서버B 'docker load'
```

서버 A 는 admin-app 이미지도 필요하지만 서버 B 는 passkey-app 만 있으면 된다.

## 3. Redis 기동 (서버 C, 최초 1회)

`deploy/docker-compose.redis.yml` 은 앱 스택과 별도 파일이다 — 앱을 재배포해도
Redis 컨테이너가 영향받지 않도록 분리했다. `REDIS_BIND_ADDR`, `REDIS_PASSWORD` 는
`${VAR:?...}` 로 선언돼 있어 값을 채우지 않으면 인증 없는 Redis 가 뜨는 대신
**에러로 즉시 중단**된다(fail-closed, 실측 확인).

```bash
cd deploy
export REDIS_BIND_ADDR=<서버C 내부IP>       # 예: 10.0.0.30 — 0.0.0.0 금지
export REDIS_PASSWORD=<강한 비밀번호>
docker compose -f docker-compose.redis.yml up -d
```

방화벽에서 서버 A·B 의 IP 만 6379 를 허용한다. Redis 는 `replicas: 1` 로 고정돼
있다 — 스케일하면 안 된다(인스턴스가 갈리면 서버 A·B 가 서로 다른 challenge
저장소를 보게 된다). 데이터는 `redis-data` 볼륨(AOF)에 영속화되지만, 영구
데이터의 원본은 Oracle 이므로 이 볼륨을 잃어도 서비스 복구는 가능하다(로그인
중이던 세션/challenge 만 끊긴다).

## 4. 앱 스택 기동

각 서버에서 `.env.example` 을 복사해 값을 채운다.

```bash
cd deploy
cp .env.example .env && chmod 600 .env
```

`.env.example` 의 주요 변수:

| 그룹 | 변수 | 비고 |
|---|---|---|
| 이미지 | `IMAGE_TAG` | 기본 `0.0.1-SNAPSHOT` |
| DB | `DB_URL`, `DB_RUNTIME_USER/PASSWORD`, `DB_ADMIN_USER/PASSWORD` | passkey-app/admin-app 런타임 계정 |
| DB (Flyway) | `DB_OWNER_USER/PASSWORD` | **admin-app 에만** 주입 — passkey-app 은 `--scale` 로 여러 개 뜨므로 마이그레이션을 동시에 시도하면 안 된다 |
| Redis | `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | 서버 C 를 가리킨다 |
| 시크릿 | `MASTER_KEY` | `openssl rand -base64 32`. **서버 A·B 가 반드시 동일**해야 한다 — 다르면 한쪽이 암호화한 데이터를 다른 쪽이 복호화하지 못한다 |
| 도메인 | `PASSKEY_SERVER_NAME`, `ISSUER_BASE`, `ADMIN_INVITE_BASE_URL` | nginx `server_name` + ID Token issuer |
| admin | `ADMIN_BIND_ADDR`(기본 `127.0.0.1`), `ADMIN_HOST_PORT`(기본 `8081`), `MDS_LEASE_HOLDER` | 콘솔은 내부망 전용 |
| 리소스 | `PASSKEY_MEM_LIMIT`, `ADMIN_MEM_LIMIT`, `RP_MEM_LIMIT` | 컨테이너 메모리 상한. `JAVA_OPTS` 의 `MaxRAMPercentage=75` 가 이 값을 인식한다 |
| QA 전용 | `RP_SERVER_NAME`, `RP_PROFILE`, `RP_PASSKEY_BASE_URL`, `RP_API_KEY`, `RP_TENANT_ID` | prod 에서는 `qa` profile 을 지정하지 않으므로 값이 비어 있어도 무해하다 |

`SERVER_FORWARD_HEADERS_STRATEGY` 는 주입하지 않는다 — `forward-headers-strategy:
framework` 가 passkey-app/admin-app 의 base `application.yml` 에 이미 있다.

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
`docker-compose.yml` 의 passkey-app `ports:` 주석 2줄을 해제한다. 단 이 경우
호스트 포트가 고정되므로 `--scale` 은 1 로 제한된다. 기본 구성(nginx 사용)에서는
passkey-app 에 `ports:` 매핑이 전혀 없다 — nginx 가 compose 내부 네트워크로만
접근하므로 `--scale` 시 포트 충돌이 나지 않는다.

admin-app 은 `ports: "${ADMIN_BIND_ADDR:-127.0.0.1}:${ADMIN_HOST_PORT:-8081}:8081"`
로, 변수를 비워둬도 **기본값이 `127.0.0.1`** 이라 외부에 노출되지 않는다
(fail-closed, 실측 확인). 내부망에서 접근하려면 `ADMIN_BIND_ADDR` 을 내부 IP로
명시한다.

## 5. 무중단 배포 (수동 블루/그린)

compose 자체에는 롤링 업데이트가 없다. passkey-app 은 무상태(세션/challenge 는
Redis, 영구 데이터는 Oracle)이므로 아래 수동 절차로 대체한다.

```bash
# 새 이미지를 미리 pull/load 해둔 뒤
docker compose up -d --scale passkey-app=6 --no-recreate   # 신버전 3개 추가
docker compose ps                                          # healthy 확인
docker compose up -d --scale passkey-app=3                 # 구버전 정리
```

`--no-recreate` 로 기존 컨테이너를 건드리지 않고 새 이미지로 추가 인스턴스만
띄운 뒤, `healthcheck` (`/actuator/health` 폴링, `start_period: 60s`)가 healthy
로 바뀐 걸 확인하고 스케일을 원래 수로 줄여 구버전을 정리한다. nginx 는 Docker
내장 DNS(`resolver 127.0.0.11 valid=10s`)로 업스트림을 재해석하므로 스케일 변경이
10초 내 반영된다.

admin-app 은 1개 고정이라 이 방식이 불가능하다. 재기동 시 수 초~수십 초 중단되나
운영자 콘솔이므로 수용한다.

## 6. 배포 체크리스트

- [ ] 외부 Oracle 에 PSK_APP_OWNER / PSK_APP_RUNTIME_USER / PSK_APP_ADMIN_USER 부트스트랩 완료
      (`scripts/init-db-external.sh` 또는 `scripts/bootstrap-external.sql`)
- [ ] 서버 A·B 의 `MASTER_KEY` 가 **동일한 값**인지 확인
- [ ] 앞단 LB 가 클라이언트발 `X-Forwarded-*` 헤더를 스트립하는지 확인
- [ ] LB → nginx 로 `X-Forwarded-Proto: https` 가 전달되는지 확인
- [ ] 서버 C 방화벽이 서버 A·B 만 6379 허용
- [ ] 서버 A 에만 `admin` profile 지정 (B 에 중복 기동 금지)
- [ ] prod 에서 `qa` profile 미지정 (rp-app 배제)
- [ ] `.env` 권한 600, `git status` 에 나타나지 않음
- [ ] DNS: passkey 서브도메인이 LB 를 가리킴
- [ ] 세 Dockerfile 에 `# syntax=` 지시자가 재도입되지 않았는지 확인 (§1 참고)

## 7. 알려진 한계

| 항목 | 상태 |
|---|---|
| 롤링 업데이트 | 자동 없음 — §5 수동 절차로 대체 |
| 호스트 장애 | 해당 서버의 인스턴스가 전부 중단, LB 가 다른 서버로 우회 |
| **Redis SPOF** | 서버 C 장애 시 신규 인증/등록 중단. 영구 데이터는 Oracle 에 있어 유실은 없다 |
| 시크릿 관리 | `.env` 평문 — Vault 등 시크릿 매니저 미도입, 파일 권한(600)으로만 보호 |
| 검증 상태 | 이 절차는 아직 실제 빌드/기동으로 검증되지 않았다(§ 상단 참고) |

## 8. 트러블슈팅

**passkey-app 이 부팅 직후 종료** — prod 프로필은 DB/Redis 관련 환경변수가
비어 있으면 의도적으로 fail-fast 한다. `docker compose logs passkey-app` 에서
`Failed to configure a DataSource` 류의 메시지를 확인하고 `.env` 를 점검한다.

## 마이그레이션 후속 조치

- **V5 (`admin_signup_request` 도입 + 초대 테이블 제거) 적용 후**: 기존 초대
  흐름이 남긴 미완료 계정(`admin_user.status='PENDING'`, `bcrypt_hash IS NULL`)은
  초대 테이블이 사라져 더 이상 완료할 방법이 없다. `admin_user_tenant.admin_user_id`
  FK 가 `ON DELETE CASCADE` 이므로 `admin_user` 행만 지우면 연결된 테넌트 매핑도
  함께 정리된다. PSK_APP_OWNER 로 한 번 실행한다:
  ```sql
  DELETE FROM admin_user WHERE status = 'PENDING' AND bcrypt_hash IS NULL;
  ```

**`--scale` 시 포트 충돌** — passkey-app 에 `ports:` 매핑이 살아 있는지
확인한다. nginx 를 쓰는 기본 구성에서는 주석 처리돼 있어야 한다.

**로그인 후 즉시 로그아웃 / WebAuthn origin 오류** — `X-Forwarded-Proto` 가
앱까지 원본 그대로 전달되지 않는 경우다. LB 설정과 `nginx.conf` 의
`proxy_set_header X-Forwarded-Proto $http_x_forwarded_proto;` 를 확인한다.
`$scheme` 으로 바뀌어 있으면 안 된다 — nginx 가 평문 HTTP 로 받았다는 이유로
앱이 secure 쿠키/WebAuthn origin 검증을 깨뜨린다.

**nginx 가 기동 자체를 못 함 (`host not found in upstream`)** — `nginx.conf` 의
`proxy_pass` 대상이 변수(`$passkey_upstream`)가 아니라 호스트명 리터럴로 바뀐
경우다. 리터럴이면 nginx 가 기동 시점에 DNS 를 해석해, 백엔드가 아직/이미
없으면 그 자체로 기동에 실패한다(실측 확인). `resolver 127.0.0.11` + `set $var`
+ `proxy_pass http://$var` 패턴을 유지해야, 백엔드 부재가 기동 실패가 아니라
요청 시점의 502 로만 나타난다.

**challenge not found** — 서버 A·B 가 서로 다른 Redis 를 보고 있는 경우다.
양쪽 `.env` 의 `REDIS_HOST` 가 같은 서버 C 를 가리키는지 확인한다.

**admin 콘솔에 외부에서 접속이 안 됨** — 의도된 동작이다. `ADMIN_BIND_ADDR` 기본값이
`127.0.0.1` 이라 루프백에서만 열린다. 내부망에서 접근하려면 `.env` 에서
`ADMIN_BIND_ADDR` 을 내부 IP 로 명시적으로 바꿔야 한다.
