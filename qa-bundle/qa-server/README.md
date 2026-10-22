# QA 서버(qa-fido-passkey) 적용 안내

`/data/deploy` 에서 Docker nginx 로 passkey·RP·admin 을 도메인 하나(`qa-passkey.crosscert.com`)로 함께 띄우는
QA 서버 전용 파일이다. 번들의 `deploy/` 는 운영 서버 A·B 와 공유하므로 건드리지 않고, 이 서버에 맞춘 것을 따로 둔다.

## 무엇이 문제였나

서버 compose 의 nginx 마운트가 컨테이너 안에서도 `/data/deploy/nginx/...` 경로로 되어 있었다.
nginx 이미지는 그 경로를 읽지 않으므로, 서버의 `nginx.conf`·인증서와 상관없이 이미지 기본
설정(80 포트, `server_name localhost`)으로 떠 있었다(`nginx -T` 로 확인). 443·8443 은 아무도 받지 않는다.

## 구성

| 주소 | 대상 |
|---|---|
| `https://qa-passkey.crosscert.com` | passkey-app |
| `https://qa-passkey.crosscert.com/admin/` | admin-app (관리 콘솔) |
| `https://qa-passkey.crosscert.com:8443` | rp-app (QA 데모) |

admin 을 같은 도메인에 두었으므로 admin 의 로그인·CSRF 쿠키가 같은 도메인의 RP 페이지(:8443)에도 보인다
(쿠키는 포트를 구분하지 않는다). QA 에서는 감수하지만, 운영에서는 admin 을 별도 도메인으로 분리한다.

## 파일

| 이 폴더 | 서버 위치 | 방법 |
|---|---|---|
| `docker-compose.yml` | `/data/deploy/docker-compose.yml` | 교체 |
| `nginx/nginx.conf` | `/data/deploy/nginx/nginx.conf` | 교체 |
| `env-추가.txt` | `/data/deploy/.env` | **교체 금지** — 필요한 줄만 추가·수정 |

`docker-compose.redis.yml` 은 건드리지 않는다(Redis 는 별도 프로젝트).

## 1. 백업

```bash
cd /data/deploy
D=$(date +%Y%m%d-%H%M)
cp -p docker-compose.yml docker-compose.yml.bak-$D
cp -p nginx/nginx.conf   nginx/nginx.conf.bak-$D
cp -p .env               .env.bak-$D
```

## 2. 인증서 확인

```bash
ls -l nginx/cert
openssl x509 -in nginx/cert/fullchain.pem -noout -subject -enddate -ext subjectAltName
grep -c 'BEGIN CERTIFICATE' nginx/cert/fullchain.pem
head -1 nginx/cert/key.pem
```

- `subjectAltName` 에 `qa-passkey.crosscert.com` 이 있어야 한다(`*.crosscert.com` 와일드카드도 된다).
- `BEGIN CERTIFICATE` 가 1 이고 중간 CA 가 따로 있으면 이어 붙인다: `cat 서버.crt 중간CA.crt > fullchain.pem`
- 개인키 첫 줄에 `ENCRYPTED` 가 있으면 nginx 가 뜨지 못한다. 암호를 푼 키를 만든다: `openssl pkey -in 암호키 -out key.pem`

## 3. 파일 교체 후 차이 확인

두 파일을 위 표의 위치에 덮어쓴 뒤:

```bash
diff docker-compose.yml.bak-$D docker-compose.yml
```

nginx 서비스 말고도 차이가 나오면(이미지 태그, 포트, 환경변수 등) 서버에서 일부러 고친 것인지
확인하고, 필요한 것은 새 파일에 다시 넣는다.

## 4. `.env` 반영

`env-추가.txt` 를 보고 없는 키는 추가, 있는 키는 값만 고친다. 그다음 자리표시자가 남지 않았는지 본다:

```bash
grep -n '__' .env          # 아무것도 안 나와야 한다
```

## 5. 설정 검증

```bash
COMPOSE_PROFILES=proxy,admin,qa docker compose config -q && echo OK
```

값이 빠졌으면 `required variable TLS_CERT_NAME is missing a value: …` 처럼 무엇이 없는지 알려 준다.

## 6. 적용

```bash
COMPOSE_PROFILES=proxy,admin,qa docker compose up -d
```

