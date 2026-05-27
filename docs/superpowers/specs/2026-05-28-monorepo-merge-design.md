# monorepo 통합 — sdk-java + sample-rp 흡수 설계 문서

작성일: 2026-05-28
상태: 검토 대기
선행: dev profile phase (`9bf1f03` 머지) 완료
관련: 앱 개발자가 단일 저장소에서 패스키 등록 / 인증 테스트 가능하도록 인프라 통합

## 1. 배경 / DoD

### 1.1 배경

현재 `~/Git/10-work/crosscert/` 에 3개 분리 repo:

```
Passkey2/    (core + passkey-app + admin-app + admin-ui)
sdk-java/    (passkey-sdk-java java-library — 단일 "Initial commit" + remote 없음)
sample-rp/   (Spring Boot 데모 RP — 단일 "Initial commit" + remote 없음)
```

sample-rp 는 sdk-java 를 `mavenLocal()` 의존 → SDK 변경 시 매번 `:sdk-java:publishToMavenLocal` 필요. 두 외부 repo 모두 commit history 가치 거의 없음 ("extracted from Passkey2 examples/..." 한 줄). 강결합 + 단일 release cadence + 데모용이므로 monorepo 가 자연스러움.

### 1.2 Definition of Done

1. **이동**: `sdk-java/` + `sample-rp/` (.git 제외) → `Passkey2/sdk-java/` + `Passkey2/sample-rp/` 복사 (rsync `.git/`, `build/`, `.gradle/` 제외)
2. **Gradle 통합**: `settings.gradle.kts` 에 `include(":sdk-java", ":sample-rp")` 추가
3. **sdk-java build.gradle.kts 단순화**: 자체 wrapper / settings 제거. plugins 의 `java-library` + `maven-publish` 유지. 의존 버전은 root catalog + Spring Boot BOM 으로 위임 (nimbus-jose-jwt 는 catalog alias 사용)
4. **sample-rp build.gradle.kts 갱신**:
   - `mavenLocal()` repository 제거
   - `implementation("com.crosscert.passkey:passkey-sdk-java:0.1.0-SNAPSHOT")` → `implementation(project(":sdk-java"))`
   - Spring Boot version `3.5.0` 명시 → root catalog 의 `3.5.14` 자동 적용
   - webauthn4j-test 명시 → catalog alias 사용
   - `group` / `version` / `java { toolchain }` 제거 → root `allprojects` / `subprojects` 가 처리
5. **테스트**: `:sdk-java:test` (WireMockContractIT) + `:sample-rp:test` (SampleRpSmokeIT) + 기존 IT 모두 통과
6. **dev profile 통합**: `docs/dev-setup.md` (현재 uncommitted) 의 sample-rp 단락을 monorepo 경로로 갱신, `:sdk-java:publishToMavenLocal` 단계 제거
7. **옛 외부 디렉터리 처리**: `/tmp/sdk-java-pre-monorepo-YYYYMMDD-HHMMSS.tar.gz` + `/tmp/sample-rp-pre-monorepo-YYYYMMDD-HHMMSS.tar.gz` 백업 → 사용자 확인 후 `rm -rf`
8. **Codex review** 후 main merge

### 1.3 의도적 제외

| 항목 | 미루는 이유 |
|---|---|
| sdk-java GitHub publish 자동화 (Maven Central 등) | 아직 외부 publish 필요 없음. `maven-publish` plugin 만 보존 |
| sample-rp Dockerfile / Helm chart | 데모용. local bootRun 만 |
| sdk-java multi-language SDK (Python/Node) | 별도 phase |
| 옛 sample-rp / sdk-java 의 single "Initial commit" history 보존 | 가치 거의 없음 (extracted from Passkey2 라는 정보만) |

## 2. 파일 이동 + Gradle 통합

### 2.1 파일 복사 (.git / build / .gradle 제외)

```bash
rsync -av --exclude='.git' --exclude='build' --exclude='.gradle' \
  /Users/jhyun/Git/10-work/crosscert/sdk-java/ \
  /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/monorepo-merge/sdk-java/

rsync -av --exclude='.git' --exclude='build' --exclude='.gradle' \
  /Users/jhyun/Git/10-work/crosscert/sample-rp/ \
  /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/monorepo-merge/sample-rp/
```

`gradlew`, `gradlew.bat`, `gradle/wrapper/` 도 복사되지만 monorepo root wrapper 를 사용하므로 §2.3 + §2.4 에서 명시 삭제.

### 2.2 settings.gradle.kts 갱신

