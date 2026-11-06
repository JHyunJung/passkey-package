# On-Premise Deployment Guide

## Overview

설치형(on-prem) 배포 모드. SaaS 모드와 동일한 단일 jar 를 사용하되 런타임에 `passkey.deployment.mode=onprem` 으로 전환한다. 활성화되면:

- 단일 tenant 로 테넌트 컨텍스트(앱 레벨 Hibernate `@Filter`)가 부팅 시 + 요청별 자동 고정 (OnpremTenantPinFilter)
- 라이센스 토큰의 서명·만료가 부팅 시 검증됨 (실패 시 시작 중단)
- 주기적 heartbeat 로 라이센스 서버에 갱신/revocation 확인 (기본 1시간)
- 만료/grace 초과 시 모든 API가 503 으로 차단됨

## 부팅 환경변수

| 환경변수 | 의미 | 기본값 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` (필수) | — |
| `PASSKEY_DEPLOYMENT_MODE` | `onprem` (이 가이드 적용 시) | `saas` |
| `PASSKEY_LICENSE_PATH` | 라이센스 JWS 파일 절대경로 | `/etc/passkey/license.jwt` |
| `PASSKEY_LICENSE_CACHE_PATH` | 캐시 파일 절대경로 (heartbeat 결과 저장) | `/var/lib/passkey/license-cache.jwt` |
| `PASSKEY_LICENSE_ISSUER` | 라이센스 발급자 식별자 | `license.crosscert.com` |
| `PASSKEY_LICENSE_AUDIENCE` | 환경 식별자 | `passkey-onprem` |
| `PASSKEY_LICENSE_HEARTBEAT_URL` | 라이센스 서버 base URL | `https://license.crosscert.com/v1/license` |
| `PASSKEY_LICENSE_HEARTBEAT_INTERVAL` | ISO-8601 Duration | `PT1H` |

## 라이센스 파일 수령

영업 담당자가 `license.jwt` 파일을 제공한다. 파일은 단일 JWS compact serialization (한 줄, `eyJ` 로 시작).

1. 파일을 `PASSKEY_LICENSE_PATH` 위치에 배치 (기본 `/etc/passkey/license.jwt`).
2. 권한 설정: `chmod 600 /etc/passkey/license.jwt`, owner = passkey 프로세스 실행 user.
3. 서비스 재시작.

## Production public key 교체

설치 jar 내부 `core/src/main/resources/license-public.ed25519.pub` 는 **placeholder** (테스트 키와 동일). 프로덕션 배포 전 반드시 실제 Crosscert 라이센스 발급자 public key 로 교체:

1. Crosscert 에서 `license-public.ed25519.pub` (PEM 형식) 수령
2. 빌드 시 `src/main/resources/` 의 파일을 교체 후 jar 재빌드
3. 또는 jar 의 해당 리소스를 외부 디렉토리로 override (`spring.config.additional-location` 등 — 운영팀 표준에 따름)

## 운영자 액션 매트릭스

| LicenseState | UI 배너 | 운영자 액션 |
|---|---|---|
| VALID | (없음) | 없음 |
| WARNING | 노란 배너 — "N일 후 만료" | 영업에 갱신 요청. 새 라이센스 파일 받아 교체 후 서비스 재시작 |
| NETWORK_GRACE | 주황 배너 — "N시간 내 차단" | 네트워크/방화벽 점검. license 서버 도달성 확인 (`curl $PASSKEY_LICENSE_HEARTBEAT_URL/{jti}/verify`) |
| DEAD | 빨간 전체 페이지 | 영업 문의. 라이센스 갱신 + 서비스 재시작 |

## /actuator/health 의 license 컴포넌트

- VALID / WARNING / NETWORK_GRACE → `UP`
- DEAD → `DOWN` (전체 health 도 DOWN)

운영 모니터링에서 `actuator/health` 의 `components.license.status` 를 watch 하면 만료 직전 자동 페이지 가능.

## 시계 동기화 (NTP) — **필수**

