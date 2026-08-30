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
│   ├── docker-compose.redis.yml
│   ├── .env.qa-template          이걸 .env 로 복사해 값을 채운다
│   └── nginx/nginx.conf
```

> **DB 는 이 번들 범위 밖이다.** 스키마·계정·초기 운영자 계정은 이미 생성돼
> 있다는 전제이며, DDL 스크립트와 운영자 시드 도구는 포함하지 않는다.

**따로 준비해야 하는 것 (번들에 넣을 수 없음)**

| 항목 | 이유 |
|---|---|
| `Rocky-9.7-x86_64-dvd1.iso` | 배포 불가. `iptables`/`container-selinux` 가 없을 때만 필요 |
| Oracle DB | 스택 외부. 내부망 1521 접근 필요 |
| TLS 인증서 + 내부 DNS 2개 | 조직 인프라 (§9) |

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
./load-images.sh                 # 4개 전부
./load-images.sh passkey-app     # 특정 앱만 (재배포 시)
```

5개 이미지가 모두 `amd64` 로 확인돼야 한다. 앱을 따로 재배포할 때는 해당
`tar.gz` 하나만 서버로 옮겨 그 이름으로 실행하면 된다.

### 4. DB 확인

스키마·계정·초기 운영자 계정은 **이미 생성돼 있다는 전제**다. 기동 전에 접속만
확인한다.

```bash
./preflight.sh <OracleIP> 1521
```

앱이 쓰는 계정은 아래 3개다. `.env` 에 채울 값과 일치해야 한다.

| 계정 | 용도 |
|---|---|
| `PSK_APP_OWNER` | 스키마 소유자. Flyway 마이그레이션 |
| `PSK_APP_ADMIN_USER` | admin-app 런타임 |
| `PSK_APP_RUNTIME_USER` | passkey-app 런타임 |

> **Flyway** — admin-app 이 기동하며 V1~V4 를 적용한다. 스키마를 DDL 로 이미
> 만들어 둔 경우 `flyway_schema_history` 가 비어 있으므로
> **`baseline -baselineVersion=4`** 처리가 필요하다. 안 하면 V1 부터 재적용을
> 시도하다 실패한다.

### 5. 환경 설정

```bash
./setup.sh
```

**6가지만 물어본다.** 나머지는 전부 유도하거나 자동 생성한다.

| 묻는 것 | 어디서 얻나 |
|---|---|
| Oracle 호스트 · 포트 · 서비스명 | DBA |
| 계정 3개 비밀번호 | DBA |
| passkey 서버 FQDN · RP 데모 FQDN | 인프라팀(내부 DNS) |
| 호스트 내부 IP | `docker0` 에서 자동 탐지, 확인만 |

자동으로 처리되는 것:

- 도메인 1개 입력 → `PASSKEY_SERVER_NAME`·`ISSUER_BASE`·`ADMIN_INVITE_BASE_URL`
  **3곳에 일관 반영** (손편집 시 한 곳만 고치는 사고가 사라진다)
- `MASTER_KEY`·`REDIS_PASSWORD`·`RP_RELAY_SECRET` 생성 및 기록
- `REDIS_HOST` = `REDIS_BIND_ADDR` 동기화, `127.0.0.1` 입력은 거부
- `chmod 600`, 백업 파일 정리
- 마지막에 `start.sh --check` 자동 실행

끝나면 `[OK] 검증 통과` 가 떠야 한다.

> `.env` 를 직접 편집하고 싶다면 `deploy/.env.qa-template` 을 `deploy/.env` 로
> 복사해 손으로 채운 뒤 `./start.sh --check` 로 검증해도 된다.

### 6. 기동

```bash
./start.sh
```

검증 → Redis → 앱 순으로 띄우고 healthy 까지 기다린다. 아래를 대신 처리하므로
compose 를 직접 칠 필요가 없다.

- `COMPOSE_PROFILES=proxy,admin,qa` 자동 지정
  — **빠뜨리면 `passkey-app` 하나만 뜨고 nginx·admin-app·rp-app 이 에러 없이
  누락된다.** 폐쇄망에서 원인 찾기가 가장 어려운 사고다.
- Redis 스택이 요구하는 `REDIS_BIND_ADDR` / `REDIS_PASSWORD` export
- healthy 폴링 — admin-app 은 `start_period` 가 90초라 시간이 걸리는 게 정상

`RP_API_KEY` 가 비어 있으면 **rp-app 은 자동으로 제외**하고 안내를 출력한다.
빈 키로 띄워봐야 401 만 나기 때문이다.

```bash
./start.sh --status     # 상태 확인
./start.sh --stop       # 앱 중지 (Redis 유지)
./start.sh --stop-all   # 앱 + Redis 중지 (볼륨 보존)
```

Flyway 마이그레이션 로그는 이렇게 본다.

```bash
cd deploy && docker compose logs -f admin-app
```

### 7. (참고) compose 직접 실행

`start.sh` 없이 손으로 띄운다면 아래가 최소 절차다. 프로파일과 두 export 를
빠뜨리지 않도록 주의한다.

```bash
cd deploy
export REDIS_BIND_ADDR=<호스트 내부 IP>    # 0.0.0.0 금지
export REDIS_PASSWORD=$(grep '^REDIS_PASSWORD=' .env | cut -d= -f2-)
docker compose -f docker-compose.redis.yml up -d
COMPOSE_PROFILES=proxy,admin,qa docker compose up -d
```

### 8. 테넌트 생성 → API 키 → rp-app 재기동

admin 콘솔(`https://<admin주소>/admin/`)에서 테넌트를 만든다.

| 항목 | 값 | 규칙 |
|---|---|---|
| `rpId` | `rp-qa.내부도메인` | **도메인만.** 스킴·포트 없음 |
| `allowedOrigins` | `["https://rp-qa.내부도메인"]` | **전체 origin.** 스킴 포함 |

`mdsRequired` 는 첫 등록 테스트에서 **`false`** 를 권장한다(§12).

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

- 내부 DNS 서브도메인 2개 (`passkey-qa.…`, `rp-qa.…`)
- 앞단에서 TLS 를 종료하는 LB 또는 호스트 nginx
- 그 앞단이 **`X-Forwarded-Proto: https` 를 전달**할 것
- 사설 CA 라면 테스트 브라우저·OS 신뢰 저장소에 등록 (인증서 경고 화면을 거친
  상태에서는 WebAuthn 이 동작하지 않는다)

번들의 `deploy/nginx/nginx.conf` 는 **TLS 를 다루지 않는다.** 앞단이 평문으로
넘겨준다는 전제로 서브도메인 라우팅만 한다. `ISSUER_BASE` 도 반드시
`https://` 로 시작해야 한다.

---

## 문제 해결

| 증상 | 원인 |
|---|---|
| `exec format error` | 이미지 아키텍처 불일치 — `./load-images.sh` 로 확인 |
| 브라우저가 등록 즉시 거부 | HTTPS 아님 / 인증서 미신뢰 |
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