Before:
```kotlin
rootProject.name = "passkey2"
include(":core", ":passkey-app", ":admin-app")
```

After:
```kotlin
rootProject.name = "passkey2"
include(":core", ":passkey-app", ":admin-app", ":sdk-java", ":sample-rp")
```

(admin-ui 는 Node 프로젝트라 Gradle subproject 가 아님 — `include` 에 없음.)

### 2.3 sdk-java 정리

**옛 standalone artifact 제거**:
- `sdk-java/settings.gradle.kts` 삭제 (root 가 관리)
- `sdk-java/gradlew`, `sdk-java/gradlew.bat`, `sdk-java/gradle/wrapper/` 삭제
- `sdk-java/gradle.properties` 검토 — 단순 `org.gradle.jvmargs` 면 root 와 중복이라 삭제

**`sdk-java/build.gradle.kts` 단순화**:

```kotlin
plugins {
    `java-library`
    `maven-publish`
}

java {
    withSourcesJar()
    // toolchain 17 은 root subprojects 가 강제
}

dependencies {
    // Spring Boot 3.5 BOM (root subprojects 에서 import) 이 spring-web 6.2.x,
    // jackson, slf4j 를 모두 관리. 명시 version 제거.
    api("org.springframework:spring-web")
    api("com.fasterxml.jackson.core:jackson-databind")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    api(libs.nimbus.jose.jwt)
    api("org.slf4j:slf4j-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation(libs.wiremock.standalone)
    // junit-platform-launcher 는 root subprojects 가 모든 모듈에 testRuntimeOnly
}

tasks.named<Test>("test") { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("maven") { from(components["java"]) }
    }
}
```

`group` 과 `version` 은 root `allprojects { group = "com.crosscert.passkey"; version = "0.0.1-SNAPSHOT" }` 가 처리. sdk-java 의 옛 version `0.1.0-SNAPSHOT` 은 monorepo 표준 `0.0.1-SNAPSHOT` 으로 통일 — `:sdk-java:publishToMavenLocal` 결과물의 artifactId 는 `passkey-sdk-java` 그대로지만 version 이 바뀌므로 외부 의존이 있었다면 갱신 필요 (sample-rp 의 의존은 `project(":sdk-java")` 로 직접 전환되므로 영향 없음).

### 2.4 sample-rp 정리

**옛 standalone artifact 제거** (sdk-java 와 동일 패턴):
- `sample-rp/settings.gradle.kts`
- `sample-rp/gradlew`, `gradlew.bat`, `gradle/wrapper/`
- `sample-rp/gradle.properties` (단순 jvmargs)

**`sample-rp/build.gradle.kts` 갱신**:

```kotlin
plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

dependencies {
    implementation(project(":sdk-java"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.session:spring-session-core")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(libs.webauthn4j.test)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
}
```

**주요 변화**:
- `repositories { mavenLocal(); mavenCentral() }` 통째로 제거
- Spring Boot 버전 3.5.0 → catalog 3.5.14
- webauthn4j-test 버전 명시 → catalog alias
- `group` / `version` / `java { toolchain }` 제거

### 2.5 root build.gradle.kts 영향

**변경 없음**. `allprojects` / `subprojects` block 이 새 모듈 두 개에도 자동 적용. sdk-java 는 Spring Boot plugin 미사용 — root subprojects 가 dependency-management 만 apply 하므로 BOM import 는 되지만 `bootJar` 강제는 없음.

## 3. 테스트 + dev profile 통합 + cleanup

### 3.1 테스트 동작 확인

**sdk-java**:
```bash
./gradlew :sdk-java:test
```
기존 WireMockContractIT 가 root catalog 의 wiremock 3.10.0 과 동일 버전이라 변경 없이 통과 예상.

**sample-rp**:
```bash
./gradlew :sample-rp:test
```
Spring Boot 3.5.0 → 3.5.14 patch bump 만. webauthn4j-test 0.31.5 동일. break 없음 예상.

**전체 회귀**:
```bash
./gradlew check
```
기존 admin-app / passkey-app / core 의 모든 IT 가 영향 없음을 확인.

**의도적 자제**: 새 unit test / IT 추가 안 함. 기존 SDK + sample-rp 테스트가 회귀 채널.

### 3.2 docs/dev-setup.md 갱신

기존 uncommitted `docs/dev-setup.md` 의:

**§1.2 저장소 구조** — Before:
```
/Users/jhyun/Git/10-work/crosscert/
├── Passkey2/        # 백엔드 + admin-ui (이 저장소)
└── sample-rp/       # 데모 RP 앱 (별도 저장소)
```

