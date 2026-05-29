# On-Premise Deployment Guide

## Overview

설치형(on-prem) 배포 모드. SaaS 모드와 동일한 단일 jar 를 사용하되 런타임에 `passkey.deployment.mode=onprem` 으로 전환한다. 활성화되면:

- 단일 tenant 로 VPD 컨텍스트가 부팅 시 + 요청별 자동 고정 (OnpremTenantPinFilter)
- 라이센스 토큰의 서명·만료가 부팅 시 검증됨 (실패 시 시작 중단)
- 주기적 heartbeat 로 라이센스 서버에 갱신/revocation 확인 (기본 1시간)
- 만료/grace 초과 시 모든 API가 503 으로 차단됨

## 부팅 환경변수

| 환경변수 | 의미 | 기본값 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod,onprem` 권장 | — |
| `PASSKEY_DEPLOYMENT_MODE` | `saas` 또는 `onprem` | `saas` |
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
