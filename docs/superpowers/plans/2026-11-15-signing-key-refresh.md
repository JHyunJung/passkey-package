# 서명 키 캐시 주기 갱신 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 서명 키 회전 뒤 passkey-app 의 모든 인스턴스가 재기동 없이 새 ACTIVE 키로 서명하게 한다.

**Architecture:** core `SigningKeyProvider` 에 "DB 의 ACTIVE kid 가 캐시와 다르면 다시 읽는" 메서드를 더하고, passkey-app 의 `@Scheduled` 잡이 1분마다 부른다. 서명 경로와 admin-app 은 건드리지 않는다.

**Tech Stack:** Java 17(core), Kotlin 2.4.21(passkey-app), Spring Boot 4.1, JUnit 5 + Mockito / mockito-kotlin, Testcontainers Oracle.

**Spec:** `docs/superpowers/specs/2026-11-15-signing-key-refresh-design.md` — 구현자는 전부 읽는다.

## Global Constraints

- 작업 위치: worktree `/Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/signing-key-refresh`, 브랜치 `fix/signing-key-refresh`. 시작할 때 `pwd`·`git branch --show-current` 로 확인. 메인 저장소 경로에 쓰지 않는다(상대 경로 사용).
- 기존 `SigningKeyProvider.init()`·`reload()`·`signingKey()`·`publicJwkSet()` 의 동작과 시그니처는 바꾸지 않는다. admin-app 코드는 바꾸지 않는다.
- 서명 요청 경로(`IdTokenIssuer`)에 DB 조회를 추가하지 않는다.
- 설정 키와 기본값은 정확히: `passkey.signing-key.refresh.fixed-delay` = `PT1M`, `passkey.signing-key.refresh.initial-delay` = `PT1M`.
- 분산 lease 를 쓰지 않는다(인스턴스마다 실행해야 한다).
- 새 주석은 한국어, 그 파일의 기존 말투. 작업 번호·"이번 수정" 같은 이력 표현을 주석에 쓰지 않는다. 컴파일러·프레임워크 동작을 주석에 쓸 때는 확인한 것만.
- 커밋 메시지 마지막 줄은 정확히 `Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>` 한 줄. push 하지 않는다. `git add -A` 금지.
- 전체 `./gradlew build`·모듈 전체 테스트는 돌리지 않는다(이 저장소에서 원래 빨갛고 오래 걸린다). 이 계획이 지정한 테스트 클래스만 돌린다. 10분 넘게 걸릴 명령은 백그라운드로.
- 이 세션의 셸은 `git` 이 들어간 복합 명령(`cd x && git …`, 파이프, `$(git …)`)을 거부한다. git 은 단일 명령으로 실행한다.

## Review Focus

1. **ACTIVE 행이 잠깐 없는 순간** — `refreshIfRotated()` 가 예외를 던지거나 캐시를 null 로 만들지 않는다. 서명은 계속된다.
2. **스케줄러가 멈추지 않는다** — 잡에서 어떤 `RuntimeException` 이 나도 다음 tick 이 실행된다(예외가 `@Scheduled` 메서드 밖으로 나가지 않는다).
3. **키가 안 바뀌었으면 봉투를 풀지 않는다** — kid 가 같을 때 `KeyEnvelope` 복호화가 호출되지 않는다.
4. **복호화 실패 시 캐시 유지** — 새 행을 못 풀면 이전 키로 계속 서명한다(대입이 복호화 뒤에 온다).
5. **동시성** — `refreshIfRotated()` 와 `reload()`·`signingKey()` 가 동시에 불려도 `cachedActive` 는 항상 완전한 키를 가리킨다(`volatile` 단일 대입).

---

### Task 1: core — `SigningKeyProvider.refreshIfRotated()`

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/jwt/SigningKeyProvider.java`
- Test: `core/src/test/java/com/crosscert/passkey/core/jwt/SigningKeyProviderTest.java` (기존 파일에 추가, 기존 `setUp`·헬퍼 재사용)

**Interfaces:**
- Produces: `public boolean refreshIfRotated()` — 캐시를 바꿨으면 `true`. ACTIVE 행이 없거나 kid 가 같으면 `false`. 새 행 복호화 실패는 예외로 전파(캐시 불변).

- [ ] **Step 1: 실패하는 테스트 4개 작성** — 기존 `reloadRefreshesCachedActiveAfterRotation` 이 쓰는 방식(mock repo 가 반환하는 ACTIVE 행을 바꿔 줌)을 그대로 쓴다.

  - `refreshIfRotated_sameKid_returnsFalseAndKeepsInstance`: `init()` 후 같은 행을 반환 → `assertThat(provider.refreshIfRotated()).isFalse()`, `assertThat(provider.signingKey()).isSameAs(before)`. 그리고 봉투 복호화가 `init()` 때 한 번만 일어났음을 단언한다(`KeyEnvelope` 를 spy 하거나, 기존 테스트가 실제 `KeyEnvelope` 를 쓰면 복호화에 쓰이는 엔티티 접근자 호출 횟수로 — 기존 테스트 구성에 맞는 방법으로. 어느 쪽도 불가능하면 `isSameAs` 단언으로 대신하고 보고서에 적는다).
  - `refreshIfRotated_newActiveRow_returnsTrueAndSwitchesKid`: repo 가 새 kid 의 행을 반환 → `isTrue()`, `provider.signingKey().getKeyID()` 가 새 kid.
  - `refreshIfRotated_noActiveRow_returnsFalseAndKeepsCache`: repo 가 `Optional.empty()` → `isFalse()`, 예외 없음, `signingKey()` 가 `isSameAs(before)`.
  - `refreshIfRotated_undecodableRow_throwsAndKeepsCache`: 새 kid 지만 봉투가 깨진 행(예: sealed 필드가 잘못된 값) → `assertThatThrownBy(provider::refreshIfRotated)` 가 예외, 이후 `signingKey()` 가 `isSameAs(before)`.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :core:test --tests '*SigningKeyProviderTest' --console=plain -q`
