# 서명 키 회전 뒤 passkey-app 캐시 갱신 — 설계

2026-11-15

## 1. 문제

admin 콘솔에서 서명 키를 회전하면, passkey-app 은 재기동 전까지 옛 키로 ID Token 에 서명한다.

- `SigningKeyProvider` 는 `@PostConstruct init()` 에서 ACTIVE 키를 한 번 읽어 `cachedActive` 에 둔다. 다시 읽는 것은 `reload()` 뿐이다.
- `reload()` 를 부르는 운영 코드는 admin-app `KeyRotationService` 뿐이다. passkey-app 에는 갱신 경로가 없다.
- 회전된 옛 키는 ROTATED 가 되고, grace(`passkey.key-rotation.grace`, 기본 PT30M) 뒤 `KeyExpirationJob` 이 REVOKED 로 바꾼다.
- `JwksAssembler` 는 ACTIVE·ROTATED 만 내보내므로 REVOKED 키는 JWKS 에서 빠진다.
- 그 뒤 passkey-app 이 발급한 토큰은 RP 의 sdk `IdTokenVerifier` 에서 `Unknown or non-RSA kid` 로 실패한다.

즉 회전 후 약 30분 뒤부터 RP 로그인이 깨진다. passkey-app 을 여러 대 띄워도 전부 해당한다.

## 2. 목표 / 비목표

**목표**
- 회전 뒤 passkey-app 의 모든 인스턴스가 재기동 없이, grace 가 끝나기 훨씬 전에 새 ACTIVE 키로 서명한다.
- 서명 요청 경로에 DB 조회를 추가하지 않는다.
- 갱신에 실패해도 서명은 멈추지 않는다(기존 키 유지).

**비목표**
- admin-app 쪽 동작 변경. admin-app 은 ID Token 을 발급하지 않고, 회전한 인스턴스는 이미 `reload()` 한다.
- 인스턴스 간 즉시 전파(Redis pub/sub 등).
- grace 값·회전 절차·JWKS 구성 변경.
- 최초 키 생성(`createInitialKey`) 경로 변경.

**실패 조건**
- 갱신 중 예외가 스케줄러 밖으로 새어 이후 tick 이 멈춘다.
- 키가 바뀌지 않았는데 매 tick 마다 봉투를 풀어 개인키를 다시 복호화한다.
- ACTIVE 행이 잠깐 없을 때(회전 트랜잭션 중간 등) 캐시를 비우거나 서명이 실패한다.

## 3. 접근

검토한 세 가지:

1. **passkey-app 주기 갱신** — 채택. 짧은 주기로 DB 의 ACTIVE kid 를 확인해 캐시와 다르면 다시 읽는다. 인스턴스마다 독립적이라 조율이 필요 없고, 서명 경로는 그대로다.
2. **서명할 때마다 DB 조회** — 항상 최신이지만 인증 완료 요청마다 조회가 늘고, DB 장애가 곧 서명 장애가 된다.
3. **Redis 알림** — 즉시 반영되지만 알림이 유실될 수 있어 결국 주기 확인이 필요하다. 1 만으로 충분하다.

사용자가 1 을 승인했다.

## 4. 설계

### core — `SigningKeyProvider`

메서드 하나를 더한다.

```java
/**
 * DB 의 ACTIVE 키가 캐시와 다르면(kid 비교) 다시 읽어 캐시를 바꾼다.
 * @return 캐시를 바꿨으면 true
 */
public boolean refreshIfRotated()
```

- `repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE")` 로 현재 ACTIVE 행을 읽는다.
- 행이 없으면 아무것도 하지 않고 false 를 반환한다(캐시 유지). `reload()` 처럼 예외를 던지지 않는다 — 주기 호출이라 회전 중간에 걸릴 수 있다.
- 행의 kid 가 캐시된 키의 kid 와 같으면 false. **이 경우 봉투를 풀지 않는다.**
- 다르면 `decodeWithPrivate(row)` 로 복호화해 `cachedActive` 를 바꾸고 true.
- 복호화 실패 등 예외는 그대로 던진다(캐시는 대입 전이라 유지된다). 삼키는 것은 호출자 몫이다.

기존 `init()`·`reload()`·`signingKey()`·`publicJwkSet()` 의 동작은 바꾸지 않는다.

### passkey-app — `SigningKeyRefreshJob`

`com.crosscert.passkey.app.config` 또는 `…app.security` 가 아닌, 서명 키를 쓰는 쪽과 가까운 패키지에 둔다(구현자 판단, 기존 패키지 구조를 따른다).

