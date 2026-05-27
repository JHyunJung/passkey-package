# Passkey SDK + Sample RP

`:passkey-app` 의 RP API 를 외부 RP 개발자 관점에서 dogfooding 하는 묶음.

- `sdk-java/` — 중간 레벨 도메인 Java 클라이언트 (`com.crosscert.passkey:passkey-sdk-java:0.1.0-SNAPSHOT`).
- `sample-rp/` — `sdk-java` 만 의존하는 Spring Boot 3.5 데모 앱. `:9090`.
- 테스트 페이지 — `sample-rp` 가 서빙하는 Thymeleaf 3개 페이지.

> **이 두 프로젝트는 Passkey2 의 `settings.gradle.kts` 에 포함되지 않는다** (Phase 0 § 9).
> main merge 후 sibling 디렉토리 (`../sdk-java`, `../sample-rp`) 로 분리될 예정.

## 사전 조건

- Passkey2 의 `docker-compose up -d` (Oracle, Redis)
- `passkey-app`, `admin-app` 기동 (단 passkey-app 은 `passkey.id-token.issuer-base` 가 `http://localhost:8080` 이어야 sample-rp 의 ID Token iss 검증 통과)
- admin-app 시드 계정 — `alice@crosscert.com` / `alice-temp-pw` (V11)
- `jq`, `curl` 설치 (bootstrap 스크립트 의존)

## 7-step quickstart

```
[1] Passkey2: docker compose up -d
[2] Passkey2: ./gradlew :passkey-app:bootRun \
              --args="--passkey.id-token.issuer-base=http://localhost:8080"  (별 터미널)
[3] Passkey2: ./gradlew :admin-app:bootRun          (별 터미널)
[4] sdk-java: cd examples/sdk-java && ./gradlew publishToMavenLocal
[5] sample-rp: cd ../sample-rp && ./scripts/bootstrap-sample-rp.sh
[6] sample-rp: set -a && source .env && set +a && ./gradlew bootRun
[7] 브라우저: http://localhost:9090
```

[2]번의 `--args` 또는 `passkey-app/src/main/resources/application-local.yml` 의 추가로
issuer-base override 가 필요한 이유: 기본값이 `https://passkey.crosscert.com` 인데,
sample-rp 의 PASSKEY_ISSUER_BASE 가 그것과 일치해야 ID Token 의 `iss` 검증이 통과한다.

`bootstrap-sample-rp.sh` 가 환경 변수로 받을 수 있는 값들:
- `ADMIN_EMAIL`/`ADMIN_PASS` — 다른 admin 계정 사용 (기본 alice)
- `RP_ID`/`RP_NAME`/`ORIGIN` — 다른 도메인에서 데모 (기본 localhost)
- `ISSUER_BASE` — passkey-app 의 issuer-base override 와 일치해야 함
- `TENANT_SLUG` — 데모 tenant 의 slug (기본 sample-rp-demo)

## Manual smoke checklist (5분)

bootstrap → bootRun 후 다음 4 단계를 직접 따라 한다:

1. `http://localhost:9090/register` → username + displayName → **Register passkey** → "Passkey registered" JSON 출력
2. `http://localhost:9090/login` → 같은 username → **Login with passkey** → `/` 로 리다이렉트, 헤더에 "로그인됨: <username>"
3. `/` 페이지의 **Logout** → 헤더가 "비로그인" 으로 돌아옴
4. 두 서버 로그에 동일한 `X-Trace-Id` 가 찍히는지 확인 (sample-rp 와 passkey-app)

## Virtual authenticator (실 인증기 없는 환경)

Chrome devtools → `⋮` → More tools → **WebAuthn** → "Enable virtual authenticator environment" 체크 → "Add" 로 가상 authenticator 등록. 데모 동안만 활성화하고 끝나면 끄자.

Touch ID / Windows Hello / 외장 YubiKey 도 그대로 동작.

## 알려진 한계

- `localhost` 전용 (WebAuthn HTTPS 강제 예외). `127.0.0.1` 로 접근하면 RP id 매칭 실패.
- in-memory 세션·user store — sample-rp 재시작 시 등록 데이터 유실. README 가 dogfood scope 이므로 의도된 한계.
- 운영 환경에서는 `application.yml` 의 `logging.level.com.crosscert.passkey.sdk` 를 `INFO` 이하로 (DEBUG 로그가 마스킹은 되지만 안전 기본값은 INFO).
- bootstrap 스크립트는 idempotent 가 아님. 같은 slug 로 두 번 실행하면 admin-app 이 409 Conflict 반환. 운영자가 의식적으로 tenant 를 admin-ui 에서 삭제 후 재실행해야 함.

## passkey-app 응답 envelope 이 바뀌면

`sdk-java/src/test/java/com/crosscert/passkey/sdk/PasskeyClientContractIT.java` 가 가장 먼저 깨진다.
passkey-app phase PR 에 `sdk-java/src/test/resources/contract/*.json` fixture 갱신이 함께
들어가야 한다. 그 갱신을 강제하는 회귀 채널이 이 contract test 의 존재 이유.
