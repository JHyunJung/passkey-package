# rp-app 사용자 매핑 JSON 파일 영속화 — 설계

- **작성일**: 2026-06-07
- **상태**: 승인됨 (구현 대기)
- **범위**: `rp-app` 모듈 한정

## 1. 문제

`InMemoryUserStore` 는 `username → userHandle → (credentialId, displayName, createdAt)`
매핑을 두 개의 `ConcurrentHashMap`(`byHandle`, `byUsername`)에만 보관한다. rp-app 을
재기동하면 이 맵이 비워지면서 passkey-app(Oracle)에 credential 이 살아있어도 rp-app
측 매핑이 사라져 인증이 끊긴다.

구체적 실패 경로:

- **`authenticate/finish`** — ID Token 검증은 통과하지만
  `users.findByUserHandle(claims.sub())` 이 빈 맵에서 실패 → `"unknown sub"` 로 로그인
  거부 (`WebAuthnController.java:153-158`).
- **`authenticate/begin` (typed flow)** — `findByUsername()` 실패로 `userHandle=null`
  처리 (`WebAuthnController.java:92-93`).

재현: 등록·인증까지 마친 패스키가 rp-app 재기동 후 인증 불가.

## 2. 목표 / 비목표

### 목표
- rp-app 재기동 후에도 **확정 등록된** user↔handle↔credential 매핑이 유지되어 인증이
  계속 성공한다.
- 외부 인프라(Oracle/Redis) 추가 없이 해결한다.
- 공개 API(`InMemoryUserStore` 의 메서드 시그니처)와 컨트롤러/DTO/SDK/세션을 바꾸지
  않는다.

### 비목표 (YAGNI)
- 다중 인스턴스 동시 쓰기 안전성(파일 락) — 단일 인스턴스 데모를 가정.
- pending user 의 TTL/만료 정리 — 재기동으로 자연 해소되므로 불필요.
- 세션(`SessionKeys.USER`) 영속화 — 별개 문제, 범위 밖.
- rp-app 의 데모 위상 변경 — 여전히 데모/통합테스트용.

## 3. 결정 사항

| 항목 | 결정 | 근거 |
|---|---|---|
| 위상 | 데모 유지, 재기동만 견딤 | 단일 인스턴스 가정 |
| 저장소 | 파일 기반, JSON 직렬화 (Jackson) | 외부 의존 0, 육안 확인·디버그 용이 |
| 변경 범위 | `InMemoryUserStore` 내부만 | 컨트롤러/SDK/세션 불변 |
| 영속화 대상 | **확정 user 만** (credentialId ≠ null) | pending 은 재기동으로 정리 |
| 파일 경로 | 설정 가능 `rp.user-store.file`, 기본 `./data/rp-app-users.json` | 환경별 오버라이드 |

## 4. 아키텍처

유일한 변경 지점은 `InMemoryUserStore`. 맵은 in-memory 캐시로 유지하고, **확정 user 에
한해** 디스크에 미러링한다.

```
createPending(pending) ──────────────┐
                                      ├─ byHandle / byUsername (ConcurrentHashMap, 캐시)
confirmRegistration(확정) ─persist()──┘                  │
                                                         ▼ (credentialId≠null 만)
@PostConstruct load() ◀──────────  data/rp-app-users.json  (JSON List<RpAppUser>)
```

## 5. 컴포넌트 동작

### 5.1 `@PostConstruct load()`
- 파일이 존재하면 `List<RpAppUser>` 로 역직렬화해 `byHandle`/`byUsername` 복원.
- 읽힌 user 는 모두 확정 상태(credentialId 보유)이므로 양쪽 맵을 모두 채운다.
- 파일이 없거나 손상되었으면 **빈 상태로 시작 + WARN 로그**. 절대 기동을 크래시하지
  않는다.

### 5.2 `createPending()`
- 현행 그대로 메모리에만 기록. `persist()` 호출하지 않는다.
- 부수효과: 재기동 시 pending 이 사라져 stale username 예약이 자연 해소된다.

