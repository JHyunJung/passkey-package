# rp-app 사용자 매핑 JSON 파일 영속화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** rp-app 재기동 후에도 확정 등록된 user↔handle↔credential 매핑이 유지되어 패스키 인증이 계속 성공하도록 `InMemoryUserStore` 를 JSON 파일에 영속화한다.

**Architecture:** `InMemoryUserStore` 의 두 `ConcurrentHashMap` 은 in-memory 캐시로 유지하되, credentialId 가 채워진 **확정 user 만** JSON 파일에 미러링한다. 기동 시 `@PostConstruct` 로 파일을 로드하고, `confirmRegistration` 성공 시 원자적으로 파일을 다시 쓴다. 컨트롤러/DTO/SDK/세션은 일절 건드리지 않는다.

**Tech Stack:** Java 17, Spring Boot 3.x, Jackson (`spring-boot-starter-web` transitive, `JavaTimeModule` 포함), JUnit 5 (`@TempDir`).

---

## File Structure

| 파일 | 책임 | 변경 |
|---|---|---|
| `rp-app/.../user/RpAppUser.java` | 영속 모델 + Jackson 바인딩 애너테이션 | Modify |
| `rp-app/.../user/InMemoryUserStore.java` | 캐시 + load/persist (핵심) | Modify |
| `rp-app/src/main/resources/application.yml` | `rp-app.user-store.file` 기본값 | Modify |
| `.gitignore` | `data/` 런타임 산출물 제외 | Modify |
| `rp-app/src/test/.../user/InMemoryUserStoreTest.java` | round-trip / pending 격리 / 손상내성 테스트 | Create |

### 설계 노트 (구현 결정 — spec 의 열린 항목 확정)

- **Jackson record 바인딩**: 루트 `build.gradle.kts` 에 `-parameters` 컴파일 플래그가
  없다. 전역 빌드 변경을 피하기 위해 `RpAppUser` record 컴포넌트에 **`@JsonProperty`**
  를 명시한다. 이러면 컴파일러 파라미터명 보존 여부와 무관하게 역직렬화가 결정적으로
  동작한다.
- **ObjectMapper**: store 내부에서 자체 생성하지 않고 **Boot 자동설정 `ObjectMapper`
  빈을 주입**해 재사용한다(`JavaTimeModule` 이미 등록됨 → `Instant` 안전).
- **설정 주입**: 신규 `@ConfigurationProperties` 클래스를 만들지 않고 store 생성자에
  `@Value("${rp-app.user-store.file:./data/rp-app-users.json}")` 로 경로를 주입한다
  (경량, 단일 속성). yml prefix 는 기존 `rp-app:` 트리에 합류시킨다.

---

## Task 1: RpAppUser 에 Jackson 바인딩 애너테이션 추가

**Files:**
- Modify: `rp-app/src/main/java/com/crosscert/passkey/rpapp/user/RpAppUser.java`

레코드 역직렬화를 컴파일러 `-parameters` 플래그에 의존하지 않도록 각 컴포넌트에
`@JsonProperty` 를 단다. 동작 변경은 없고 직렬화 안정성만 확보한다. 이 task 는 다음
task 의 테스트가 의존하므로 먼저 둔다.

- [ ] **Step 1: RpAppUser 수정**

```java
package com.crosscert.passkey.rpapp.user;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.Instant;

public record RpAppUser(
        @JsonProperty("userHandle")  String userHandle,
        @JsonProperty("username")    String username,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("createdAt")   Instant createdAt,
        @JsonProperty("credentialId") String credentialId   // confirmRegistration 후 채워짐. 없으면 null (pending).
) implements Serializable {}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :rp-app:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/user/RpAppUser.java
git commit -m "refactor(rp-app): RpAppUser 에 @JsonProperty 바인딩 명시"
```

---

## Task 2: InMemoryUserStore 영속화 — round-trip 테스트부터 (TDD)

**Files:**
- Test: `rp-app/src/test/java/com/crosscert/passkey/rpapp/user/InMemoryUserStoreTest.java`
- Modify: `rp-app/src/main/java/com/crosscert/passkey/rpapp/user/InMemoryUserStore.java`

