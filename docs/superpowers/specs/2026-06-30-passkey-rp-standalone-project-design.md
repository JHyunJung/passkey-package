# passkey_rp 독립 프로젝트 추출 설계

작성일: 2026-06-30

## 1. 배경과 목표

모노레포(`Passkey2`)의 `rp-app`(고객사 참고용 샘플 RP 서버)을 `../passkey_rp/`(현재 `.git`만 있는 빈
저장소)에 **독립 빌드 가능한 standalone Gradle 프로젝트**로 추출한다. SDK(`sdk-java`)는 빌드된
라이브러리(jar) 형태로 포함하고, rp-app·SDK 두 사용자 가이드를 함께 넣는다. 빌드/배포에 불필요한
스크립트·런타임 데이터는 제외한다.

**사용자 확정 결정:**
- SDK 포함 방식 = **빌드된 로컬 jar**(소스 모듈이 아님). rp-app 은 `files(...)` 로 의존.
- rp-app **테스트 코드(src/test) + 테스트 의존성 포함**.
- 루트 공유 리소스는 **빌드/동작에 필요한 것만 이전**.

## 2. 모노레포 의존성 분석 (떼어낼 결합점)

현재 `rp-app/build.gradle.kts` 는 다음에 의존한다:
- `project(":sdk-java")` — 소스 모듈 의존 → **로컬 jar 의존으로 대체**.
- 루트 `allprojects{}` — group/version/repositories.
- 루트 `subprojects{}` — java toolchain 17, Spring Boot BOM import, jackson-annotations 2.21
  override, Lombok(compileOnly+annotationProcessor), test BOM, junit-platform-launcher,
  `jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")`.
- `rootProject.libs.*`(version catalog) — `logstash.logback.encoder`, `webauthn4j.test`.
- BootJar destination = `rootProject/deploy`.

`sdk-java` 의 transitive 의존(jar 에 안 담김 → rp-app 이 명시해야 함): `spring-web`,
`jackson-databind`, `jackson-datatype-jsr310`, `nimbus-jose-jwt`, `slf4j-api` (모두 Spring Boot
3.5 BOM 관리, nimbus 는 9.40).

`.well-known` 자산(`apple-app-site-association`/`assetlinks.json`): `WellKnownController` 가 루트
파일을 읽는 게 아니라 `rp-app.well-known.*` 설정에서 값을 받아 **동적 생성**한다. 따라서 두 파일은
빌드·동작에 불필요 → **제외**.

## 3. 목표 디렉토리 구조

```
passkey_rp/
├── settings.gradle.kts          # rootProject.name="passkey-rp"; include(":rp-app")
├── build.gradle.kts             # 루트: 모노레포 subprojects 설정을 평탄화해 녹임
├── gradlew, gradlew.bat         # 모노레포 루트에서 복사
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties # gradle 8.10
├── lombok.config                # 모노레포 루트에서 복사(@Slf4j/@RequiredArgsConstructor 용)
├── .gitignore                   # build/, .gradle/ 등
├── libs/
│   ├── sdk-java-1.0.0.jar         # SDK 빌드 산출물
│   └── sdk-java-1.0.0-sources.jar # SDK 소스 jar(IDE 탐색용, 선택적 동봉)
├── README.md                    # 최상위: 패키지 개요·빌드·실행·SDK jar 교체법
├── docs/
│   ├── rp-app-guide.md          # rp-app 사용자 가이드(현 rp-app/README.md 복사)
│   └── sdk-java-guide.md        # SDK 사용자 가이드(현 sdk-java/README.md 복사)
└── rp-app/
    ├── build.gradle.kts         # standalone 으로 재작성(jar 의존 + 평탄화)
    └── src/
        ├── main/java/...        # 34개(그대로)
        ├── main/resources/...   # application*.yml 5개 + logback + static + templates
        ├── test/java/...        # 10개(그대로)
        └── test/resources/application-test.yml
```

## 4. 빌드 재구성

### 4-A. `settings.gradle.kts`
```kotlin
rootProject.name = "passkey-rp"
include(":rp-app")
```

