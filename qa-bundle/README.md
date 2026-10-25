# Passkey QA 반입 번들 (폐쇄망 / Rocky Linux 9.7 x86_64)

인터넷이 차단된 QA 서버 1대에 Passkey 플랫폼을 기동하기 위한 **모든 파일**이
들어 있다. 이 디렉터리를 통째로 서버에 옮기면 추가 다운로드가 필요 없다.

> ⚠️ **아키텍처**: 이 번들의 이미지와 바이너리는 전부 **linux/amd64(x86_64)**
> 전용이다. ARM 서버에서는 동작하지 않는다.

---

## 구성

```
passkey-qa-bundle/
├── README.md                      ← 이 파일
├── preflight.sh                   사전 점검 (§1)
├── load-images.sh                 이미지 로드 + 아키텍처 검증 (§3)
├── setup.sh                       .env 대화형 작성 (§5)
├── gen-secrets.sh                 시크릿 3종 생성 (setup.sh 가 호출)
├── start.sh                       .env 검증 + 기동/중지/상태 (§6)
├── docker-install/
│   ├── docker-29.7.2.tgz          Docker 정적 바이너리
│   ├── docker-compose-linux-x86_64  Compose v2 플러그인
│   └── install-docker.sh          §2 전 과정 자동화
├── images/
│   ├── passkey-app.tar.gz         인증 API 서버
│   ├── admin-app.tar.gz           관리 콘솔
│   ├── rp-app.tar.gz              QA 데모 RP
│   ├── infra.tar.gz               nginx + redis
│   └── SHA256SUMS
├── deploy/                        compose 스택 (저장소 deploy/ 사본)
│   ├── docker-compose.yml
│   ├── docker-compose.host-nginx.yml   PROXY_MODE=host 덧씌움 (앱을 루프백 포트에 연다)
│   ├── docker-compose.redis.yml
│   ├── .env.qa-template          이걸 .env 로 복사해 값을 채운다
│   ├── host-nginx/passkey-qa.conf.template   호스트 nginx 설정 원본 (start.sh --nginx-conf)
│   └── nginx/nginx.conf          번들 nginx 설정 (PROXY_MODE=bundled)
```

> **DB 는 이 번들 범위 밖이다.** 스키마·계정·초기 운영자 계정은 이미 생성돼
> 있다는 전제이며, DDL 스크립트와 운영자 시드 도구는 포함하지 않는다.

**따로 준비해야 하는 것 (번들에 넣을 수 없음)**

| 항목 | 이유 |
|---|---|
| `Rocky-9.7-x86_64-dvd1.iso` | 배포 불가. `iptables`/`container-selinux` 가 없을 때만 필요 |
| Oracle DB | 스택 외부. 내부망 1521 접근 필요 |
| TLS 인증서 + 내부 DNS 2개 | 조직 인프라. host 모드는 passkey·admin 도메인, bundled 모드는 passkey·RP 서브도메인 ("반드시 먼저 해결해야 하는 것 — HTTPS") |
| (host 모드) 서버의 nginx | 이미 설치돼 443 에서 TLS 를 받고 있다는 전제 ("nginx — 두 가지 방식") |

---

## 실행 순서

### 1. 사전 점검

```bash
./preflight.sh <OracleIP> 1521
```

`[!!]` 가 나온 항목을 먼저 해결한다. 특히 **`iptables` 가 없으면 Docker
네트워킹이 동작하지 않는다.** 폐쇄망이라 `dnf install` 이 안 되므로 OS 설치
ISO 를 로컬 저장소로 마운트해 설치한다.

```bash
sudo mount -o loop /경로/Rocky-9.7-x86_64-dvd1.iso /mnt/iso
sudo tee /etc/yum.repos.d/rocky-local.repo > /dev/null <<'EOF'
[local-baseos]
name=Rocky Local BaseOS
baseurl=file:///mnt/iso/BaseOS
enabled=1
gpgcheck=0

[local-appstream]
name=Rocky Local AppStream
baseurl=file:///mnt/iso/AppStream
enabled=1
gpgcheck=0
EOF
sudo dnf --disablerepo='*' --enablerepo='local-*' install -y iptables container-selinux
```

### 2. Docker 설치

```bash
sudo ./docker-install/install-docker.sh
```

끝나면 `Storage: overlay2 / Cgroup: 2` 가 나와야 한다. sudo 없이 쓰려면
`sudo usermod -aG docker $USER` 후 재로그인.

