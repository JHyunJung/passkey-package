# passkey_rp 독립 프로젝트 추출 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 모노레포의 rp-app(샘플 RP 서버)을 `../passkey_rp/`에 독립 빌드 가능한 standalone Gradle 프로젝트로 추출하고, SDK를 로컬 jar로 포함하며 rp-app·SDK 사용자 가이드 2종 + 최상위 README를 넣는다.

**Architecture:** 코드 로직 변경 0 — 파일 복사·빌드 파일 재작성·문서 작성이다. TDD 사이클 대신 각 task는 "복사/작성 → 검증(빌드/grep/diff) → 커밋"으로 구성한다. 모노레포 의존(루트 subprojects 설정, version catalog, project(":sdk-java"))을 standalone으로 평탄화하고 SDK는 빌드된 jar를 files(...)로 의존한다.

**Tech Stack:** Gradle 8.10(Kotlin DSL), Spring Boot 3.5.14, Java 17, Lombok, Markdown.

## Global Constraints

- **작업 대상은 `../passkey_rp/`** (모노레포 루트 `/Users/jhyun/Git/10-work/crosscert/Passkey2` 기준 형제 디렉토리). 현재 `.git`만 존재.
- **모노레포(Passkey2)는 변경 금지** — 추출만, 원본 불변. (단, 이 plan/spec 문서 커밋은 모노레포에 — 별개.)
- **SDK = 로컬 jar**: `passkey_rp/libs/sdk-java-1.0.0.jar`(+ sources jar). rp-app은 `files(...)` 의존.
- **SDK transitive 5개를 rp-app이 직접 선언**(jar에 POM 없음): `spring-web`, `jackson-databind`, `jackson-datatype-jsr310`, `nimbus-jose-jwt:9.40`, `slf4j-api`.
- **버전 핀(직접 명시, version catalog 안 가져감):** Spring Boot 3.5.14, spring-dep-mgmt 1.1.6, Lombok 1.18.34, jackson-annotations 2.21, nimbus-jose-jwt 9.40, logstash-logback-encoder 8.0, janino 3.1.12, webauthn4j-test 0.31.5.RELEASE, Gradle 8.10.
- **group/version:** `com.crosscert.passkey` / `0.0.1-SNAPSHOT`(rp-app). SDK jar는 1.0.0.
- **제외:** `rp-app/scripts/`, `rp-app/data/rp-app-users.json`, `rp-app/build/`, 루트 `apple-app-site-association`·`assetlinks.json`, `gradle/libs.versions.toml`.
- **JVM 인자:** test에 `--add-opens java.base/java.util=ALL-UNNAMED`.
- **BootJar:** `archiveFileName=rp-app.jar`, destination `passkey_rp/deploy/`.

---

### Task 1: SDK jar 빌드 + passkey_rp 골격·wrapper 복사

SDK 산출물을 만들고, passkey_rp의 디렉토리 골격과 빌드에 필요한 공유 파일(wrapper, lombok.config)을 복사한다.

**Files:**
- Create: `passkey_rp/libs/sdk-java-1.0.0.jar`, `passkey_rp/libs/sdk-java-1.0.0-sources.jar`
- Create: `passkey_rp/gradlew`, `passkey_rp/gradlew.bat`, `passkey_rp/gradle/wrapper/gradle-wrapper.jar`, `passkey_rp/gradle/wrapper/gradle-wrapper.properties`
- Create: `passkey_rp/lombok.config`, `passkey_rp/.gitignore`

**Interfaces:**
- Consumes: 모노레포 `sdk-java`.
- Produces: `passkey_rp/libs/sdk-java-1.0.0.jar`(rp-app build가 files()로 참조), wrapper(gradlew 실행 가능), lombok.config.

- [ ] **Step 1: SDK jar + sources jar 빌드 (모노레포에서)**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
./gradlew :sdk-java:jar :sdk-java:sourcesJar
ls -la sdk-java/build/libs/
```
Expected: `sdk-java-1.0.0.jar` 와 `sdk-java-1.0.0-sources.jar` 가 `sdk-java/build/libs/` 에 존재.

- [ ] **Step 2: passkey_rp 골격 디렉토리 + libs 복사**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
mkdir -p ../passkey_rp/libs ../passkey_rp/gradle/wrapper ../passkey_rp/docs ../passkey_rp/rp-app
cp sdk-java/build/libs/sdk-java-1.0.0.jar ../passkey_rp/libs/
cp sdk-java/build/libs/sdk-java-1.0.0-sources.jar ../passkey_rp/libs/
ls -la ../passkey_rp/libs/
```
Expected: 두 jar 가 `../passkey_rp/libs/` 에 복사됨.

