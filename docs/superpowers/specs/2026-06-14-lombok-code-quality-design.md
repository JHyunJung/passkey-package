# Lombok 도입을 통한 코드 품질 개선 설계

- 날짜: 2026-06-14
- 상태: 설계 승인 완료, 구현 계획 대기
- 절대 제약: **기존 런타임 기능에 변경이 있으면 안 된다.** 바이트코드 수준에서 동등성을 증명한다.

## 1. 배경 & 목표

프로젝트는 Spring Boot 3.5 / Java 17 멀티모듈(core, webauthn, admin-app, passkey-app, rp-app, sdk-java)이며 현재 Lombok을 전혀 사용하지 않는다(그린필드 도입).

탐색 결과:
- `record`가 이미 102~109개 광범위 사용 → DTO / value object 영역은 record가 커버하므로 손대지 않는다.
- Logger 선언이 **49개 파일에서 100% 일관**: 예외 없이
  `private static final Logger log = LoggerFactory.getLogger(본인클래스.class);`
- 순수 의존성 주입(생성자 본문이 `this.x = x` 할당만)을 하는 Spring 컴포넌트 약 55개.
- 빌드 강제 도구(checkstyle/spotless/.editorconfig) 없음.

목표는 **보일러플레이트 2종만** 제거하여 가독성을 높이되, 동작은 불변으로 유지하는 것.

## 2. 범위 (확정)

### 적용 대상

| 어노테이션 | 대상 | 규모 | 안전성 근거 |
|---|---|---|---|
| `@Slf4j` | `private static final Logger log = LoggerFactory.getLogger(본인.class)` 패턴 | 49개 파일 | 변수명 `log` 고정, 인자 전부 `본인클래스.class` 임을 grep으로 검증 완료 → Lombok 생성 결과와 정확히 일치 |
| `@RequiredArgsConstructor` | 생성자가 순수 `this.field = param` 할당만 하는 클래스 | 약 55개 | 아래 5개 안전 판정 기준을 모두 만족할 때만 |

### 명시적 제외 (동작 변경 위험)

- **복잡한 생성자**: 메트릭 카운터 초기화/검증 등 주입 외 로직이 있는 클래스(예: `ApiKeyAuthFilter`) → **손대지 않는다.**
- **JPA Entity `@Getter`/`@Setter`** → 이번 범위 밖.
- **`@Builder`, `@Data`, `@EqualsAndHashCode`, `@ToString`** → equals/hashCode/toString 자동생성은 JPA 프록시·양방향 관계·컬렉션·기존 테스트 assert와 충돌 위험 → **금지.**
- record로 이미 커버되는 102개 → 그대로 유지.

### 핵심 불변식

> Lombok은 컴파일타임 애너테이션 프로세서다. 변경 전후 `.class` 바이트코드가 (`@lombok.Generated` 부착을 제외하고) 동일하면 런타임 동작은 정의상 동일하다.

## 3. 빌드 구성

### 3.1 버전 카탈로그 (`gradle/libs.versions.toml`)

현재 Lombok 미정의. 추가:

```toml
[versions]
lombok = "1.18.34"   # Java 17 + Gradle 8.10 호환 안정 버전

[libraries]
lombok = { module = "org.projectlombok:lombok", version.ref = "lombok" }
```

### 3.2 루트 `build.gradle.kts`의 `subprojects` 일괄 적용

모든 모듈이 동일하게 쓰므로 루트에서 한 번만 선언(모듈별 중복 방지):

```kotlin
subprojects {
    // ... 기존 설정 ...
    dependencies {
        "compileOnly"(rootProject.libs.lombok)
        "annotationProcessor"(rootProject.libs.lombok)
        "testCompileOnly"(rootProject.libs.lombok)
        "testAnnotationProcessor"(rootProject.libs.lombok)
        // ... 기존 test 의존성 유지 ...
    }
}
```

`compileOnly` + `annotationProcessor` 조합이 핵심: Lombok은 컴파일타임에만 필요하고 런타임 JAR에 포함되지 않는다. → **배포 산출물(production classpath)에 Lombok 0** → 런타임 의존성 변화 0.

### 3.3 `lombok.config` (루트에 1개, 하위 모듈 상속)

```properties
# 루트 lombok.config — config.stopBubbling로 하위 전체 상속 종결점
config.stopBubbling = true
# @Slf4j 변수명을 기존 코드와 동일하게 'log'로 고정 (기본값이 log이지만 명시)
lombok.log.fieldName = log
# Lombok 생성 멤버에 @lombok.Generated 부착 → 커버리지 도구가 제외 인식 + 검증 시 식별 가능
lombok.addLombokGeneratedAnnotation = true
```

### 3.4 IDE

사용자는 IntelliJ 사용(`.idea` 존재). IntelliJ 2020.3+ 는 Lombok 플러그인 내장이라 추가 설정 불필요. 인식 안 되면 "Enable annotation processing" 확인.

## 4. 실행 전략

### 4.1 `@RequiredArgsConstructor` 안전 판정 기준 (5개 모두 충족 시에만 적용)

