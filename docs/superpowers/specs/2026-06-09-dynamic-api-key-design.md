# 재기동 없는 동적 API Key (Dynamic API Key) 설계

- **날짜**: 2026-06-09
- **상태**: 설계 승인 → 구현 계획 대기
- **범위**: `sdk-java` (핵심), `rp-app` (레퍼런스 구현)
- **passkey-app / admin-app**: 변경 없음 (서버 측은 이미 동적)

---

## 1. 문제 정의

현재 rp-app(고객사 측 클라이언트)은 API Key를 **정적으로 1개만** 보유한다.

- `passkey.api-key` 가 `String` 1개로 `application.yml`/환경변수에 바인딩됨
  (`rp-app/.../config/PasskeyProperties.java:11`)
- `PasskeyClient` 가 부팅 시 **1회** 생성되며, `RedactingRequestInterceptor` 가
  생성자에서 `this.apiKey = config.apiKey()` 로 키를 **final 로 캡처**
  (`sdk-java/.../internal/RedactingRequestInterceptor.java:25`)

결과적으로:

1. **키 교체 시 반드시 재기동** 해야 한다.
2. 어드민에서 키를 **회수해도, 이미 켜진 rp-app 인스턴스는 새 키로 못 갈아탄다**
   (여러 인스턴스면 전부 수동 재기동).
3. "여러 키를 만들어도 다 못 쓴다."

### 1.1 서버 측은 이미 동적 (변경 불필요)

`passkey-app` 은 요청마다 DB 에서 prefix 로 키를 조회하고 revoked/expired 를
검사한다 (`ApiKeyAuthFilter.java:151-172`). 따라서:

- 어드민 회수 → **다음 요청부터 즉시 401** (재기동 불필요)
- 여러 키가 각자 독립적으로 인증됨 (prefix 별 BCrypt 검증)
- `ApiKeyAdminService.rotate()` 가 신·구 키를 **grace 창(기본 24h) 동안 동시
  유효**하게 둠 (`ApiKeyAdminService.java:204-205`)

**즉 병목은 전적으로 클라이언트(rp-app + SDK)가 키를 정적으로 1개만 들고 있다는 점.**

---

## 2. "여러 키" 가치 판단 (재설계 근거)

브레인스토밍에서 "API Key 를 여러 개 만드는 게 의미가 있는가"를 코드 사실로
검증했다. "여러 키"는 두 갈래로 나뉘며 가치가 정반대다.

### 2.1 같은 용도의 키 여러 개 (= 무중단 교체용) → **의미 있음, 유지**

- 서버 rotation 이 신·구 키를 grace 창 동안 공존시킴
- "재기동 없는 교체"를 **무중단**으로 만들려면, 교체 순간 신·구 키가 잠깐
  공존해야 한다. 한 키만 고집하면 "구 키 회수 → 새 키 적용" 사이에 반드시
  빈틈이 생긴다.
- **따라서 "여러 키 공존"은 별개 기능이 아니라 "재기동 없는 교체"를 무중단으로
  만드는 메커니즘 그 자체다.**

### 2.2 다른 용도의 키 여러 개 (= scope 분리) → **현재 도메인에선 YAGNI, 폐기**

- rp-app 은 SDK 메서드 4개(registration/authentication × start/finish)만 호출.
  유일한 사용처는 `WebAuthnController`.
- self-service(`/api/v1/rp/credentials/**`)도 서버가 **registration scope 로 통일**
  (`ApiKeyScopeResolver.java:27`)
- **admin scope 는 RP-facing 엔드포인트가 아예 없음** — DB 에만 있는 예약값
  (`ApiKeyScopeResolver.java:11` 주석)
- rp-app 이 호출하는 모든 경로는 `registration` + `authentication` 두 scope 면
  충분 → **키 1개에 두 scope 다 담으면 끝**

**결론**: scope 별 키 풀/선택 로직은 활용처가 없다. SDK 가 "호출마다 어떤 키를
고를지" 판단하는 복잡도를 도입하지 않는다.

---

## 3. 설계 결정 요약

| 결정 | 내용 |
|------|------|
| scope 키 풀 | **폐기** (YAGNI) |
| 핵심 메커니즘 | **동적 키 소스(`Supplier<String>`)** + **무중단 교체**(서버 grace 와 짝) |
| 키 소스 (rp-app 레퍼런스) | **파일/외부 설정 핫리로드** (의존성 0, 기존 rp-app 파일 패턴과 일관) |
| SDK 시그니처 | `String apiKey` → `Supplier<String> apiKeySupplier` **전면 교체 (breaking)** — 아직 외부 배포 전 |
| 핫리로드 방식 | **mtime 폴링 + 메모리 캐시** (매 요청 디스크 IO 회피) |

---

## 4. SDK 변경 (`sdk-java`) — 핵심

### 4.1 `PasskeyClientConfig`

- `String apiKey` → `Supplier<String> apiKeySupplier`
- 팩토리: `defaults(URI baseUrl, Supplier<String> apiKeySupplier)`
- `Objects.requireNonNull(apiKeySupplier)` — Supplier 자체는 non-null.
  **반환값(키 문자열)의 null/blank 검증은 호출 시점**(아래 interceptor)에서.

### 4.2 `RedactingRequestInterceptor`

생성자에서 키를 캡처하던 것을 → **요청마다 `apiKeySupplier.get()` 호출**로 변경.