- [ ] **Step 3: gradle wrapper + lombok.config 복사**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
cp gradlew gradlew.bat ../passkey_rp/
cp gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties ../passkey_rp/gradle/wrapper/
cp lombok.config ../passkey_rp/
chmod +x ../passkey_rp/gradlew
ls -la ../passkey_rp/gradlew ../passkey_rp/gradle/wrapper/ ../passkey_rp/lombok.config
```
Expected: gradlew(실행권한), wrapper jar+properties, lombok.config 존재.

- [ ] **Step 4: `.gitignore` 작성**

`passkey_rp/.gitignore`:
```gitignore
# Gradle
.gradle/
build/
deploy/

# IDE
.idea/
*.iml
.vscode/

# rp-app 런타임 데이터(InMemoryUserStore 영속 파일)
rp-app/data/

# OS
.DS_Store
```

- [ ] **Step 5: 커밋 (passkey_rp 저장소)**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/../passkey_rp
git add libs/ gradlew gradlew.bat gradle/ lombok.config .gitignore
git commit -m "chore: add SDK jar, gradle wrapper, lombok config, gitignore"
```

---

### Task 2: rp-app 소스·리소스 복사 (제외 규칙 적용)

rp-app의 src 전체(main 34 + test 10 + resources)를 복사한다. scripts/data/build 는 제외한다.

**Files:**
- Create: `passkey_rp/rp-app/src/` (main/java, main/resources, test/java, test/resources 전체)

**Interfaces:**
- Consumes: 모노레포 `rp-app/src`.
- Produces: `passkey_rp/rp-app/src/**` — Task 4의 build가 컴파일할 소스.

- [ ] **Step 1: src 전체 복사 (scripts/data/build 제외)**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
cp -R rp-app/src ../passkey_rp/rp-app/src
```
(`src` 만 복사하므로 `scripts/`, `data/`, `build/` 는 자연히 제외된다.)

- [ ] **Step 2: 복사 무결성 확인**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
echo "main java (기대 34): $(find ../passkey_rp/rp-app/src/main/java -name '*.java' | wc -l)"
echo "test java (기대 10): $(find ../passkey_rp/rp-app/src/test/java -name '*.java' | wc -l)"
echo "resources yml (기대 5): $(find ../passkey_rp/rp-app/src/main/resources -name 'application*.yml' | wc -l)"
ls ../passkey_rp/rp-app/src/main/resources/templates/ ../passkey_rp/rp-app/src/main/resources/static/
test -f ../passkey_rp/rp-app/src/test/resources/application-test.yml && echo "test yml OK"
```
Expected: main 34, test 10, yml 5, templates(4 html)·static·test yml 존재.

- [ ] **Step 3: 제외 대상 부재 확인**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
test ! -d ../passkey_rp/rp-app/scripts && echo "scripts 제외 OK"
test ! -e ../passkey_rp/rp-app/data/rp-app-users.json && echo "data json 제외 OK"
test ! -d ../passkey_rp/rp-app/build && echo "build 제외 OK"
```
Expected: 세 줄 모두 OK.

- [ ] **Step 4: 커밋**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/../passkey_rp
git add rp-app/src/
git commit -m "chore: copy rp-app source (main 34 + test 10 + resources), exclude scripts/data/build"
```

---

### Task 3: standalone 빌드 파일 (settings + 루트 build)

모노레포 루트 `allprojects/subprojects` 설정을 standalone 단일 모듈용으로 평탄화한다.

**Files:**
- Create: `passkey_rp/settings.gradle.kts`
- Create: `passkey_rp/build.gradle.kts`

**Interfaces:**
- Consumes: 없음.
- Produces: 루트 build가 `:rp-app` 에 java toolchain 17, Spring Boot BOM, Lombok, test BOM, jvmArgs 를 적용(Task 4 build가 이 위에서 동작).

- [ ] **Step 1: `settings.gradle.kts` 작성**

`passkey_rp/settings.gradle.kts`:
```kotlin
rootProject.name = "passkey-rp"
include(":rp-app")
```

- [ ] **Step 2: 루트 `build.gradle.kts` 작성**