핵심 동작: 확정 user 가 파일에 저장되고, 새 store 인스턴스가 같은 파일에서 복원한다.

- [ ] **Step 1: 실패하는 round-trip 테스트 작성**

```java
package com.crosscert.passkey.rpapp.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryUserStoreTest {

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void confirmedUserSurvivesNewStoreInstance(@TempDir Path dir) {
        Path file = dir.resolve("users.json");

        InMemoryUserStore first = new InMemoryUserStore(mapper(), file.toString());
        String handle = first.createPending("alice", "Alice");
        first.confirmRegistration(handle, "cred-123");

        // 새 인스턴스가 같은 파일에서 복원
        InMemoryUserStore second = new InMemoryUserStore(mapper(), file.toString());

        Optional<RpAppUser> byHandle = second.findByUserHandle(handle);
        assertThat(byHandle).isPresent();
        assertThat(byHandle.get().username()).isEqualTo("alice");
        assertThat(byHandle.get().displayName()).isEqualTo("Alice");
        assertThat(byHandle.get().credentialId()).isEqualTo("cred-123");
        assertThat(byHandle.get().createdAt()).isNotNull();

        assertThat(second.findByUsername("alice")).isPresent();
        assertThat(second.findByUsername("alice").get().userHandle()).isEqualTo(handle);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :rp-app:test --tests InMemoryUserStoreTest`
Expected: FAIL — 컴파일 에러 (생성자 `InMemoryUserStore(ObjectMapper, String)` 없음)

- [ ] **Step 3: InMemoryUserStore 에 생성자 + load/persist 구현**

`InMemoryUserStore.java` 전체를 아래로 교체한다. `@PostConstruct` 대신 생성자에서
직접 `load()` 를 호출해 테스트(스프링 컨텍스트 없이 `new`)와 런타임 양쪽에서 동일하게
동작하게 한다.

```java
package com.crosscert.passkey.rpapp.user;

import com.crosscert.passkey.rpapp.common.exception.BusinessException;
import com.crosscert.passkey.rpapp.common.exception.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * username ↔ userHandle ↔ credential 매핑 저장소.
 *
 * 맵은 in-memory 캐시이고, 확정 등록된 user(credentialId ≠ null)만 JSON 파일에
 * 미러링한다. pending user 는 메모리에만 두어 재기동 시 자연 정리된다. 단일 인스턴스
 * 데모를 가정하며 파일 락은 두지 않는다.
 */
@Component
public class InMemoryUserStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryUserStore.class);

    private final ConcurrentMap<String, RpAppUser> byHandle   = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String>    byUsername = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();

    private final ObjectMapper mapper;
    private final Path file;

    public InMemoryUserStore(ObjectMapper mapper,
                             @Value("${rp-app.user-store.file:./data/rp-app-users.json}") String file) {
        this.mapper = mapper;
        this.file   = Path.of(file);
        load();
    }

    /** 기동 시 파일에서 확정 user 복원. 파일이 없거나 손상되면 빈 상태로 시작(크래시 금지). */
    private void load() {
        if (!Files.exists(file)) {
            log.info("user-store: no persisted file at {} — starting empty", file);
            return;
        }
        try {
            List<RpAppUser> users = mapper.readValue(file.toFile(), new TypeReference<List<RpAppUser>>() {});
            for (RpAppUser u : users) {
                if (u.credentialId() == null) continue;   // 방어: 확정만 신뢰
                byHandle.put(u.userHandle(), u);
                byUsername.put(u.username(), u.userHandle());
            }
            log.info("user-store: loaded {} confirmed user(s) from {}", byHandle.size(), file);
        } catch (IOException e) {
            log.warn("user-store: failed to read {} — starting empty. cause={}", file, e.toString());
        }
    }

    /** 확정 user(credentialId ≠ null)만 골라 원자적으로 파일에 쓴다. 실패해도 예외 비전파. */
    private synchronized void persist() {
        List<RpAppUser> confirmed = byHandle.values().stream()
                .filter(u -> u.credentialId() != null)
                .toList();
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), confirmed);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("user-store: failed to persist {} user(s) to {} — cause={}",
                    confirmed.size(), file, e.toString());
        }
    }

    /** username 으로 새 userHandle (32B base64url) 발급 + pending 상태로 저장. 중복이면 USERNAME_TAKEN. */
    public String createPending(String username, String displayName) {
        byte[] raw = new byte[32];
        rng.nextBytes(raw);
        String userHandle = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        // putIfAbsent 로 username 을 원자적으로 예약. 동시 호출 시 두 번째는 non-null 반환 → 거부.
        if (byUsername.putIfAbsent(username, userHandle) != null) {
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }
        RpAppUser user = new RpAppUser(userHandle, username, displayName, Instant.now(), null);
        byHandle.put(userHandle, user);
        // pending 은 영속화하지 않음 — 재기동 시 자연 정리.
        return userHandle;
    }

    /** registration/finish 성공 후 credentialId 채워서 확정 + 파일에 영속화. */
    public void confirmRegistration(String userHandle, String credentialId) {
        byHandle.computeIfPresent(userHandle, (k, u) ->
                new RpAppUser(u.userHandle(), u.username(), u.displayName(), u.createdAt(), credentialId));
        persist();
    }

    public Optional<RpAppUser> findByUserHandle(String userHandle) {
        return Optional.ofNullable(byHandle.get(userHandle));
    }

    public Optional<RpAppUser> findByUsername(String username) {
        String handle = byUsername.get(username);
        return handle == null ? Optional.empty() : findByUserHandle(handle);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :rp-app:test --tests InMemoryUserStoreTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add rp-app/src/main/java/com/crosscert/passkey/rpapp/user/InMemoryUserStore.java \
        rp-app/src/test/java/com/crosscert/passkey/rpapp/user/InMemoryUserStoreTest.java
git commit -m "feat(rp-app): InMemoryUserStore 확정 user JSON 파일 영속화"
```