firewalld 가 켜져 있다면:

```bash
sudo firewall-cmd --permanent --zone=trusted --add-interface=docker0
sudo firewall-cmd --reload
```

### 3. 이미지 적재

```bash
./load-images.sh                 # tarball 4개 전부(이미지 5개)
./load-images.sh passkey-app     # 특정 앱만 (재배포 시)
```

5개 이미지가 모두 `amd64` 로 확인돼야 한다. 앱을 따로 재배포할 때는 해당
`tar.gz` 하나만 서버로 옮겨 그 이름으로 실행하면 된다.

이미지를 재빌드하면 `images/` 의 `tar.gz` 와 `SHA256SUMS` 를 **함께** 갱신한다 —
`load-images.sh` 가 `sha256sum -c` 로 검증하므로 해시가 어긋나면 적재가 실패한다.
리포지토리의 `scripts/build-images.sh` 가 amd64 빌드부터 두 파일 갱신까지 한 번에
한다(서버가 아니라 빌드하는 PC 에서 실행).

### 4. DB 확인

스키마·계정·초기 운영자 계정은 **이미 생성돼 있다는 전제**다. 기동 전에 접속만
확인한다.

```bash
./preflight.sh <OracleIP> 1521
```

앱이 쓰는 계정은 아래 2개다. `.env` 에 채울 값과 일치해야 한다.

| 계정 | 용도 |
|---|---|
| `PSK_APP_ADMIN_USER` | admin-app 런타임 |
| `PSK_APP_RUNTIME_USER` | passkey-app 런타임 |

> **Flyway 미사용** — QA 번들은 admin-app 의 Flyway 를 끈다(`SPRING_FLYWAY_ENABLED=false`).
> 앱은 스키마를 만들거나 바꾸지 않고, 스키마 이력(`flyway_schema_history`)도 검사하지 않는다.
> 스키마는 DBA 가 `V1`~`V5`(정본: `ls core/src/main/resources/db/migration`) 기준으로 미리
> 만들어 두고, 스키마 소유자(`PSK_APP_OWNER`) 계정 비밀번호는 번들에 필요 없다.
> 테이블·컬럼이 빠져 있으면 두 앱이 기동 시 `Schema-validation: missing table/column` 으로 멈춘다.

### 5. 환경 설정

```bash
./setup.sh
```

**아래 7가지만 물어본다.** 나머지는 전부 유도하거나 자동 생성한다. ("7가지"는
*값*의 묶음이다 — 실제 프롬프트는 host 모드 12번, bundled 모드 9번이고,
예컨대 "계정 2개 비밀번호"가 프롬프트 2번이다.)

| # | 묻는 것 | 프롬프트 | 어디서 얻나 |
|---|---|---|---|
| 1 | Oracle 호스트 · 포트 · 서비스명 | 3 | DBA |
| 2 | 계정 2개 비밀번호 (`PSK_APP_ADMIN_USER` / `PSK_APP_RUNTIME_USER`) | 2 | DBA |
| 3 | nginx 방식 — `host`(서버에 이미 있는 nginx, 기본) / `bundled`(번들 nginx) | 1 | "nginx — 두 가지 방식" |
| 4 | passkey 서버 FQDN | 1 | 인프라팀(내부 DNS) |
| 5 | **host**: admin 콘솔 FQDN · RP 데모 포트(기본 8443) · 인증서/개인키 경로 <br> **bundled**: RP 데모 FQDN | host 4 / bundled 1 | 인프라팀(내부 DNS), 기존 nginx 설정 |
| 6 | 호스트 내부 IP | 1 | `docker0` 에서 자동 탐지, 확인만 |
| 7 | `RP_TENANT_ID` · `RP_API_KEY` | (별도) | 테넌트를 만든 뒤에야 알 수 있어 §8 의 `./setup.sh --rp-keys` 로 나중에 채운다 |