라이센스 만료·grace period 가 시스템 시계 기반이다. NTP 동기화는 필수:

- 시스템 시간이 `lastVerifiedAt` 보다 과거로 돌아가면 자동으로 DEAD 상태 진입 (rollback 공격 보호)
- 잘못된 시계로 인한 false DEAD 시 운영자가 NTP 복구 후 서비스 재시작 필요

## 업그레이드 배포 — Spring Boot 4.1 로 처음 교체할 때 (1회): admin 세션 정리 → 전원 재로그인

Spring Boot 4.1(Spring Framework 7 · Spring Security 7) 빌드는 admin-app 의 Spring Session
(Redis 에 JDK 직렬화로 저장) 형식이 이전(Spring Boot 3.5) 빌드와 **호환되지 않는다**. 이전 빌드가
남긴 세션을 새 admin-app 이 읽지 못하므로, 교체 시 **한 번** admin 세션 키를 지운다.
**admin 콘솔 사용자는 전원 다시 로그인**해야 한다. passkey-app(등록·인증 API)·rp-app 은 영향 없다.

1. **모든 호스트의 이전 버전 admin-app 인스턴스를 전부 중지**한다(`--scale` 복제본 포함).
   하나라도 살아 있으면 키를 지운 뒤에도 그 인스턴스가 옛 형식 세션을 다시 써 넣는다.
   passkey-app 은 계속 돌아도 된다.
2. Redis 에서 admin 세션 네임스페이스 `spring:session:*` 키만 삭제하고, 남은 키가 0 인지 확인한다
   (`admin-app` 의 `spring.session.data.redis.namespace` 기본값. 바꿔 운영 중이면 그 값으로).
   아래 명령이 마지막에 `OK:` 를 출력하고 exit 0 으로 끝난 경우에만 3단계로 간다.
   `중단:` 이 나오면(접속·인증 실패, 키 잔존) 원인을 고치기 전까지 새 admin-app 을 띄우지 않는다.
3. 새 버전 admin-app 기동

삭제 대상은 `spring:session:*` 뿐이다. passkey-app 의 챌린지(`challenge:*`)·rate limit 키가 같은
Redis 에 있으므로 **`FLUSHALL`/`FLUSHDB` 는 쓰지 않는다**.

먼저 앱이 쓰는 Redis 접속 값(`REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`)을 셸에 올린다.
compose 로 배포했다면 `deploy/.env` 를 읽는다(그 외 배포는 같은 세 변수를 직접 `export`):

```bash
cd deploy
set -a; . ./.env; set +a          # 값의 따옴표를 셸이 벗겨준다
```

그다음 아래 블록을 통째로 붙여 넣는다. `bash -euo pipefail` 로 따로 실행하므로 어느 단계든
실패하면 그 자리에서 0 이 아닌 코드로 끝나고, 로그인 셸(zsh 포함)은 영향받지 않는다.
`REDIS_PASSWORD` 가 비어 있으면(requirepass 없는 Redis) 인증 없이 접속한다.

```bash
bash -euo pipefail <<'EOF'
# REDIS_HOST / REDIS_PORT / REDIS_PASSWORD 는 위에서 셸에 올린 값을 쓴다
: "${REDIS_HOST:?REDIS_HOST 가 비어 있음}"
if [ -n "${REDIS_PASSWORD:-}" ]; then export REDISCLI_AUTH="$REDIS_PASSWORD"; fi
rc() { redis-cli -h "$REDIS_HOST" -p "${REDIS_PORT:-6379}" "$@"; }

# 1) 접속·인증 확인 — PONG 이 아니면 중단
pong=$(rc ping 2>&1) || true
[ "$pong" = PONG ] || { echo "중단: Redis 접속/인증 실패 ($pong)" >&2; exit 1; }

# 2) admin 세션 키만 삭제 (spring:session:*)
echo "삭제 대상: $(rc --scan --pattern 'spring:session:*' | wc -l | tr -d ' ') 개"
rc --scan --pattern 'spring:session:*' \
  | xargs -r -n 100 redis-cli -h "$REDIS_HOST" -p "${REDIS_PORT:-6379}" unlink > /dev/null

# 3) 검증 — 남은 키가 0 이 아니면 중단
left=$(rc --scan --pattern 'spring:session:*')
[ -z "$left" ] || { echo "중단: spring:session 키가 남아 있음" >&2; exit 1; }
echo "OK: admin 세션 키 0 — 새 admin-app 을 기동해도 된다"
EOF
```