### 5.3 `confirmRegistration()`
- 맵 갱신(`computeIfPresent`) 후 `persist()` 호출.
- 이 시점의 user 는 credentialId 를 가진 확정 상태.

### 5.4 `persist()` (`synchronized`)
- `byHandle.values()` 중 **credentialId ≠ null** 만 필터해 JSON 배열로 직렬화.
- **원자적 쓰기**: temp 파일에 쓴 뒤 `Files.move(..., ATOMIC_MOVE)` 로 교체.
- 부모 디렉토리가 없으면 생성(`Files.createDirectories`).

## 6. 데이터 흐름 (재기동 시나리오)

1. 등록 완료 → `confirmRegistration` → `data/rp-app-users.json` 에 확정 user 기록.
2. rp-app 재기동 → `load()` 가 파일에서 user 복원 → 양쪽 맵 채워짐.
3. `authenticate/begin` → `findByUsername` 성공 → userHandle 정상 전달.
4. `authenticate/finish` → `findByUserHandle(claims.sub())` 성공 → **로그인 성공**.

## 7. 에러 처리

| 상황 | 처리 |
|---|---|
| 파일 읽기 실패(손상/권한) | WARN 로그 후 빈 맵으로 계속. 기동 보장. |
| 파일 쓰기 실패 | ERROR 로그. 예외를 컨트롤러로 **전파하지 않음**. 등록 ceremony 는 passkey-app 에서 이미 성공·DB 커밋되었으므로 rp-app persist 실패로 사용자 응답을 깨면 안 된다. 다음 confirm 때 자연 재시도. |

## 8. 직렬화 세부

- `RpAppUser` 는 record + `Serializable` 이며 `Instant createdAt` 필드를 가진다.
- `Instant` 직렬화에는 `jackson-datatype-jsr310` 의 `JavaTimeModule` 이 필요하다.
  `spring-boot-starter-web` 이 jsr310 을 transitive 로 포함하므로 신규 의존성은
  불필요하다. 단, store 내부에서 별도 `ObjectMapper` 를 생성한다면 `JavaTimeModule`
  을 **수동 등록**하거나, Boot 가 자동설정한 `ObjectMapper` 빈을 **주입해 재사용**해야
  한다. (구현 단계 선택 — 주입 재사용 권장.)
- record 역직렬화를 위해 파라미터명 보존이 필요할 수 있다. Spring Boot 기본 컴파일
  설정(`-parameters`)이 적용되는지 plan 단계에서 확인.

## 9. 설정

`application.yml` (및 프로필별 yml)에 추가:

```yaml
rp:
  user-store:
    file: ./data/rp-app-users.json   # 기본값. 환경변수/args 로 오버라이드 가능
```

- `.gitignore` 에 `data/` 추가 (런타임 산출물 커밋 방지).
- 주입 방식은 기존 `PasskeyProperties` 의 `@ConfigurationProperties(record)` 패턴을
  따르거나 단순 `@Value`. plan 단계에서 결정(일관성 vs 경량).

## 10. 테스트

| 테스트 | 검증 |
|---|---|
| 영속화 round-trip | `@TempDir` 경로로 store 생성 → createPending+confirmRegistration → **새 store 인스턴스** load → `findByUserHandle`/`findByUsername` 복원됨 |
| pending 격리 | createPending 만 한 뒤 새 store load → 해당 user **없음** (확정만 영속화) |
| 손상 파일 내성 | 깨진 JSON 파일로 load → 예외 없이 빈 store |
| 기존 회귀 | `RpAppSmokeIT` 영향 없음 (테스트는 파일 경로를 임시로 격리) |

## 11. 변경 파일 (예상)

- `rp-app/.../user/InMemoryUserStore.java` — load/persist 추가 (핵심)
- `rp-app/src/main/resources/application.yml` (+ 프로필별) — `rp.user-store.file`
- `.gitignore` — `data/`
- `rp-app/.../config/*` — 설정 바인딩(선택, 방식에 따라)
- `rp-app/src/test/.../user/InMemoryUserStoreTest.java` — 신규 테스트