Expected: 컴파일 실패(`refreshIfRotated` 없음).

- [ ] **Step 3: 구현** — `reload()` 아래에 `public boolean refreshIfRotated()`. ACTIVE 행 조회 → 없으면 `false` → 행의 kid 와 `cachedActive.getKeyID()` 비교, 같으면 `false` → `decodeWithPrivate(row)` 결과를 지역 변수에 받은 뒤 `cachedActive` 에 대입, `true`. Javadoc 에는 무엇을 하는지와 "행이 없으면 캐시를 유지한다(회전 트랜잭션 중간에 불릴 수 있다)"만 적는다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :core:test --tests '*SigningKeyProviderTest' --console=plain -q`
Expected: exit 0 (기존 테스트 5개 + 새 4개).

- [ ] **Step 5: 커밋**

```
fix(core): SigningKeyProvider.refreshIfRotated — ACTIVE kid 가 바뀌었을 때만 캐시 갱신
```

---

### Task 2: passkey-app — `SigningKeyRefreshJob` + 통합 테스트 + 문서

**Files:**
- Create: `passkey-app/src/main/kotlin/com/crosscert/passkey/app/…/SigningKeyRefreshJob.kt` (패키지는 기존 구조에서 서명 키를 쓰는 쪽과 가까운 곳 — 구현자 판단, 보고서에 이유 한 줄)
- Create: 단위 테스트 `SigningKeyRefreshJobTest.kt`, 통합 테스트 `SigningKeyRefreshIT.kt` (같은 패키지의 `src/test/kotlin`)
- Modify: `core/src/main/java/com/crosscert/passkey/core/jwt/SigningKeyProvider.java` (클래스 Javadoc 만), `core/src/main/java/com/crosscert/passkey/core/jwt/IdTokenIssuer.java` (주석만), `docs/backlog.md`, 배포 문서(해당 내용이 있을 때)

**Interfaces:**
- Consumes: Task 1 의 `SigningKeyProvider.refreshIfRotated(): boolean`.
- Produces: `class SigningKeyRefreshJob(keys: SigningKeyProvider) { fun runOnce() }` — `@Component`, `runOnce` 에 `@Scheduled(fixedDelayString = "\${passkey.signing-key.refresh.fixed-delay:PT1M}", initialDelayString = "\${passkey.signing-key.refresh.initial-delay:PT1M}")`.

- [ ] **Step 1: 실패하는 단위 테스트 작성** (`SigningKeyRefreshJobTest`, mockito-kotlin)

  - `runOnce_providerThrows_doesNotPropagate`: `whenever(keys.refreshIfRotated()).thenThrow(IllegalStateException("boom"))` → `assertThatCode { job.runOnce() }.doesNotThrowAnyException()`.
  - `runOnce_callsProviderOnce`: `true` 를 반환할 때와 `false` 를 반환할 때 각각 `verify(keys, times(1)).refreshIfRotated()`.
  - `runOnce_isScheduledWithConfiguredKeys`: 리플렉션으로 `SigningKeyRefreshJob::class.java.getMethod("runOnce").getAnnotation(Scheduled::class.java)` 의 `fixedDelayString` 이 `"\${passkey.signing-key.refresh.fixed-delay:PT1M}"`, `initialDelayString` 이 `"\${passkey.signing-key.refresh.initial-delay:PT1M}"` 임을 단언.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :passkey-app:test --tests '*SigningKeyRefreshJobTest' --console=plain -q`
Expected: 컴파일 실패(클래스 없음).

- [ ] **Step 3: `SigningKeyRefreshJob` 구현** — `runOnce()` 는 `try { if (keys.refreshIfRotated()) log.info(…새 kid…) } catch (e: RuntimeException) { log.warn(…, e) }`. false 일 때는 로그 없음. 로거는 파일 최상위 `private val log`(이 모듈의 관례). 클래스 KDoc 에는 왜 있는지(회전은 admin-app 에서 일어나고 이 프로세스의 캐시는 스스로 갱신해야 한다)와 lease 를 쓰지 않는 이유(인스턴스마다 실행해야 한다)를 적는다.

- [ ] **Step 4: 단위 테스트 통과 확인**

Run: `./gradlew :passkey-app:test --tests '*SigningKeyRefreshJobTest' --console=plain -q`
Expected: exit 0.