- 설정이 바뀐 컨테이너만 다시 만든다.
- rp-app 의 `RP_API_KEY`·`RP_TENANT_ID` 를 아직 발급받지 않았으면 `COMPOSE_PROFILES=proxy,admin` 으로 띄운다.
- `restart` 로는 마운트·포트·환경변수 변경이 반영되지 않는다. 반드시 `up -d` 로 한다.

## 7. 확인

```bash
docker compose ps
docker exec passkey-deploy-nginx-1 nginx -t
docker exec passkey-deploy-nginx-1 nginx -T 2>&1 | grep -E '^# configuration file|server_name|listen|ssl_certificate|location'
```

- `ps`: passkey-app·admin-app 이 `healthy`(admin-app 은 최대 90초), nginx·rp-app 이 `Up`.
- `nginx -t`: `test is successful`, 경고(`[warn]`) 없음.
- `nginx -T`: `server_name qa-passkey.crosscert.com`, `listen 443 ssl`·`listen 8443 ssl`, `location /admin` 이 보이면 된다.
  `server_name localhost` 만 보이면 아직 이전 상태다.

서버 안에서 DNS 와 무관하게 확인(`--resolve`):

```bash
P=qa-passkey.crosscert.com
curl -sk --resolve $P:443:127.0.0.1  -o /dev/null -w '%{http_code}\n' https://$P/.well-known/jwks.json  # 200
curl -sk --resolve $P:443:127.0.0.1  -o /dev/null -w '%{http_code}\n' https://$P/actuator/health         # 404
curl -sk --resolve $P:443:127.0.0.1  -o /dev/null -w '%{http_code}\n' https://$P/admin/                  # 200
curl -sk --resolve $P:8443:127.0.0.1 -o /dev/null -w '%{http_code}\n' https://$P:8443/                   # 200 (rp-app 기동 시)
```

## 8. 테넌트 설정 (admin 콘솔 `https://qa-passkey.crosscert.com/admin/`)

- `rpId` = `qa-passkey.crosscert.com` (도메인만)
- `allowedOrigins` = `["https://qa-passkey.crosscert.com:8443"]` (포트까지 정확히)

네이티브 앱 패스키용 `/.well-known/assetlinks.json`·`apple-app-site-association` 은 passkey 도메인 443 에서
rp-app 으로 넘기도록 nginx.conf 에 들어 있다.

## 문제 해결

| 증상 | 확인·조치 |
|---|---|
| nginx 컨테이너가 계속 재시작 | `docker compose logs nginx` — `cannot load certificate` 는 `.env` 의 `TLS_*_NAME` 파일명 오타, `bad password read` 는 암호 걸린 키(2단계), `Permission denied` 는 SELinux — compose 의 두 마운트 끝을 `:ro,z` 로 바꾼다 |
| 502 Bad Gateway | 해당 앱 컨테이너가 안 떠 있다 — `docker compose ps` |
| passkey-app·admin-app 이 함께 재시작 반복 | Redis 가 꺼져 있다 — `docker ps -a \| grep redis` 로 확인, `docker compose -f docker-compose.redis.yml up -d` |
| admin-app 로그에 Flyway(`flywayInitializer`, `Validate failed`)가 보임 | Flyway 가 꺼지지 않았다 — compose 의 admin-app 에 `SPRING_FLYWAY_ENABLED=false` 가 있는지 보고 `docker compose up -d admin-app` |
| 로그에 `Schema-validation: missing table/column` | 스키마가 덜 만들어졌다 — DBA 가 빠진 DDL 을 적용한다(Flyway 를 쓰지 않으므로 앱이 만들지 않는다) |
| RP 에서 패스키 요청이 403 | rp-app 에 forward headers 설정이 없다 — `docker compose config rp-app \| grep FORWARD` 가 `framework` 여야 한다 |
| 서버 안 curl 은 되는데 PC 브라우저에서 8443 안 열림 | 서버·네트워크 방화벽에서 8443 허용 |
| 패스키 등록 시 origin 오류 | 테넌트 `allowedOrigins` 에 `:8443` 까지 들어 있는지 |
| admin 메일의 링크 주소가 틀림 | `.env` 의 `ADMIN_INVITE_BASE_URL=https://qa-passkey.crosscert.com` (뒤에 `/admin` 을 붙이지 않는다) 후 `up -d` |
| nginx.conf 를 고쳤는데 반영 안 됨 | `docker compose up -d --force-recreate --no-deps nginx` (시작할 때만 읽는다) |