`passkey_rp/build.gradle.kts`:
```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.5.14" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
}

allprojects {
    group = "com.crosscert.passkey"
    version = "0.0.1-SNAPSHOT"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
    }

    extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports { mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.14") }
        // webauthn4j 가 끌어오는 Jackson 3 계열이 jackson-annotations 2.20+ 를 요구.
        // Boot BOM 의 2.19.x 를 앞으로 override(2.x annotations 는 하위호환).
        dependencies { dependency("com.fasterxml.jackson.core:jackson-annotations:2.21") }
    }

    dependencies {
        "compileOnly"("org.projectlombok:lombok:1.18.34")
        "annotationProcessor"("org.projectlombok:lombok:1.18.34")
        "testCompileOnly"("org.projectlombok:lombok:1.18.34")
        "testAnnotationProcessor"("org.projectlombok:lombok:1.18.34")
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // Java 17 module system: Spring Data 등의 UUID 리플렉션 접근 허용.
        jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
    }
}
```

- [ ] **Step 3: Gradle 구성 평가 가드 (rp-app build 없이 settings만 검증)**

이 시점엔 `rp-app/build.gradle.kts` 가 아직 없어 `:rp-app` 프로젝트가 디렉토리로만 존재한다.
구성 단계가 깨지지 않는지 확인:
```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/../passkey_rp
./gradlew projects 2>&1 | tail -15
```
Expected: `Root project 'passkey-rp'` 와 하위 `+--- Project ':rp-app'` 가 보임. BUILD SUCCESSFUL.
(`:rp-app` build 파일이 없어도 빈 서브프로젝트로 인식된다.)

- [ ] **Step 4: 커밋**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/../passkey_rp
git add settings.gradle.kts build.gradle.kts
git commit -m "build: standalone settings + root build (subprojects 평탄화)"
```

---

### Task 4: rp-app standalone build.gradle.kts + 빌드·테스트 검증

rp-app의 build를 standalone으로 재작성(SDK jar 의존 + transitive 직접 선언)하고, 컴파일·테스트가 그린인지 검증한다. 이 task가 추출의 핵심 — 빌드가 실제로 돌아야 한다.

**Files:**
- Create: `passkey_rp/rp-app/build.gradle.kts`

**Interfaces:**
- Consumes: Task 1의 `libs/sdk-java-1.0.0.jar`, Task 3의 루트 build 설정, Task 2의 src.
- Produces: 빌드 가능한 `:rp-app` 모듈(bootJar → `deploy/rp-app.jar`).

- [ ] **Step 1: `rp-app/build.gradle.kts` 작성**

`passkey_rp/rp-app/build.gradle.kts`:
```kotlin
plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    // SDK 를 로컬 jar 로 의존. jar 에 POM 이 없어 transitive 가 안 따라오므로
    // SDK 가 api() 로 노출하던 의존을 여기서 명시한다(버전은 Boot BOM / 직접 핀).
    implementation(files("$rootDir/libs/sdk-java-1.0.0.jar"))
    implementation("org.springframework:spring-web")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.nimbusds:nimbus-jose-jwt:9.40")
    implementation("org.slf4j:slf4j-api")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.session:spring-session-core")

    implementation("net.logstash.logback:logstash-logback-encoder:8.0")
    runtimeOnly("org.codehaus.janino:janino:3.1.12")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    // RpAppSmokeIT 용 webauthn4j-test (ClientPlatform + PackedAuthenticator).
    testImplementation("com.webauthn4j:webauthn4j-test:0.31.5.RELEASE")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("rp-app.jar")
    // 실행 가능한 jar 를 루트 deploy/ 에 모아 배포 편의를 높인다.
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("deploy"))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // Java 17 UUID reflection access (passkey-app 와 동일 패턴)
    jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
}
```

- [ ] **Step 2: 컴파일 검증 (SDK jar transitive 누락 없는지)**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/../passkey_rp
./gradlew :rp-app:compileJava 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`. (SDK 클래스 `PasskeyClient`/`RegistrationRelayCodec`/`IdTokenClaims` 등이 jar 에서 해석되고, transitive(spring-web 등)도 명시 선언으로 해석됨.) 실패 시 누락된 import 를 보고 의존을 추가.