---

## Task 3: pending 격리 테스트 (확정만 영속화 검증)

**Files:**
- Modify: `rp-app/src/test/java/com/crosscert/passkey/rpapp/user/InMemoryUserStoreTest.java`

`createPending` 만 한 user 는 파일에 남지 않아야 한다 (재기동 시 username 예약 자연 해소).

- [ ] **Step 1: 테스트 추가**

기존 `InMemoryUserStoreTest` 클래스 안에 메서드 추가:

```java
    @Test
    void pendingUserIsNotPersisted(@TempDir Path dir) {
        Path file = dir.resolve("users.json");

        InMemoryUserStore first = new InMemoryUserStore(mapper(), file.toString());
        String handle = first.createPending("bob", "Bob");   // confirm 하지 않음

        InMemoryUserStore second = new InMemoryUserStore(mapper(), file.toString());

        assertThat(second.findByUserHandle(handle)).isEmpty();
        assertThat(second.findByUsername("bob")).isEmpty();
    }
```

- [ ] **Step 2: 테스트 실행 (구현 이미 충족 → 바로 통과)**

Run: `./gradlew :rp-app:test --tests InMemoryUserStoreTest`
Expected: PASS (createPending 은 persist 를 호출하지 않으므로 파일이 생성조차 안 됨)

- [ ] **Step 3: Commit**

```bash
git add rp-app/src/test/java/com/crosscert/passkey/rpapp/user/InMemoryUserStoreTest.java
git commit -m "test(rp-app): pending user 비영속화 검증"
```

---

## Task 4: 손상 파일 내성 테스트

**Files:**
- Modify: `rp-app/src/test/java/com/crosscert/passkey/rpapp/user/InMemoryUserStoreTest.java`

깨진 JSON 파일이 있어도 기동을 크래시하지 않고 빈 store 로 시작해야 한다.

- [ ] **Step 1: 테스트 추가**

`java.nio.file.Files` import 를 테스트 상단에 추가하고, 클래스 안에 메서드 추가:

```java
    @Test
    void corruptFileYieldsEmptyStoreWithoutCrash(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("users.json");
        java.nio.file.Files.writeString(file, "{ this is not valid json ][");

        InMemoryUserStore store = new InMemoryUserStore(mapper(), file.toString());

        assertThat(store.findByUsername("anyone")).isEmpty();
    }
```

- [ ] **Step 2: 테스트 실행**

Run: `./gradlew :rp-app:test --tests InMemoryUserStoreTest`
Expected: PASS (load 의 catch 가 WARN 후 빈 상태 유지)

