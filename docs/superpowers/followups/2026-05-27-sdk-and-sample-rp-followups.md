# SDK + sample-rp followups

Spec: docs/superpowers/specs/2026-05-27-sdk-and-sample-rp-design.md
Plan: docs/superpowers/plans/2026-05-27-sdk-and-sample-rp.md

## Manual smoke result (T18)

운영자가 7-step quickstart 를 직접 따라 하면서 체크박스를 채운다.

- [ ] T18.1 `docker compose ps` — oracle + redis Up
- [ ] T18.2 `passkey-app` + `admin-app` 가 8080/8081 에 LISTEN
- [ ] T18.3 `cd examples/sdk-java && ./gradlew publishToMavenLocal` 성공
- [ ] T18.4 `./scripts/bootstrap-sample-rp.sh` 가 health curl 까지 통과
- [ ] T18.5 `set -a; source .env; set +a && ./gradlew bootRun` 성공 (9090 LISTEN)
- [ ] T18.6 브라우저: /register → username/displayName → "Passkey registered" 결과
- [ ] T18.7 브라우저: /login → 같은 username → / 로 이동 + "로그인됨" 헤더
- [ ] T18.8 브라우저: /logout → "비로그인" 헤더
- [ ] T18.9 sample-rp 와 passkey-app 양쪽 로그에 동일한 X-Trace-Id

## Deferred (spec § 10)

1. **Maven Central 배포 + group/artifact 확정** — 외부 고객사 통합 단계.
2. **SDK 자동 재시도/서킷브레이커 (Resilience4j 옵션)** — RP 통합 사례 2개 이상 쌓이고 운영 페일 패턴이 드러나면.
3. **JWKS pre-warm 옵션** — 첫 로그인 지연이 문제 될 때.
4. **sample-rp 영구 저장소 (H2 file / Postgres)** — 데모가 운영 시나리오로 확장될 때.
5. **멀티 tenant sample-rp 시연** — 별도 spec.
6. **Kotlin/Scala 친화 표면 (DSL 빌더)** — 외부 통합 사례가 Kotlin 베이스이면.
7. **WebAuthn level 3 마이그레이션** — 브라우저 표준 진화 시.
8. **admin 2FA via passkey (admin dogfood)** — 원래 Phase 4 합의의 두 번째 절반. 별도 spec.

## In-loop findings (codex review 가 잡은 것 중 결함 아닌 항목)

본 phase 진행 중 codex review 가 잡은 항목 중, 의도된 동작이거나 deferred 인 것:

- **SDK RedactingRequestInterceptor 의 `idToken` 16자 prefix 노출** (T5) — 본문 redaction 의 절충안. dogfood DEBUG 환경 한정, prod 에서는 `logging.level.com.crosscert.passkey.sdk: INFO` 권장.
- **`"signature"` 키 false-positive 가능성** (T5 redaction regex) — passkey-app wire 에는 ceremony response 안에서만 나타나므로 실 영향 없음. JWT JOSE header 등 다른 컨텍스트로 SDK 가 확장되면 prefix 매칭으로 보강.
- **JwksCache 의 thundering herd** (T4) — `AtomicReference` snapshot + miss 시 중복 fetch 가능. 첫 호출 동시성에서만 발생, partial corrupt 없음. dogfood 수용.
- **InMemoryUserStore TOCTOU race** (T11) — `putIfAbsent` 로 fix 적용 완료.
- **fixture 비현실성** (T6) — aaguid hex 포맷, `error-401.json` 의 RFC 7807 problem+json, timestamp `Z` suffix, Content-Type — 모두 fix 적용 완료.
- **WebAuthnController registerOptions 의 pending state 미정리** (T13) — SDK 가 throw 하면 byUsername 예약이 남음. dogfood 수용. prod 에선 try/catch + discardPending 필요.
- **iss/aud 검증** (T13) — `passkey.issuer-base` 별도 프로퍼티 추가. bootstrap 스크립트가 passkey-app 의 `--passkey.id-token.issuer-base` 와 일치시키도록 README 가이드.

## 다음 작업

- **SampleRpSmokeIT 자동 부팅** (T17 보강): `@Disabled` 제거 + Testcontainers 로 oracle + redis + passkey-app + admin-app 동반 부팅. 현재는 manual.
- **PasskeyClientContractIT 429 path 보강** (T6): RateLimitFilter 의 problem+json wire 도 stub 에 추가. SDK 가 Retry-After 헤더와 함께 `PasskeyRateLimitException` 던지는지 확인.
- **PageResponse.from(Page<T>)** — 페이지네이션 엔드포인트가 추가될 때 (현재는 wire 호환만 위해 비어있음).