- [ ] **Step 3: 테스트 검증 (모노레포와 동일 결과)**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/../passkey_rp
./gradlew :rp-app:test 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`. (모노레포에서 :rp-app:test 가 그린이었으므로 standalone 에서도 동일해야 한다. 실패 시 모노레포의 동일 테스트와 비교해 standalone-특이 원인을 찾는다 — 누락 의존/리소스.)

- [ ] **Step 4: bootJar 스모크 (실행 가능 jar 생성)**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/../passkey_rp
./gradlew :rp-app:bootJar 2>&1 | tail -5
ls -la deploy/rp-app.jar
```
Expected: `BUILD SUCCESSFUL`, `deploy/rp-app.jar` 생성.
(실제 기동은 외부 passkey-app 백엔드가 필요하므로 생략 — README 에 안내.)

- [ ] **Step 5: 커밋**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/../passkey_rp
git add rp-app/build.gradle.kts
git commit -m "build: standalone rp-app build (SDK jar 의존 + transitive 명시)"
```

---

### Task 5: 사용자 가이드 2종 (rp-app + SDK) — standalone 맞춤 정정

모노레포 README 2개를 passkey_rp/docs로 복사하고, 모노레포 전용 경로·설치 안내를 standalone 구조에 맞게 정정한다.

**Files:**
- Create: `passkey_rp/docs/rp-app-guide.md` (모노레포 `rp-app/README.md` 기반)
- Create: `passkey_rp/docs/sdk-java-guide.md` (모노레포 `sdk-java/README.md` 기반)

**Interfaces:**
- Consumes: 모노레포 `rp-app/README.md`, `sdk-java/README.md`.
- Produces: passkey_rp 기준으로 정확한 두 가이드.

- [ ] **Step 1: 두 README 복사**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
cp rp-app/README.md ../passkey_rp/docs/rp-app-guide.md
cp sdk-java/README.md ../passkey_rp/docs/sdk-java-guide.md
```

- [ ] **Step 2: `rp-app-guide.md` 의 SDK 링크 정정**

`passkey_rp/docs/rp-app-guide.md` 에서 모노레포 상대 경로 링크를 standalone 으로 바꾼다.

찾을 문자열:
```
SDK(`PasskeyClient`) 자체의 설정·API·ID Token 검증 사용법은 **[sdk-java/README.md](../sdk-java/README.md)** 를 참고한다.
```
바꿀 내용:
```
SDK(`PasskeyClient`) 자체의 설정·API·ID Token 검증 사용법은 **[sdk-java-guide.md](sdk-java-guide.md)** 를 참고한다.
```

- [ ] **Step 3: `sdk-java-guide.md` 의 §2 설치를 jar 사용법으로 정정**

`passkey_rp/docs/sdk-java-guide.md` 의 §2 설치 블록을 standalone(로컬 jar) 안내로 교체.

찾을 문자열(§2 설치 본문):
```
Maven local 또는 사내 레포에 publish 후 좌표로 의존:

```kotlin
// build.gradle.kts
implementation("com.crosscert.passkey:sdk-java:1.0.0")
```

로컬 publish:

```bash
./gradlew :sdk-java:publishToMavenLocal
```
```
바꿀 내용:
```
이 패키지에는 빌드된 SDK 가 `libs/sdk-java-1.0.0.jar` 로 포함되어 있다. RP 모듈에서 로컬 jar 로 의존한다:

```kotlin
// rp-app/build.gradle.kts
dependencies {
    implementation(files("$rootDir/libs/sdk-java-1.0.0.jar"))
    // jar 에는 POM 이 없어 transitive 가 따라오지 않으므로 SDK 의 런타임 의존을 직접 선언한다:
    implementation("org.springframework:spring-web")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.nimbusds:nimbus-jose-jwt:9.40")
    implementation("org.slf4j:slf4j-api")
}
```

새 SDK 버전을 받으면 `libs/` 의 jar 를 교체하고 위 파일명을 갱신한다. IDE 소스 탐색용으로
`libs/sdk-java-1.0.0-sources.jar` 가 함께 들어 있다.
```

- [ ] **Step 4: `sdk-java-guide.md` 의 §10 참조 예제 경로 정정**