- [ ] **Step 3: Commit**

```bash
git add rp-app/src/test/java/com/crosscert/passkey/rpapp/user/InMemoryUserStoreTest.java
git commit -m "test(rp-app): 손상 JSON 파일 기동 내성 검증"
```

---

## Task 5: 설정 기본값 + gitignore

**Files:**
- Modify: `rp-app/src/main/resources/application.yml`
- Modify: `.gitignore`

런타임 기본 경로를 명시하고, 산출물 디렉토리를 git 에서 제외한다.

- [ ] **Step 1: application.yml 의 `rp-app:` 트리에 user-store 추가**

`rp-app:` 블록의 `origin:` 다음 줄에 추가 (well-known 위/아래 어디든 같은 들여쓰기):

```yaml
rp-app:
  origin: ${RP_APP_ORIGIN:http://localhost:9090}
  # 재기동 후에도 확정 등록된 user↔handle↔credential 매핑을 유지하기 위한 JSON 파일.
  # 단일 인스턴스 데모용. 환경변수 RP_APP_USER_STORE_FILE 로 오버라이드 가능.
  user-store:
    file: ${RP_APP_USER_STORE_FILE:./data/rp-app-users.json}
```

> 주의: 기존 `well-known:` 블록은 그대로 둔다. 위 `user-store:` 를 같은 `rp-app:`
> 부모 아래 형제로 추가하기만 하면 된다.

- [ ] **Step 2: .gitignore 에 data/ 추가**

`.gitignore` 끝에 추가:

```gitignore

# rp-app 런타임 user-store 산출물
data/
```

- [ ] **Step 3: 바인딩 확인 — rp-app 컴파일 + 기존 테스트**

Run: `./gradlew :rp-app:test`
Expected: BUILD SUCCESSFUL (모든 테스트 통과, `RpAppSmokeIT` 는 `@Disabled`)

- [ ] **Step 4: Commit**

```bash
git add rp-app/src/main/resources/application.yml .gitignore
git commit -m "config(rp-app): user-store 파일 경로 기본값 + data/ gitignore"
```

---

## Task 6: 전체 빌드 검증

**Files:** (없음 — 검증 전용)

- [ ] **Step 1: rp-app 모듈 전체 빌드**

Run: `./gradlew :rp-app:build`
Expected: BUILD SUCCESSFUL — 컴파일 + 테스트 + bootJar(`deploy/rp-app.jar`)

- [ ] **Step 2: 수동 스모크 (선택, 인프라 가동 시)**

3 서버를 README 절차로 띄운 뒤:
1. rp-app 으로 패스키 등록 + 인증 1회 성공 확인
2. `data/rp-app-users.json` 에 user 1건이 기록되었는지 확인 (`credentialId` 포함)
3. rp-app 만 재기동 (`Ctrl-C` 후 `./gradlew :rp-app:bootRun` 재실행)
4. 같은 패스키로 인증 재시도 → **성공** 확인 (이전엔 `unknown sub` 로 실패하던 시나리오)

> 인프라 미가동이면 Step 1 만으로 충분. Step 2 는 end-to-end 확인용.

---

## Self-Review 체크

- **Spec coverage**: §5.1 load(Task2), §5.2 createPending 비영속(Task2/3), §5.3
  confirmRegistration persist(Task2), §5.4 원자적 쓰기 ATOMIC_MOVE(Task2), §6 재기동
  흐름(Task6 Step2), §7 읽기/쓰기 실패 비전파(Task2 load/persist catch + Task4 테스트),
  §8 직렬화 JavaTimeModule+@JsonProperty(Task1/2), §9 설정+gitignore(Task5),
  §10 테스트 3종(Task2/3/4) — 전 항목 매핑됨.
- **Placeholder 스캔**: 모든 코드 step 에 실제 코드 포함. TBD/TODO 없음.
- **Type 일관성**: 생성자 `InMemoryUserStore(ObjectMapper, String)` 가 테스트(Task2/3/4)
  와 구현(Task2)에서 동일. `RpAppUser` 컴포넌트명(userHandle/username/displayName/
  createdAt/credentialId)이 Task1 정의와 이후 사용처 일치.