1. 생성자가 **정확히 1개**.
2. 생성자 본문이 **`this.field = param;` 할당문만** (다른 로직 0).
3. 주입되는 모든 필드가 **`final`**.
4. **생성자 파라미터 순서 = 필드 선언 순서** (Lombok은 필드 선언 순서로 생성자 생성 → 어긋나면 바이트코드 차이).
5. 파라미터에 `@Autowired`/`@Qualifier`/`@Value` 등 어노테이션 **없음** (있으면 보존 위해 수동 유지).

하나라도 어긋나면 그 클래스는 제외하고 그대로 둔다. **애매하면 건드리지 않는다.**

### 4.2 Phase 분할 (per-phase worktree)

| Phase | 내용 | 검증 |
|---|---|---|
| Phase 0 | 빌드 구성 전체(3.1~3.3). 코드 변경 0. | 전체 모듈 `compileJava` 성공 |
| Phase 1 | `@Slf4j` — 49개 파일. Logger 필드 선언 제거 + import 정리 + `@Slf4j` 부착. 모듈별 1커밋. | 바이트코드 diff + 모듈 테스트 |
| Phase 2 | `@RequiredArgsConstructor` — 4.1 기준 통과 클래스만. 모듈 순서: core → webauthn → admin-app / passkey-app / rp-app / sdk-java. 모듈별 1커밋. | 바이트코드 diff + 모듈 테스트 |

Phase 1을 먼저 하는 이유: `@Slf4j`는 위험이 사실상 0이라 빌드→검증 파이프라인 자체를 먼저 검증하는 용도.

모듈 순서가 core(최하단)부터인 이유: 하위 모듈부터 해야 상위 모듈 컴파일이 안정적.

### 4.3 커밋 입도 & 안전망

- Phase 1/2 모두 모듈별 1커밋.
- 각 커밋 전 **바이트코드 diff 통과 + 해당 모듈 테스트 green** 필수.
- 커밋 전 **`/codex:review`** (staged diff 독립 리뷰) — 바이트코드 검증과 별개의 의미론적 안전망.

## 5. 바이트코드 동등성 검증 방법론

### 5.1 절차 (Phase별)

```
1. baseline: ./gradlew :<module>:compileJava → build/classes/.../*.class 별도 백업
2. Lombok 적용
3. after: 동일 compileJava
4. 각 .class를 javap -p -c -constants 로 덤프, before/after 비교
```

### 5.2 예상되는 정상 차이 (false alarm 방지)

1. **`@Slf4j`**: `private static final org.slf4j.Logger log` 필드 + `getLogger(X.class)` 초기화 → 동일. 단 `lombok.addLombokGeneratedAnnotation=true`로 `@lombok.Generated` 1개 추가.
2. **`@RequiredArgsConstructor`**: 생성자 바디 바이트코드 동일, 생성자에 `@lombok.Generated` 부착.

→ 검증 기준: **`@lombok.Generated` 애너테이션 추가를 제외하면 메서드/필드 바이트코드 명령어 시퀀스가 완전 동일.** `@lombok.Generated`는 RetentionPolicy.CLASS라 런타임 무관.

### 5.3 검증 스크립트

`scripts/verify-lombok-bytecode.sh <module>`:
- before/after `.class`를 javap로 덤프.
- `lombok.Generated` 라인 및 소스파일 메타(`Compiled from`) 필터링 후 diff.
- diff 비면 PASS, 있으면 해당 클래스 출력 후 FAIL.

### 5.4 이중 안전망

| 레이어 | 잡는 것 |
|---|---|
| 바이트코드 diff | 명령어 수준 동작 변화(생성자 순서 실수, log 변수명 차이 등) |
| 모듈 테스트 green | 통합 동작 |
| `/codex:review` | 의미론적 회귀, 놓친 케이스 |

### 5.5 전체 build 함정 주의

`./gradlew build`는 SliceConfig 충돌 + Oracle 컨테이너 경합으로 **pre-existing 빨강**. 따라서:
- 머지 게이트로 전체 build를 쓰지 않는다.
- **모듈별 `compileJava` + 모듈별 `test`** 로 한정.
- 빨강이 나오면 base worktree와 비교해 pre-existing/신규를 확정.

## 6. 예상 효과

- `@Slf4j`: 49개 파일 × 약 3줄 = ~147줄 제거.
- `@RequiredArgsConstructor`: 약 55개 클래스 × 평균 ~15줄 = 약 800줄+ 제거.
- 유지보수: 필드 추가/삭제 시 생성자 자동 갱신 → 누락 방지.
- 런타임 동작: **불변(바이트코드 동등성으로 증명).**
- 도입 비용: 매우 낮음(기존 스타일이 이미 Lombok 컨벤션과 일치).

## 7. 비범위 (Non-goals)

- JPA Entity의 getter/setter Lombok화.
- `@Builder` / `@Data` 도입.
- record로 이미 커버되는 영역의 변경.
- 복잡한 생성자(주입 외 로직 포함)의 변경.
- checkstyle/spotless 등 포매팅 강제 도구 도입.