비밀번호는 `REDISCLI_AUTH` 로 넘기므로 명령줄·`ps` 에 드러나지 않는다.

Redis 를 `deploy/docker-compose.redis.yml` 컨테이너로 운영한다면 호스트에 `redis-cli` 가 없어도
컨테이너 안에서 같은 확인·삭제·검증을 할 수 있다. 위처럼 `deploy/` 에서 `.env` 를 읽은 셸에서
실행한다(compose 파일이 `REDIS_BIND_ADDR` 를 요구한다). 컨테이너의 sh(busybox)에는 `pipefail`
이 없어 PONG 확인과 삭제 후 재조회로 실패를 잡는다:

```bash
export REDIS_BIND_ADDR=${REDIS_BIND_ADDR:-$REDIS_HOST}   # 0.0.0.0 금지
docker compose -f docker-compose.redis.yml exec -T redis sh -c '
  export REDISCLI_AUTH="$REDIS_PASSWORD"
  pong=$(redis-cli ping 2>&1)
  [ "$pong" = PONG ] || { echo "중단: Redis 접속/인증 실패 ($pong)" >&2; exit 1; }
  echo "삭제 대상: $(redis-cli --scan --pattern "spring:session:*" | wc -l) 개"
  redis-cli --scan --pattern "spring:session:*" | xargs -r -n 100 redis-cli unlink > /dev/null
  left=$(redis-cli --scan --pattern "spring:session:*") || { echo "중단: 검증 조회 실패" >&2; exit 1; }
  [ -z "$left" ] || { echo "중단: spring:session 키가 남아 있음" >&2; exit 1; }
  echo "OK: admin 세션 키 0"
'
```

`sh -c '…'` 의 작은따옴표는 그대로 둔다 — `$REDIS_PASSWORD` 는 서버 셸이 아니라 컨테이너
안의 같은 이름 환경변수로 풀린다. Redis 컨테이너가 멈춰 있으면 `exec` 자체가 실패한다(exit ≠ 0).

이후 같은 4.1 계열 빌드끼리의 재배포에는 이 단계가 필요 없다.

## 부팅 실패 트러블슈팅

| 오류 메시지 (stderr) | 원인 | 조치 |
|---|---|---|
| `License file not found at /etc/passkey/license.jwt` | 파일 없음 | 라이센스 파일 배치 후 재시작 |
| `License signature invalid` | 다른 키로 서명되었거나 파일 손상 | 영업에서 재발급 (production public key 교체 누락 여부도 확인) |
| `License audience mismatch: ...` | aud 가 `passkey-onprem` 이 아님 | 영업에서 재발급 |
| `License expired: exp=...` | 만료된 토큰 | 갱신된 라이센스 수령 |
| `License not yet valid: nbf=...` | 시계가 nbf 보다 과거 | NTP 점검 |

## Heartbeat 응답 포맷 (라이센스 서버 측 명세)

설치 서버가 호출: `GET {PASSKEY_LICENSE_HEARTBEAT_URL}/{jti}/verify`

기대 응답 (200 OK):
```json
{
  "status": "active",
  "latestToken": "eyJ..."
}
```

- `status` 가 `"active"` 가 아니면 (예: `"revoked"`) → 설치 서버가 onHeartbeatFailure 처리 → grace period 시작
- `latestToken` 이 새로운 JWS 면 캐시 갱신. 같은 jti 에 더 미래 exp 가 발급되어 있으면 그것으로 자동 갱신

## 로그 검색

자세한 cookbook 은 `docs/logging-operations.md` 의 "License (on-prem 전용)" 섹션 참고.