> **입력 규칙: 값에 작은따옴표(`'`)를 쓸 수 없고, 백슬래시(`\`)로 끝날 수 없다.**
> `.env` 를 읽는 두 파서(`start.sh` 의 셸 `source` 와 `docker compose`)가 이
> 두 경우에 합의하는 표기가 없다. `'` 는 양쪽이 다른 값을 보게 되고, 값 끝의
> `\` 는 compose 가 닫는 따옴표를 이스케이프로 읽어 **`.env` 파일 전체를
> 거부**한다(셸은 정상 통과하므로 `--check` 만으로는 드러나지 않는다).
> 입력 단계에서 거부하므로 **DB 비밀번호를 발급받을 때 `'` 와 끝 `\` 를 피한다.**

자동으로 처리되는 것:

- FQDN 입력 → **여러 곳에 일관 반영** (손편집 시 한 곳만 고치는 사고가 사라진다)
  - passkey FQDN → `PASSKEY_SERVER_NAME`·`ISSUER_BASE`
  - host: admin FQDN → `ADMIN_SERVER_NAME`·`ADMIN_INVITE_BASE_URL`, RP 포트 → `RP_PUBLIC_PORT`,
    인증서 경로 → `TLS_CERT_FILE`·`TLS_KEY_FILE`
  - bundled: RP FQDN → `RP_SERVER_NAME`, `ADMIN_INVITE_BASE_URL` 은 passkey FQDN
  - 쓰지 않는 방식의 값은 비운다(템플릿 자리표시자가 남으면 `--check` 가 막는다)

  > ⚠️ `ADMIN_INVITE_BASE_URL` 은 가입 승인·비밀번호 재설정 **메일 링크의 base** 다.
  > host 모드는 admin 도메인이 들어가 그대로 맞다. bundled 모드는 setup.sh 가 passkey
  > FQDN 을 넣는데, admin 콘솔이 nginx 뒤가 아니라 `ADMIN_BIND_ADDR:ADMIN_HOST_PORT`
  > (기본 `127.0.0.1:8081`)로 직접 바인딩되므로, 메일을 실제로 쓸 환경이면
  > `deploy/.env` 에서 이 값을 콘솔 접근 주소로 고쳐야 한다.
- `MASTER_KEY`·`REDIS_PASSWORD`·`RP_RELAY_SECRET` 생성 및 기록
  (값은 화면에 출력하지 않는다 — 확인은 `deploy/.env` 에서)
- `REDIS_HOST` = `REDIS_BIND_ADDR` 동기화, `127.0.0.1` 입력은 거부
- `chmod 600`, 이전 세대 시크릿이 든 `.env.bak` 삭제
- 마지막에 `start.sh --check` 자동 실행

끝나면 `[OK] 검증 통과` 가 떠야 한다.

기록 형식은 `KEY='값'`(작은따옴표)이다. 공백·`$`·백틱이 든 비밀번호를
`start.sh` 의 `source` 와 `docker compose` 가 **같은 값**으로 읽게 하려면
따옴표가 필요하다. 무인용으로 손편집한 기존 `.env` 도 **값에 공백·`$`·백틱이
없다면** 그대로 동작한다(있다면 두 파서가 다른 값을 보므로 따옴표를 씌운다).

> `.env` 를 직접 편집하고 싶다면 `deploy/.env.qa-template` 을 `deploy/.env` 로
> 복사해 손으로 채운 뒤 `./start.sh --check` 로 검증해도 된다.

시크릿 3종만 다시 만들려면(키 회전) `./gen-secrets.sh --write` 를 쓴다. 이전
값이 든 `deploy/.env.bak` 은 자동으로 지운다. 롤백 대비로 남기려면
`--keep-backup` 을 붙이고, 확인이 끝나면 직접 삭제한다.

### 6. 기동

```bash
./start.sh
```

검증 → Redis → 앱 순으로 띄우고 healthy 까지 기다린다. 아래를 대신 처리하므로
compose 를 직접 칠 필요가 없다.

- `.env` 의 `PROXY_MODE` 에 맞춰 `COMPOSE_PROFILES` 와 compose 파일 자동 지정
  - host: `admin,qa` + `-f docker-compose.host-nginx.yml` (번들 nginx 없음)
  - bundled: `proxy,admin,qa`
  — **프로파일을 빠뜨리면 `passkey-app` 하나만 뜨고 나머지가 에러 없이
  누락된다.** 폐쇄망에서 원인 찾기가 가장 어려운 사고다.

host 모드는 기동과 별개로 **서버 nginx 에 설정을 한 번 넣어야** 브라우저에서 닿는다
("nginx — 두 가지 방식"). 기동 마지막에 명령을 다시 안내한다.
- Redis 스택이 요구하는 `REDIS_BIND_ADDR` / `REDIS_PASSWORD` export
- healthy 폴링 — admin-app 은 `start_period` 가 90초라 시간이 걸리는 게 정상

`RP_API_KEY` **또는 `RP_TENANT_ID`** 가 비어 있으면 **rp-app 은 자동으로 제외**하고
안내를 출력한다(둘 다 채워져야 기동한다). 값이 빠진 채로 띄워봐야 401 만 나기 때문이다.

```bash
./start.sh --status     # 상태 확인
./start.sh --stop       # 앱 중지 (Redis 유지)
./start.sh --stop-all   # 앱 + Redis 중지 (볼륨 보존)
```

admin-app 이 기동하지 않으면 로그로 원인을 본다.

```bash
cd deploy && docker compose logs -f admin-app
```

### 7. (참고) compose 직접 실행

`start.sh` 없이 손으로 띄운다면 아래가 최소 절차다. 프로파일과 두 export 를
빠뜨리지 않도록 주의한다.

```bash
cd deploy
set -a; . ./.env; set +a          # 값의 따옴표를 셸이 벗겨준다
export REDIS_BIND_ADDR=${REDIS_BIND_ADDR:-$REDIS_HOST}   # 0.0.0.0 금지
docker compose -f docker-compose.redis.yml up -d

# host 모드
COMPOSE_PROFILES=admin,qa docker compose -f docker-compose.yml -f docker-compose.host-nginx.yml up -d
# bundled 모드
COMPOSE_PROFILES=proxy,admin,qa docker compose up -d
```

host 모드로 띄운 스택은 중지·상태 확인 때도 `-f` 두 개를 같이 준다(`./start.sh` 는 자동).

> `grep … | cut -d= -f2-` 로 값을 뽑지 않는다. `.env` 는 값을 작은따옴표로
> 감싸므로 따옴표까지 딸려와 비밀번호가 틀린다. 위처럼 `.` 로 읽으면 된다.

### 8. 테넌트 생성 → API 키 → rp-app 재기동

admin 콘솔에서 테넌트를 만든다.

- host: `https://<ADMIN_SERVER_NAME>/admin/` — 서버 nginx 가 admin 도메인을 `127.0.0.1:8081` 로 넘긴다
- bundled: `http://<ADMIN_BIND_ADDR>:<ADMIN_HOST_PORT>/admin/` (기본 `http://127.0.0.1:8081/admin/`).
  admin-app 은 nginx 를 거치지 않고 루프백에 바인딩돼 **그 호스트 안에서만** 접근된다.

아래 두 값은 `./start.sh` 가 기동 끝에 이 환경 기준으로 출력한다.

| 항목 | host 모드 | bundled 모드 | 규칙 |
|---|---|---|---|
| `rpId` | `passkey-qa.내부도메인` | `rp-qa.내부도메인` | **도메인만.** 스킴·포트 없음 |
| `allowedOrigins` | `["https://passkey-qa.내부도메인:8443"]` | `["https://rp-qa.내부도메인"]` | **브라우저 주소창 origin 그대로.** 스킴 포함, 443 이 아니면 포트까지 |

> host 모드는 RP 가 passkey 도메인을 함께 쓰므로 `rpId` 가 passkey 도메인이 된다. 패스키는
> 포트를 무시하고 도메인에 묶인다 — QA 웹 테스트에는 문제없지만, 운영에서 RP 를 서브도메인으로
> 나누라고 권하는 이유다(docs/single-instance-deployment.md). 네이티브 앱 패스키까지 시험하려면
> 앱이 `https://<passkey 도메인>/.well-known/*`(443) 을 확인하므로 그 두 경로를 rp-app 으로
> 넘기는 location 을 따로 넣어야 한다(현재 설정은 웹만 다룬다).

`mdsRequired` 는 첫 등록 테스트에서 **`false`** 를 권장한다("문제 해결" 참고).

API Keys 탭에서 `registration`, `authentication` scope 키를 발급한다.
**평문 키는 1회만 표시**되므로 즉시 `.env` 의 `RP_API_KEY` 에 넣고,
테넌트 id 를 `RP_TENANT_ID` 에 넣은 뒤:

```bash
./setup.sh --rp-keys     # 값 2개를 입력받아 .env 에 기록
./start.sh rp-app
```

---

## 반드시 먼저 해결해야 하는 것 — HTTPS

**WebAuthn 은 `localhost` 가 아니면 HTTPS 를 요구한다.** 브라우저가 강제하는
사양이라 우회할 수 없다. 평문 HTTP 로 접근하면 패스키 등록이 브라우저 단계에서
거부된다. QA 라도 아래가 전부 필요하다.

- 내부 DNS 2개 — 둘 다 이 호스트를 가리킨다
  - host 모드: passkey 도메인(`passkey-qa.…`) + admin 도메인(`admin-qa.…`).
    RP 는 passkey 도메인의 다른 포트(기본 8443)로 연다
  - bundled 모드: passkey·RP 서브도메인(`passkey-qa.…`, `rp-qa.…`)
- 그 도메인을 포함하는 TLS 인증서(SAN 또는 와일드카드)
  - host 모드: 서버 nginx 가 이미 가진 인증서. 경로를 `TLS_CERT_FILE`·`TLS_KEY_FILE` 에
    적는다(admin 인증서가 따로면 `ADMIN_TLS_CERT_FILE`·`ADMIN_TLS_KEY_FILE`)
  - bundled 모드: `deploy/cert/fullchain.pem`, `deploy/cert/key.pem`
- 사설 CA 라면 테스트 브라우저·OS 신뢰 저장소에 등록 (인증서 경고 화면을 거친
  상태에서는 WebAuthn 이 동작하지 않는다)

`ISSUER_BASE` 도 반드시 `https://` 로 시작해야 한다.

RP 를 경로(`https://passkey-qa.…/rp/`)로 붙이면 안 된다. rp-app 페이지는
`/css`·`/js`·`/passkey/**` 를 루트 절대경로로 호출하고, 네이티브 앱용 `/.well-known/*` 은
도메인 루트에 있어야 하므로 경로 접두로 합치면 `/passkey/register/begin` 이 passkey-app
으로 가서 404 가 난다. 그래서 bundled 는 서브도메인, host 는 같은 도메인의 다른 포트로 나눈다.

---

## nginx — 두 가지 방식

`deploy/.env` 의 `PROXY_MODE` 로 고른다(`./setup.sh` 가 묻는다). 값이 없으면 bundled 로
동작한다 — 이 설정이 생기기 전에 만든 `.env` 는 그대로 쓸 수 있다.

| | host (기본) | bundled |
|---|---|---|
| TLS 종료 | 서버에 이미 설치된 nginx | 번들 nginx 컨테이너 |
| 번들 nginx 컨테이너 | 띄우지 않음 | 80/443 을 잡음 |
| 호스트에 열리는 앱 포트 | `127.0.0.1` 의 passkey 8080 · rp 19090 · admin 8081 | admin 8081 만 (나머지는 compose 내부망) |
| passkey | `https://<passkey 도메인>` | `https://<passkey 도메인>` |
| RP 데모 | `https://<passkey 도메인>:8443` | `https://<RP 서브도메인>` |
| admin 콘솔 | `https://<admin 도메인>/admin/` | 서버 안에서 `http://127.0.0.1:8081/admin/` |
| passkey-app `--scale` | 1 (호스트 포트 고정) | 여러 개 가능 |

포트와 도메인은 전부 `.env` 값이다(`RP_PUBLIC_PORT`, `PASSKEY_/RP_/ADMIN_HOST_PORT`,
`PASSKEY_/ADMIN_SERVER_NAME`). 바꾸면 `./start.sh` 로 다시 띄우고, host 모드는 아래
nginx 설정도 다시 만들어 넣는다.

### host 모드 — 서버 nginx 에 설정 넣기 (최초 1회, 값을 바꿨을 때도)

```bash
./start.sh --nginx-conf | sudo tee /etc/nginx/conf.d/passkey-qa.conf > /dev/null
sudo nginx -t && sudo systemctl reload nginx
```

`--nginx-conf` 는 `deploy/host-nginx/passkey-qa.conf.template` 의 `${…}` 를 `.env` 값으로
채워 **출력만** 한다(파일을 직접 쓰지 않는다). 출력은 server 블록 3개 — passkey(443),
RP(`RP_PUBLIC_PORT`), admin(443) 이다. passkey·admin 블록은 actuator 를 404 로 막는다.
서버 nginx 에 같은 `server_name` 의 443 블록이 이미 있으면 `nginx -t` 가 "conflicting
server name" 경고를 내고 먼저 읽힌 블록만 쓰이므로, 기존 블록을 정리한다.

Rocky 에서 함께 필요한 것:

```bash
# SELinux Enforcing: nginx → 앱 루프백 포트 연결 허용 (안 하면 502)
sudo setsebool -P httpd_can_network_connect 1
# firewalld: RP 포트 개방 (443 은 기존 nginx 가 이미 받고 있다는 전제)
sudo firewall-cmd --permanent --add-port=8443/tcp && sudo firewall-cmd --reload
# RP 포트를 8443 이 아닌 값으로 바꿨다면 nginx 가 listen 할 수 있게 레이블 추가
# (semanage 는 policycoreutils-python-utils 패키지)
sudo semanage port -a -t http_port_t -p tcp <RP 포트>
```

`./preflight.sh` 와 `./start.sh --check` 가 SELinux 값과 포트 점유를 점검한다.

서버 nginx 가 앱에 넘기는 헤더는 템플릿에 들어 있다. 기존 블록에 손으로 옮겨 적는다면
아래를 모두 지킨다. 특히 **`X-Forwarded-Port` 가 빠지면 RP(8443)의 등록·로그인이 전부
403(`Invalid CORS request`)** 이 된다 — `X-Forwarded-Proto` 가 포트를 443 으로 되돌리므로
`Host` 에 포트를 실어 보내는 것으로는 해결되지 않는다(실측).

| 헤더 | 값 | 빠지면 |
|---|---|---|
| `X-Forwarded-Proto` | `$scheme` | 앱이 요청을 http 로 본다 — RP 403, admin 세션 쿠키 판정 어긋남 |
| `X-Forwarded-Port` | `$server_port` | RP 가 443 이 아닌 포트일 때 403 |
| `X-Forwarded-For` | `$remote_addr` | 감사 로그·rate-limit IP 가 전부 nginx 로 찍힘 |
| `Host` | `$host` | |

### bundled 모드

번들의 `deploy/nginx/nginx.conf` 가 **TLS 를 직접 종료한다.** 443 에서 서브도메인으로
passkey-app / rp-app 을 나누고, 80 은 443 으로 리다이렉트만 한다. 인증서가 없으면
nginx 가 뜨지 않으므로 `./start.sh` 가 선검증에서 막는다.

---

## 문제 해결

| 증상 | 원인 |
|---|---|
| `exec format error` | 이미지 아키텍처 불일치 — `./load-images.sh` 로 확인 |
| 브라우저가 등록 즉시 거부 | HTTPS 아님 / 인증서 미신뢰 |
| `/passkey/register/begin` 404, rp-app 로그 없음 | RP 를 경로(`/rp/`)로 붙였거나, (bundled) Host 가 `RP_SERVER_NAME` 과 불일치 |
| RP 등록·로그인이 403 `Invalid CORS request` | nginx 가 `X-Forwarded-Proto`/`X-Forwarded-Port` 를 안 넘김 ("nginx — 두 가지 방식" 헤더 표) |
| (host) 502 Bad Gateway | SELinux `httpd_can_network_connect` off, 또는 앱 미기동(`./start.sh --status`) |
| (host) `nginx -t` 의 conflicting server name | 같은 도메인의 기존 server 블록과 겹침 — 하나로 정리 |
| 기동 시 `address already in use` / `port is already allocated` | `.env` 의 `*_HOST_PORT` 를 다른 서비스가 쓰는 중 — 값을 바꾸고 `--nginx-conf` 다시 |
| `origin mismatch` | 테넌트 `allowedOrigins` ≠ 실제 접속 origin |
| `rpId` 오류 | `rpId` 에 스킴·포트가 들어감 |
| 401 | `RP_API_KEY` 불일치 또는 scope 부족 |
| MDS 관련 거부 | `mdsRequired=true` 인데 BLOB 동기화 미완료 |
| 앱이 안 뜸 (AES 키) | `MASTER_KEY` 가 base64 32바이트가 아님 |
| rp-app `IllegalStateException` | `RP_RELAY_SECRET` 이 데모 기본값 |
| 볼륨 `permission denied` | SELinux Enforcing + `container-selinux` 미설치 |
| 일부 컨테이너가 조용히 안 뜸 | `COMPOSE_PROFILES` 누락 — `./start.sh` 를 쓰면 방지된다 |

```bash
./start.sh --check                  # .env 검증만
./start.sh --status                 # 스택 상태

cd deploy
docker compose logs -f passkey-app
docker compose exec passkey-app sh
```