- [ ] **Step 5: 통합 테스트 작성** (`SigningKeyRefreshIT`) — passkey-app 의 기존 Testcontainers IT(예: `ApiContractIT` 나 가장 가벼운 `@SpringBootTest` IT)가 쓰는 컨테이너·프로퍼티 구성을 그대로 따른다. 시나리오:

  1. 컨텍스트 기동 후 `val oldKid = keys.signingKey().keyID`.
  2. 회전을 흉내 낸다: 기존 ACTIVE 행을 ROTATED 로 바꾸고(엔티티의 회전 메서드 — admin-app `KeyRotationService` 가 쓰는 것과 같은 메서드), 새 ACTIVE 행을 저장한다(`SigningKeyFactory`/`KeyEnvelope` 등 core 가 이미 제공하는 것으로 생성 — `KeyRotationService` 가 새 키를 만드는 방식을 읽고 같은 호출을 쓴다). DB 의 "ACTIVE 는 하나" 유니크 인덱스를 지키는 순서로.
  3. `runOnce()` 호출 전: `keys.signingKey().keyID == oldKid` (아직 캐시).
  4. `job.runOnce()`.
  5. 후: `keys.signingKey().keyID == newKid`, 그리고 `IdTokenIssuer` 로 실제 토큰을 발급해 JWS 헤더의 `kid == newKid`.
  6. `keys.publicJwkSet()` 에 `newKid` 와 `oldKid`(ROTATED) 가 둘 다 있다.
  7. 다른 IT 에 영향을 주지 않도록 테스트가 바꾼 키 상태를 정리하거나(`@DirtiesContext` 또는 원복) 기존 IT 들이 쓰는 격리 방식을 따른다.

  기존 IT 인프라로 이 시나리오를 세울 수 없으면(예: `KeyRotationService` 의 키 생성이 admin-app 전용 코드에만 있음) 멈추고 무엇이 막는지 보고한다 — 추측으로 우회하지 않는다.

- [ ] **Step 6: 통합 테스트 실행** (Docker 필요 — 떠 있다)

Run: `./gradlew :passkey-app:test --tests '*SigningKeyRefreshIT' --console=plain -q` (10분 넘으면 백그라운드)
Expected: exit 0.

- [ ] **Step 7: 인접 회귀 확인**

Run: `./gradlew :core:test --tests '*SigningKeyProviderTest' --tests '*IdTokenIssuerTest' :passkey-app:test --tests '*Jwks*' --tests '*KotlinProxyWiringTest' --console=plain -q`
Expected: exit 0. (`*Jwks*` 에 해당하는 테스트가 없으면 그 필터를 빼고 보고서에 적는다.)

- [ ] **Step 8: 주석·문서 갱신**

  - `SigningKeyProvider` 클래스 Javadoc: "passkey-app 은 재기동 전까지 이전 키로 서명한다 … RP 검증이 실패한다" 문단을 현재 동작으로 바꾼다 — passkey-app 은 `SigningKeyRefreshJob` 이 주기적으로 `refreshIfRotated()` 를 불러 새 ACTIVE 키로 바꾸며, 그 사이(기본 최대 약 1분)에는 ROTATED 가 된 키로 서명하고 그 키는 grace 동안 JWKS 에 남는다. 갱신 주기가 grace 보다 짧아야 한다는 것도 한 줄.
  - `IdTokenIssuer` 주석(~26행)의 같은 문장을 맞춘다.
  - `docs/backlog.md` §0 의 "서명 키 회전 뒤 passkey-app 이 옛 키로 계속 서명한다" 항목을 `[x] ~~…~~` 로 바꾸고 해결 내용(주기 갱신, 설정 키 2개, 고정한 테스트 이름)을 적는다. "수정 전 운영 절차" 문장은 지운다.
  - `deploy/README.md`·`docs/onprem-deployment.md`·`docs/single-instance-deployment.md`·`qa-bundle/README.md` 를 `회전|rotat|재기동` 으로 grep 한다. passkey-app 환경변수 표가 있으면 두 키를(환경변수 표기 `PASSKEY_SIGNING_KEY_REFRESH_FIXED_DELAY` / `…_INITIAL_DELAY`, 기본 PT1M) 그 표의 형식대로 추가하고, 회전 후 재기동을 요구하는 문장이 있으면 고친다. 해당 내용이 없으면 문서는 건드리지 않고 보고서에 "해당 없음"으로 적는다.

- [ ] **Step 9: 컴파일 확인 후 커밋**

Run: `./gradlew :core:compileJava :passkey-app:compileKotlin :passkey-app:compileTestKotlin --console=plain -q` → exit 0.

```
fix(passkey-app): 서명 키 회전 뒤 캐시 주기 갱신 — SigningKeyRefreshJob

회전은 admin-app 에서 일어나는데 passkey-app 은 기동 때 읽은 키만 써서, grace 뒤
옛 키가 JWKS 에서 빠지면 RP 의 ID Token 검증이 실패했다. 1분마다 ACTIVE kid 를
확인해 바뀌었으면 다시 읽는다.
```