찾을 문자열:
```
`rp-app` 모듈이 이 SDK 의 레퍼런스 소비자다:
- `rp-app/.../config/PasskeyClientConfiguration.java` — Spring `@Bean` 으로 `PasskeyClient` 와
  `RegistrationRelayCodec` 구성(동적 API Key Supplier 핫리로드 포함).
- `rp-app/.../web/WebAuthnController.java` — 등록/인증 4종 + 3-인자 `verifyIdToken`(iss/aud) 검증 호출 흐름.
```
바꿀 내용:
```
이 패키지의 `rp-app` 모듈이 SDK 의 레퍼런스 소비자다:
- `rp-app/src/main/java/.../config/PasskeyClientConfiguration.java` — Spring `@Bean` 으로
  `PasskeyClient` 와 `RegistrationRelayCodec` 구성(동적 API Key Supplier 핫리로드 포함).
- `rp-app/src/main/java/.../web/WebAuthnController.java` — 등록/인증 4종 + 3-인자
  `verifyIdToken`(iss/aud) 검증 호출 흐름.
```

- [ ] **Step 5: 모노레포 전용 잔재 스캔**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/../passkey_rp
grep -rn "publishToMavenLocal\|:sdk-java\|../sdk-java/README\|com.crosscert.passkey:sdk-java" docs/
```
Expected: 결과 없음(모노레포 전용 publish/모듈 참조가 가이드에 남지 않음).

- [ ] **Step 6: 커밋**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/../passkey_rp
git add docs/rp-app-guide.md docs/sdk-java-guide.md
git commit -m "docs: rp-app + SDK 사용자 가이드(standalone jar 사용법으로 정정)"
```

---

### Task 6: 최상위 README + 최종 통합 검증

passkey_rp 최상위 README를 작성하고, 전체가 일관·완결인지 최종 검증한다.

**Files:**
- Create: `passkey_rp/README.md`

**Interfaces:**
- Consumes: 모든 이전 task 산출물.
- Produces: 패키지 진입 문서.

- [ ] **Step 1: `passkey_rp/README.md` 작성**

`passkey_rp/README.md`:
```markdown
# Passkey RP 샘플 패키지

passkey-app(패스키 서버)과 연동하는 **RP(Relying Party) 서버 샘플**과, RP가 임베드하는
**Java SDK(라이브러리)**를 함께 담은 독립 패키지다. 고객사는 이 패키지를 출발점으로 자사 RP
서버를 구축한다.

## 구성

| 경로 | 내용 |
|---|---|
| `rp-app/` | 샘플 RP 서버(Spring Boot). 등록/인증 중계·사용자 매핑·well-known 호스팅. |
| `libs/sdk-java-1.0.0.jar` | RP가 임베드하는 SDK 라이브러리(+ `-sources.jar`). |
| `docs/rp-app-guide.md` | rp-app 사용자 가이드(설정·보안·교체 포인트). |
| `docs/sdk-java-guide.md` | SDK 사용자 가이드(API·ID Token 검증·릴레이 코덱). |

## 빌드 / 실행

```bash
# 실행 가능한 jar 생성 → deploy/rp-app.jar
./gradlew :rp-app:bootJar

# 또는 개발 모드 실행
./gradlew :rp-app:bootRun

# 테스트
./gradlew :rp-app:test
```

Java 17 + Gradle wrapper(8.10) 포함. 별도 Gradle 설치 불필요.

## 필수 설정

rp-app 은 외부 passkey-app 백엔드를 호출한다. 다음을 환경변수/yml 로 주입한다(상세는
[docs/rp-app-guide.md](docs/rp-app-guide.md)):

- `PASSKEY_BASE_URL` — passkey-app 베이스 URL
- `PASSKEY_API_KEY` — 발급받은 API Key
- `PASSKEY_TENANT_ID` — 자사 테넌트 ID
- `PASSKEY_ISSUER_BASE` — ID Token iss 검증용 issuer-base(passkey-app 설정과 일치)
- `RP_RELAY_SECRET` — 등록 릴레이 토큰 HMAC 키(운영은 강한 키 필수)

## SDK jar 교체

새 SDK 버전을 받으면:
1. `libs/` 의 `sdk-java-*.jar` 를 교체한다.
2. `rp-app/build.gradle.kts` 의 `files("$rootDir/libs/sdk-java-1.0.0.jar")` 파일명을 갱신한다.

SDK 사용법은 [docs/sdk-java-guide.md](docs/sdk-java-guide.md) 참고.

## 자사 적용 시 교체 포인트

- **사용자 저장소:** `InMemoryUserStore` 는 데모용(인메모리). 자사 DB(JPA/MyBatis 등)로 교체한다.
- **릴레이 secret / API Key:** 운영용 강한 값으로 주입(데모 키는 운영 프로필에서 기동 차단).
- **well-known:** `rp-app.well-known.*` 설정에 자사 앱 메타데이터(Android 지문 / iOS TeamID·BundleID)
  를 채운다.

자세한 내용은 [docs/rp-app-guide.md](docs/rp-app-guide.md) 를 참고한다.
```