```kotlin
@Component
class SigningKeyRefreshJob(private val keys: SigningKeyProvider) {
    @Scheduled(
        fixedDelayString = "\${passkey.signing-key.refresh.fixed-delay:PT1M}",
        initialDelayString = "\${passkey.signing-key.refresh.initial-delay:PT1M}",
    )
    fun runOnce()
}
```

- `keys.refreshIfRotated()` 를 부른다. true 면 INFO 로 새 kid 를 남긴다. false 면 로그를 남기지 않는다(1분마다 도는 잡이다).
- 모든 `RuntimeException` 을 잡아 WARN 으로 남기고 끝낸다. 예외가 `@Scheduled` 밖으로 나가지 않는다. 다음 tick 에 다시 시도한다.
- 분산 lease 를 쓰지 않는다 — 인스턴스마다 자기 캐시를 갱신해야 하므로 모두가 실행해야 한다.
- passkey-app 은 이미 `@EnableScheduling` 이다.

### 설정

| 키 | 기본값 | 뜻 |
|---|---|---|
| `passkey.signing-key.refresh.fixed-delay` | `PT1M` | 확인 주기 |
| `passkey.signing-key.refresh.initial-delay` | `PT1M` | 기동 후 첫 확인까지 |

주기는 grace(PT30M)보다 충분히 짧아야 한다. 기본값에서는 회전 뒤 최대 약 1분 동안 옛(ROTATED) 키로 서명하고, 그 키는 grace 동안 JWKS 에 남아 있으므로 검증은 계속 통과한다. 주기를 grace 이상으로 설정하는 것을 기동 시 막지는 않는다(두 값이 서로 다른 앱의 설정이다) — 문서에 적는다.

### 문서·주석

- `SigningKeyProvider` 클래스 Javadoc 과 `IdTokenIssuer` 주석의 "passkey-app 은 재기동 전까지 이전 키로 서명한다"는 문장을 새 동작(주기 갱신, 최대 지연)으로 바꾼다.
- `docs/backlog.md` §0 의 해당 항목을 완료 처리하고 "수정 전 운영 절차(재기동)"가 더는 필요 없음을 적는다.
- 배포 문서(`deploy/README.md` 등)에 환경변수 표가 있으면 새 키 두 개를 추가한다. 회전 후 재기동을 안내하는 문장이 있으면 고친다.

## 5. 테스트

- **core 단위** (`SigningKeyProviderTest`, 기존 mock 구성 사용)
  - kid 가 같으면 false 이고 `signingKey()` 가 같은 인스턴스다.
  - ACTIVE 행이 바뀌면 true 이고 `signingKey().getKeyID()` 가 새 kid 다.
  - ACTIVE 행이 없으면 false 이고 캐시가 유지된다(예외 없음).
  - 새 행의 복호화가 실패하면 예외가 나가고 캐시는 이전 키 그대로다.
- **passkey-app 단위** (`SigningKeyRefreshJob`)
  - provider 가 예외를 던져도 `runOnce()` 가 예외 없이 끝난다.
  - provider 가 true/false 를 반환할 때 각각 한 번만 호출하고 끝난다.
- **passkey-app 통합** (Testcontainers Oracle, 기존 IT 구성 사용)
  - 기동 후 DB 에서 회전을 흉내 낸다(기존 ACTIVE → ROTATED, 새 ACTIVE 삽입 — `SigningKeyFactory`·`KeyEnvelope` 로 만든 행).
  - `runOnce()` 전에는 `IdTokenIssuer` 가 옛 kid 로, 후에는 새 kid 로 서명한다.
  - 새 토큰의 kid 가 `/.well-known/jwks.json` 응답에 있다.
  - 기존 IT 인프라로 이 테스트를 세울 수 없으면 그 이유를 보고하고 단위 테스트로 대신한다.
- 스케줄 등록 확인: `SigningKeyRefreshJob` 빈이 컨텍스트에 있고 `runOnce` 에 `@Scheduled` 가 붙어 있다(리플렉션 단언 또는 기존 프록시 배선 테스트 방식).

## 6. 구현자 재량

- `SigningKeyRefreshJob` 의 패키지와 로그 문구.
- 통합 테스트에서 회전을 흉내 내는 구체적 방법(리포지토리 직접 조작 등).
- kid 비교에 쓰는 엔티티 접근자(이미 있는 것을 쓴다).