After:
```
/Users/jhyun/Git/10-work/crosscert/
└── Passkey2/        # 모든 코드 (단일 저장소)
    ├── core/
    ├── passkey-app/
    ├── admin-app/
    ├── admin-ui/
    ├── sdk-java/
    └── sample-rp/
```

**§3.5 sample-rp 기동** — Before:
```bash
cd /Users/jhyun/Git/10-work/crosscert/sample-rp
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
./gradlew :sdk-java:publishToMavenLocal
cd /Users/jhyun/Git/10-work/crosscert/sample-rp
PASSKEY_... ./gradlew bootRun
```

After (Passkey2 root 안에서 단일 명령):
```bash
PASSKEY_BASE_URL=http://localhost:8080 \
PASSKEY_TENANT_ID=7F00DEAD00000000000000000ACE0001 \
PASSKEY_API_KEY=pk_devacme0dev_acme_secret_known_plaintext_for_local_test_only \
PASSKEY_ISSUER_BASE=http://localhost:8080 \
SAMPLE_RP_ORIGIN=http://localhost:9090 \
./gradlew :sample-rp:bootRun
```

### 3.3 옛 외부 디렉터리 cleanup

main merge 까지 모두 끝난 후 마지막에 수행. **자동화 X — 사용자 명시적 실행**:

```bash
TS=$(date +%Y%m%d-%H%M%S)
cd /Users/jhyun/Git/10-work/crosscert
tar czf /tmp/sdk-java-pre-monorepo-${TS}.tar.gz sdk-java/
tar czf /tmp/sample-rp-pre-monorepo-${TS}.tar.gz sample-rp/

# 확인 후 삭제
ls -la /tmp/*pre-monorepo*.tar.gz
rm -rf sdk-java/ sample-rp/
```

### 3.4 mavenLocal 의존자 검토

```bash
grep -rn "mavenLocal\|passkey-sdk-java:" --include="*.gradle*" \
  /Users/jhyun/Git/10-work/crosscert/Passkey2 \
  /Users/jhyun/Git/10-work/crosscert/sample-rp \
  /Users/jhyun/Git/10-work/crosscert/sdk-java
```

sample-rp 외 의존자 없다고 보임. 발견 시 추가 갱신.

### 3.5 위험과 대응

| 위험 | 영향 | 대응 |
|---|---|---|
| sdk-java 의 의존 버전이 Spring Boot 3.5 BOM 과 불일치 | compile fail or runtime ClassNotFoundError | catalog 와 root subprojects 의 BOM 으로 통일, 명시 version 제거. fail 시 명시 version 복구로 빠른 롤백 |
| sample-rp Spring Boot 3.5.0 → 3.5.14 minor bump | 작은 behavior 차이 | patch 만 — break 없음 예상. SampleRpSmokeIT 가 회귀 채널 |
| `webauthn4j-test:0.31.5.RELEASE` catalog 와 sample-rp 명시 차이 | NoClassDefFoundError | catalog 로 통일 |
| `mavenLocal()` 제거 후 다른 곳에서 의존 깨짐 | build fail | §3.4 grep 으로 사전 검출 |
| 옛 디렉터리 cleanup 시 미리 작업 진행 중인 게 있으면 손실 | uncommitted work 손실 | tar.gz 백업 + 사용자 직접 실행 (자동화 X) |
| publish 파이프라인 영향 | `:sdk-java:publishToMavenLocal` 명령이 안 됨 | monorepo 에서도 동일 명령으로 동작 — maven-publish plugin 유지 |
| sdk-java version 변경 (0.1.0-SNAPSHOT → 0.0.1-SNAPSHOT) | 외부 mavenLocal 의존자 깨짐 | sample-rp 외 의존자 없음 (§3.4). sample-rp 는 project(...) 로 전환 — version 무관 |

### 3.6 후속 작업

`docs/superpowers/followups/2026-05-28-monorepo-followups.md` 신규 작성:

1. sdk-java GitHub publish 자동화 (Maven Central 등) — 외부 publish 필요 생길 때
2. sample-rp Dockerfile — production-like 환경 테스트 필요할 때
3. sdk-java multi-language SDK (Python/Node)
4. Manual smoke checklist:
   - `./gradlew :sdk-java:test` 통과
   - `./gradlew :sample-rp:test` 통과
   - `./gradlew check` 전체 통과
   - `:sample-rp:bootRun` 으로 RP 부팅 → `http://localhost:9090/` 으로 사이트 접속
   - dev seed acme-corp API key 로 등록 / 인증 한 사이클 성공