- [ ] **Step 2: 전체 빌드·테스트 최종 그린**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/../passkey_rp
./gradlew :rp-app:test :rp-app:bootJar 2>&1 | tail -8
```
Expected: `BUILD SUCCESSFUL`, `deploy/rp-app.jar` 존재.

- [ ] **Step 3: 제외 규칙·구조 최종 확인**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/../passkey_rp
echo "-- 제외 대상 부재 --"
test ! -d rp-app/scripts && echo "scripts 없음 OK"
test ! -e rp-app/data/rp-app-users.json && echo "data json 없음 OK"
test ! -e gradle/libs.versions.toml && echo "version catalog 없음 OK"
test ! -e apple-app-site-association && test ! -e assetlinks.json && echo "well-known 자산 없음 OK"
echo "-- 필수 산출물 존재 --"
test -f libs/sdk-java-1.0.0.jar && echo "SDK jar OK"
test -f docs/rp-app-guide.md && test -f docs/sdk-java-guide.md && echo "가이드 2종 OK"
test -f README.md && echo "최상위 README OK"
```
Expected: 모든 줄 OK.

- [ ] **Step 4: 모노레포 불변 확인**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git status --short
```
Expected: 추출 작업으로 모노레포의 rp-app/sdk-java 소스가 바뀌지 않았으므로, 변경 없음(또는 sdk-java/build/ 산출물 — gitignore 대상). plan/spec 외 추적 변경 없음.

- [ ] **Step 5: 커밋**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/../passkey_rp
git add README.md
git commit -m "docs: 최상위 README(빌드·설정·SDK jar 교체·교체 포인트)"
```

---

## Self-Review

**Spec coverage:**
- §3 디렉토리 구조 → Task 1(골격·libs·wrapper), Task 2(src), Task 3(settings·루트build), Task 4(rp-app build), Task 5(docs), Task 6(README). ✓
- §4-A settings → Task 3 Step 1. ✓
- §4-B 루트 build 평탄화 → Task 3 Step 2. ✓
- §4-C rp-app build(jar 의존+transitive) → Task 4 Step 1. ✓
- §4 리스크(SDK jar transitive) → Task 4 Step 2-3 빌드/테스트 검증. ✓
- §5 이전(src/wrapper/lombok/jar/README) → Task 1·2·5. ✓
- §5 제외(scripts/data/build/well-known/catalog) → Task 2 Step 3 + Task 6 Step 3. ✓
- §6-A rp-app-guide → Task 5 Step 2. ✓
- §6-B sdk-java-guide §2/§10 정정 → Task 5 Step 3·4. ✓
- §6-C 최상위 README → Task 6 Step 1. ✓
- §7 작업 순서 → Task 1~6 순서. ✓
- §8 검증(독립 빌드·테스트·bootJar·제외·문서) → Task 4 Step 2-4, Task 5 Step 5, Task 6 Step 2-3. ✓
- §9 비범위(모노레포 불변·SDK 모듈 아님·Maven 미지원·CI 없음) → Task 6 Step 4 + plan 미포함. ✓

**Placeholder scan:** 모든 step 에 실제 명령·완전한 파일 내용 제공. "TBD"/"적절히"/"유사" 없음. Task 4 Step 2의 "실패 시 누락 의존 추가"는 placeholder 가 아니라 검증 분기(예상 결과는 BUILD SUCCESSFUL 명시).

**Type/이름 consistency:** SDK jar 파일명 `sdk-java-1.0.0.jar` 가 Task 1(복사)·Task 4(files 의존)·Task 5(가이드)·Task 6(README) 전반 일치. transitive 5개 좌표가 Task 4 build 와 Task 5 가이드에서 동일. 버전 핀(Boot 3.5.14, Lombok 1.18.34, jackson-annotations 2.21, nimbus 9.40, webauthn4j-test 0.31.5.RELEASE)이 Global Constraints·Task 3·Task 4 에서 일관. `rootProject.name="passkey-rp"`·`include(":rp-app")` 가 settings·검증 명령과 일치. ✓