```java
// 매 요청마다 현재 유효 키를 다시 묻는다 — "재기동 없는 교체"의 심장
String currentKey = apiKeySupplier.get();
if (currentKey == null || currentKey.isBlank()) {
    throw new IllegalStateException("API key supplier returned null/blank");
}
request.getHeaders().set("X-API-Key", currentKey);
```

- `get()` 이 null/blank → **명확한 예외**로 fail-fast (조용한 401 방지)
- 기존 마스킹 정규식(`API_KEY_HEADER`)은 그대로 유효 — 헤더 렌더링 형태 불변
- Supplier 자체는 interceptor 가 final 로 보관 (Supplier 인스턴스는 불변,
  반환값만 매번 새로 묻음)

### 4.3 영향받는 호출부

`defaults()`, `PasskeyClientContractIT`, 기타 String 시그니처 의존 테스트를
Supplier 형태로 갱신. 가장 단순한 정적 키는 `() -> "pk_..."` 람다로 표현.

---

## 5. rp-app 레퍼런스 구현 — 핫리로드 Supplier

### 5.1 `PasskeyProperties`

`String apiKey` 는 유지하되 의미를 **"초기값/폴백"** 으로 재정의. 신규 프로퍼티 추가:

```yaml
passkey:
  api-key: ${PASSKEY_API_KEY:}              # 폴백(파일 미설정 시)
  api-key-file: ${PASSKEY_API_KEY_FILE:}    # 설정 시 파일이 env 보다 우선
  api-key-reload: ${PASSKEY_API_KEY_RELOAD:10s}  # 파일 폴링 주기
```

### 5.2 신규 `ReloadableApiKeySupplier implements Supplier<String>`

- **파일 mtime 폴링**(기본 10초)으로 변경 감지 → 바뀌었을 때만 다시 읽음
  → 매 요청 디스크 IO 없음 (메모리 캐시 + 변경 감지)
- **파일 미설정 시** `PasskeyProperties.apiKey`(env) 폴백 → **기존 동작 100% 보존**
- 읽은 키를 메모리 캐시. **파일 읽기 실패 시 직전 유효 키 유지(fail-safe)** + WARN 로그
- 트림/공백 처리, 빈 파일이면 폴백
- `get()` 은 hot path 에서 호출되므로 동기화는 가벼운 volatile 캐시 +
  마지막 폴링 시각 비교로 (매 호출 stat 호출도 회피하려면 폴링 간격 내 캐시 재사용)

### 5.3 `PasskeyClientConfiguration`

`props.apiKey()` 직접 주입 → `ReloadableApiKeySupplier` 빈을 SDK config 에 전달.

```java
@Bean
public PasskeyClient passkeyClient(PasskeyProperties props, Supplier<String> apiKeySupplier) {
    return PasskeyClient.of(new PasskeyClientConfig(
            props.baseUrl(), apiKeySupplier, /* ...timeouts... */));
}
```

---

## 6. 무중단 교체 흐름 (서버 grace 와의 합주)

### 6.1 계획적 로테이션 — 재기동 0회

1. 어드민 `rotate()` → 신 키 발급 + 구 키 24h grace 만료 (서버는 둘 다 유효)
2. 운영자가 새 키 평문을 rp-app 키 파일에 기록
3. rp-app 이 다음 폴링(≤10s)에 새 키 픽업 → 이후 호출은 새 키로
4. grace 창 동안 신·구 어느 쪽이든 통과 → **빈틈 없음**
5. 24h 후 구 키 자동 만료 (또는 운영자가 즉시 revoke)

### 6.2 긴급 회수 — 재기동 0회

1. 어드민 revoke → 서버가 **다음 요청부터 즉시 401** (서버는 이미 즉시 반영)
2. 운영자가 키 파일을 새 키로 교체
3. ≤10s 후 rp-app 복구

---

## 7. 테스트

### SDK
- Supplier 가 호출마다 다른 값 반환 시 헤더가 따라 바뀌는지 (MockWebServer 또는
  ContractIT 확장)
- `get()` null/blank → 명확한 예외
- 마스킹: 새 키도 `pk_<prefix>…<redacted>` 로 마스킹되는지

### rp-app
- 파일 변경 → mtime 폴링 → 새 키 픽업
- 파일 읽기 실패 → 직전 유효 키 유지 (fail-safe)
- 파일 미설정 → env(`passkey.api-key`) 폴백
- 빈 파일 → 폴백

---

## 8. 범위에서 제외 (YAGNI)

- scope 별 키 선택 로직 (활용처 없음 — §2.2)
- 키 풀 / 라운드로빈
- admin API 자동 fetch
- 외부 시크릿 매니저(Vault/AWS SM) 직접 연동

> **확장점은 공짜로 남는다**: SDK 가 `Supplier<String>` 인터페이스만 받으므로,
> 고객사는 나중에 시크릿 매니저·admin fetch·scope 선택 등 **어떤 Supplier 구현이든**
> 끼워 넣을 수 있다. rp-app 은 그중 가장 단순한 "파일 핫리로드" 레퍼런스를 제공.

---

## 9. 호환성 / 마이그레이션 노트

- SDK 는 아직 외부 배포 전이므로 breaking 시그니처 변경 허용.
- rp-app 기존 사용자: `passkey.api-key`(env) 만 설정돼 있으면 폴백 경로로
  **동작 불변**. `api-key-file` 을 추가 설정한 경우에만 핫리로드 활성화.