### 4-B. 루트 `build.gradle.kts` (모노레포 subprojects 평탄화)
모노레포의 `allprojects{}`+`subprojects{}` 를 단일 모듈용으로 녹인다. version catalog 는 가져가지
않고 버전을 직접 명시한다.
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
        jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
    }
}
```
(`buildscript` classpath 에 `io.spring.dependency-management` 가 필요하므로 plugins 블록에
`apply false` 로 선언 — 위와 같음. testcontainers BOM 은 rp-app 이 안 쓰므로 제외.)

### 4-C. `rp-app/build.gradle.kts` (standalone)
```kotlin
plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    // SDK 를 로컬 jar 로 의존. transitive 가 jar 에 없으므로 SDK 가 api() 로 노출하던
    // 의존을 여기서 명시한다(버전은 Spring Boot BOM / 직접 핀이 관리).
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
    testImplementation("com.webauthn4j:webauthn4j-test:0.31.5.RELEASE")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("rp-app.jar")
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("deploy"))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
}
```

**리스크 — SDK jar 의 sources/transitive:** `files(...)` 의존은 POM 이 없어 transitive 를 안
끌어온다. 그래서 SDK 가 `api()` 로 노출하던 5개를 rp-app 이 직접 선언한다(위). 누락 시
컴파일/런타임 NoClassDefFound 로 드러나므로 빌드·부팅으로 검증한다.

## 5. 파일 이전·제외 규칙

**이전(복사):**
- `rp-app/src/` 전체(main 34 + test 10 + resources).
- `rp-app/build.gradle.kts` → 4-C 로 **재작성**.
- 모노레포 루트 `gradlew`, `gradlew.bat`, `gradle/wrapper/*`, `lombok.config` → passkey_rp 루트.
- `sdk-java` 빌드 산출물 `sdk-java-1.0.0.jar`(+ sources jar) → `passkey_rp/libs/`.
- 현 `rp-app/README.md` → `passkey_rp/docs/rp-app-guide.md`.
- 현 `sdk-java/README.md` → `passkey_rp/docs/sdk-java-guide.md`.

**제외:**
- `rp-app/scripts/bootstrap-rp-app.sh` (사용자 명시 — 불필요 스크립트).
- `rp-app/data/rp-app-users.json` (InMemoryUserStore 런타임 영속 — 데모 데이터). 빈 시작점이
  깔끔하므로 제외. `data/` 디렉토리는 런타임에 자동 생성되거나 README 에서 안내.
- `rp-app/build/` (빌드 산출물).
- 루트 `apple-app-site-association`, `assetlinks.json` (코드가 동적 생성 — 불필요).
- version catalog(`gradle/libs.versions.toml`) — standalone 은 직접 버전 명시(가져가지 않음).

## 6. 문서 (3종)

### 6-A. `docs/rp-app-guide.md`
현 `rp-app/README.md` 를 복사. **단, "SDK 연동" 섹션의 모듈 경로 참조**(`sdk-java/README.md` 상대
링크, `project(":sdk-java")` 언급)가 있으면 standalone 구조(`docs/sdk-java-guide.md`,
`libs/sdk-java-1.0.0.jar`)에 맞게 정정.

### 6-B. `docs/sdk-java-guide.md`
현 `sdk-java/README.md` 를 복사. **§2 설치** 의 모노레포 publish 안내(`./gradlew
:sdk-java:publishToMavenLocal`, `implementation("com.crosscert.passkey:sdk-java:1.0.0")`)를
standalone 의 jar 사용법(`libs/sdk-java-1.0.0.jar` 를 `files(...)` 로 의존, transitive 직접
선언)으로 정정. §10 참조 예제 경로(`rp-app/.../*.java`)는 passkey_rp 기준으로 유지/정정.

### 6-C. `passkey_rp/README.md` (신규)
- 이 패키지가 무엇인지(고객사 참고용 RP 샘플 + SDK 라이브러리).
- 빌드/실행: `./gradlew :rp-app:bootRun` (또는 `bootJar` → `deploy/rp-app.jar`).
- 필수 설정(환경변수: `PASSKEY_BASE_URL`/`PASSKEY_API_KEY`/`PASSKEY_TENANT_ID`/`PASSKEY_ISSUER_BASE`/
  `RP_RELAY_SECRET` 등 — rp-app-guide 로 연결).
- **SDK jar 교체법**: 새 SDK 버전을 받으면 `libs/` 의 jar 를 교체하고 `rp-app/build.gradle.kts`
  의 파일명/버전을 갱신.
- 두 가이드 링크(`docs/rp-app-guide.md`, `docs/sdk-java-guide.md`).
- InMemoryUserStore → 자사 DB 교체 강조(rp-app-guide 로 연결).

## 7. 작업 순서

1. **SDK jar 빌드:** 모노레포에서 `./gradlew :sdk-java:jar :sdk-java:sourcesJar` → 산출물 확보.
2. **디렉토리·파일 복사:** §5 이전 규칙대로 passkey_rp 에 복사(제외 규칙 준수).
3. **빌드 파일 작성:** settings/루트 build/rp-app build 를 §4 대로 작성.
4. **문서 작성:** §6 3종.
5. **검증:** §8.

## 8. 검증 전략

- **독립 빌드 그린:** `cd passkey_rp && ./gradlew :rp-app:compileJava` → 성공(SDK jar transitive
  누락 없음 확인).
- **테스트 그린:** `./gradlew :rp-app:test` → 성공(모노레포에서와 동일 결과). 모노레포 대비 회귀
  없음을 base 와 비교(슬라이스/통합 테스트가 standalone 에서도 통과).
- **부팅 스모크:** 가능하면 `./gradlew :rp-app:bootJar` → `deploy/rp-app.jar` 생성 확인. (실제
  기동은 passkey-app 백엔드 필요 — 환경에 따라 생략 가능, README 에 안내.)
- **제외 확인:** passkey_rp 에 `scripts/`, `data/rp-app-users.json`, `build/`, `.well-known` 자산,
  version catalog 가 없는지 확인.
- **문서 정확성:** sdk-java-guide §2 설치가 jar 사용법으로 바뀌었는지, 모노레포 전용 경로 참조가
  남지 않았는지.

## 9. 비범위 (YAGNI)
- 모노레포(`Passkey2`) 자체 변경 — 없음(추출만, 원본 불변).
- SDK 소스를 passkey_rp 에 모듈로 포함 — 안 함(jar 형태로 확정).
- Maven 빌드 지원 — Gradle 만(원본과 동일).
- CI/CD·Docker·배포 스크립트 작성 — 안 함(불필요 스크립트 제외 취지).
- passkey-app 백엔드 포함 — 안 함(rp-app 은 외부 passkey-app 을 호출하는 클라이언트).
