# monorepo 통합 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `~/Git/10-work/crosscert/sdk-java/` 와 `~/Git/10-work/crosscert/sample-rp/` 두 외부 repo 를 Passkey2 monorepo 의 root level subproject 로 흡수. mavenLocal 의존 제거, project(":sdk-java") 직접 의존으로 전환.

**Architecture:**
- 두 외부 repo 의 src + build 파일을 .git 제외하고 rsync 복사 → Passkey2 root level (`sdk-java/`, `sample-rp/`)
- 각 자체 wrapper / settings / gradle.properties 제거 → root Gradle wrapper + settings 가 관리
- sdk-java build.gradle.kts: java-library + maven-publish 유지, 의존 버전 명시 → BOM + catalog 위임
- sample-rp build.gradle.kts: mavenLocal() 제거 + project(":sdk-java") 직접 의존 + catalog alias 적용
- 옛 외부 디렉터리는 main merge 후 사용자가 tar.gz 백업 + rm -rf

**Tech Stack:** Gradle Kotlin DSL multi-module + version catalog + Spring Boot 3.5.14 BOM

**Worktree:** `.claude/worktrees/monorepo-merge` (branch `feature/monorepo-merge` from main `f852309`)

**Spec:** [docs/superpowers/specs/2026-05-28-monorepo-merge-design.md](../specs/2026-05-28-monorepo-merge-design.md)

**Test-minimization:**
- 신규 unit test / IT 추가 없음
- 기존 sdk-java 의 `PasskeyClientContractIT` (WireMock) + sample-rp 의 `SampleRpSmokeIT` (webauthn4j-test ClientPlatform) 가 회귀 채널
- `./gradlew check` 전체 회귀 (admin-app / passkey-app / core / sdk-java / sample-rp)
- 각 task commit 후 codex review (`Agent` tool, `codex:codex-rescue`, `--fresh`)

---

## Task 별 commit prefix

| Task | prefix | 이유 |
|---|---|---|
| 1 | `chore(build)` | sdk-java 파일 복사 + 자체 wrapper / settings 제거 |
| 2 | `chore(build)` | sample-rp 파일 복사 + 자체 wrapper / settings 제거 |
| 3 | `chore(build)` | settings.gradle.kts 에 :sdk-java + :sample-rp include |
| 4 | `chore(build)` | sdk-java build.gradle.kts root catalog + BOM 위임 |
| 5 | `chore(build)` | sample-rp build.gradle.kts project(":sdk-java") + catalog 적용 |
| 6 | `test` | `:sdk-java:test` + `:sample-rp:test` + `./gradlew check` 통과 |
| 7 | `docs(dev-setup)` | docs/dev-setup.md 갱신 (monorepo 경로) + commit |
| 8 | `docs(followups)` | 옛 외부 디렉터리 cleanup 안내 + followup 문서 |

---

## Task 1: sdk-java 파일 복사 + 자체 standalone artifact 제거

**Files:**
- Copy: `~/Git/10-work/crosscert/sdk-java/{src,build.gradle.kts,scripts,...}` (.git/build/.gradle 제외) → `Passkey2/sdk-java/`
- Delete: `sdk-java/settings.gradle.kts`, `sdk-java/gradlew`, `sdk-java/gradlew.bat`, `sdk-java/gradle/wrapper/`, `sdk-java/gradle.properties`

- [ ] **Step 1: rsync 복사**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/monorepo-merge
rsync -av --exclude='.git' --exclude='build' --exclude='.gradle' \
  /Users/jhyun/Git/10-work/crosscert/sdk-java/ \
  ./sdk-java/
```

Expected: `sdk-java/` 디렉터리 생성, `src/main/java/com/crosscert/passkey/sdk/PasskeyClient.java` + 17개 Java 파일 + `build.gradle.kts` + `settings.gradle.kts` + `gradlew*` + `gradle/wrapper/` 복사.

- [ ] **Step 2: 자체 standalone artifact 제거**

```bash
rm sdk-java/settings.gradle.kts
rm -f sdk-java/gradlew sdk-java/gradlew.bat
rm -rf sdk-java/gradle/wrapper
# gradle.properties 가 단순 jvmargs + caching 만 — root 와 중복이라 삭제
rm -f sdk-java/gradle.properties
# gradle/wrapper 만 삭제 후 gradle/ 디렉터리가 비어있으면 그것도 삭제
rmdir sdk-java/gradle 2>/dev/null || true
```

Expected: `sdk-java/` 안에 `src/`, `build.gradle.kts`, (있으면) `scripts/` 만 남음.

- [ ] **Step 3: 상태 확인**

Run: `ls sdk-java/`
Expected: `src/`, `build.gradle.kts` (+ 있으면 scripts/, README 등). `settings.gradle.kts`, `gradlew*`, `gradle/` 없음.

- [ ] **Step 4: Commit**

```bash
git add sdk-java/
git commit -m "$(cat <<'EOF'
chore(build): sdk-java 파일 복사 + 자체 wrapper / settings 제거

~/Git/10-work/crosscert/sdk-java/ (외부 repo) 의 src + build.gradle.kts +
scripts 를 Passkey2 root level 로 rsync 복사 (.git / build / .gradle 제외).
monorepo root 의 Gradle wrapper + settings 가 관리하므로 자체 settings /
gradlew / gradle/wrapper / gradle.properties 제거.

이 commit 후 sdk-java 는 아직 settings 에 include 안 됨 — Task 3 에서 추가.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: Codex review**

```
Agent(subagent_type=codex:codex-rescue, prompt="--fresh Review HEAD of worktree /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/monorepo-merge. Task 1: sdk-java 파일을 외부 repo 에서 Passkey2 root 로 rsync 복사 (.git/build/.gradle 제외) + 자체 standalone artifact (settings.gradle.kts, gradlew, gradlew.bat, gradle/wrapper/, gradle.properties) 제거. Verify (a) 모든 src/main/java 와 src/test/java 의 .java 파일이 빠짐없이 복사됨; (b) build.gradle.kts 가 그대로 보존됨; (c) 자체 settings / wrapper 가 모두 제거됨 — ls sdk-java/ 가 src + build.gradle.kts 만 보임; (d) 다른 모듈 (core / passkey-app / admin-app / admin-ui) 의 파일이 잘못 건드려지지 않음. 100 words max.")
```

---

## Task 2: sample-rp 파일 복사 + 자체 standalone artifact 제거

**Files:**
- Copy: `~/Git/10-work/crosscert/sample-rp/{src,build.gradle.kts,scripts,...}` → `Passkey2/sample-rp/`
- Delete: `sample-rp/settings.gradle.kts`, `sample-rp/gradlew`, `sample-rp/gradlew.bat`, `sample-rp/gradle/wrapper/`, `sample-rp/gradle.properties`

- [ ] **Step 1: rsync 복사**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/monorepo-merge
rsync -av --exclude='.git' --exclude='build' --exclude='.gradle' \
  /Users/jhyun/Git/10-work/crosscert/sample-rp/ \
  ./sample-rp/
```

Expected: `sample-rp/` 디렉터리 생성, `src/main/java/com/crosscert/passkey/samplerp/` 의 모든 .java + `src/main/resources/{application.yml, static/, templates/}` + `src/test/java/.../SampleRpSmokeIT.java` + `scripts/bootstrap-sample-rp.sh` + `build.gradle.kts` 등 모두 복사.

- [ ] **Step 2: 자체 standalone artifact 제거**

```bash
rm sample-rp/settings.gradle.kts
rm -f sample-rp/gradlew sample-rp/gradlew.bat
rm -rf sample-rp/gradle/wrapper
rm -f sample-rp/gradle.properties
rmdir sample-rp/gradle 2>/dev/null || true
```

Expected: `sample-rp/` 안에 `src/`, `build.gradle.kts`, `scripts/` (bootstrap-sample-rp.sh) 만 남음.

- [ ] **Step 3: 상태 확인**

Run: `ls sample-rp/`
Expected: `src/`, `build.gradle.kts`, `scripts/`. `settings.gradle.kts`, `gradlew*`, `gradle/` 없음.

- [ ] **Step 4: Commit**

```bash
git add sample-rp/
git commit -m "$(cat <<'EOF'
chore(build): sample-rp 파일 복사 + 자체 wrapper / settings 제거

~/Git/10-work/crosscert/sample-rp/ (외부 repo) 의 src + build.gradle.kts +
scripts (bootstrap-sample-rp.sh) 를 Passkey2 root level 로 rsync 복사
(.git / build / .gradle 제외). 자체 settings / gradlew / gradle/wrapper /
gradle.properties 제거.

이 commit 후 sample-rp 는 아직 settings 에 include 안 됨 + 여전히 mavenLocal()
+ passkey-sdk-java:0.1.0-SNAPSHOT 의존 — Task 3 (include) + Task 5
(dependency 갱신) 에서 처리.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: Codex review**

```
Agent(subagent_type=codex:codex-rescue, prompt="--fresh Review HEAD of worktree /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/monorepo-merge. Task 2: sample-rp 파일을 외부 repo 에서 Passkey2 root 로 rsync 복사 + 자체 standalone artifact 제거. Task 1 의 sdk-java 와 동일 패턴. Verify (a) src/main/java 의 SampleRpApplication / config / web / user / session 디렉터리 모두 복사; (b) src/main/resources 의 application.yml + static/ + templates/ 보존; (c) src/test/java 의 SampleRpSmokeIT 보존; (d) scripts/bootstrap-sample-rp.sh 보존; (e) 자체 settings / wrapper 가 모두 제거됨. 100 words max.")
```

---

## Task 3: settings.gradle.kts 에 :sdk-java + :sample-rp include

**Files:**
- Modify: `settings.gradle.kts`

- [ ] **Step 1: settings.gradle.kts 갱신**

`settings.gradle.kts`:
```kotlin
rootProject.name = "passkey2"

include(":core", ":passkey-app", ":admin-app", ":sdk-java", ":sample-rp")
```

- [ ] **Step 2: gradle settings 인식 확인**

Run: `./gradlew projects 2>&1 | tail -20`
Expected: `:sdk-java` + `:sample-rp` 가 list 에 보임. 예시 출력:
```
Root project 'passkey2'
+--- Project ':admin-app'
+--- Project ':core'
+--- Project ':passkey-app'
+--- Project ':sample-rp'
\--- Project ':sdk-java'
```

**중요**: 이 시점에는 sdk-java + sample-rp 의 build.gradle.kts 가 아직 옛 standalone 형태 (sdk-java 는 자체 plugins block + repositories, sample-rp 는 mavenLocal + passkey-sdk-java:0.1.0-SNAPSHOT). 그래도 `projects` task 는 settings 만 읽으니 통과.

`./gradlew :sdk-java:compileJava` 또는 `:sample-rp:compileJava` 시도 시 fail 가능성 — 그건 Task 4 / Task 5 에서 build.gradle.kts 정리 후 통과.

- [ ] **Step 3: Commit**

```bash
git add settings.gradle.kts
git commit -m "$(cat <<'EOF'
chore(build): settings.gradle.kts 에 :sdk-java + :sample-rp include

이제 monorepo root 가 5 개 Gradle subproject 를 인식 — core / passkey-app /
admin-app / sdk-java / sample-rp. admin-ui 는 Node 프로젝트라 include 안 함.

이 commit 후 build.gradle.kts 들이 아직 옛 standalone 형식이라 :sdk-java
또는 :sample-rp build / test 는 fail 가능 — Task 4 + Task 5 에서 정리.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: Codex review**

```
Agent(subagent_type=codex:codex-rescue, prompt="--fresh Review HEAD of worktree /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/monorepo-merge. Task 3: settings.gradle.kts 에 ':sdk-java', ':sample-rp' 두 모듈 include 추가. Verify (a) include 순서가 자연스럽고 기존 :core, :passkey-app, :admin-app 와 일관성; (b) admin-ui 가 include 에 추가되지 않음 (Node 프로젝트); (c) `./gradlew projects` 가 5 모듈을 모두 list. 100 words max.")
```

---

## Task 4: sdk-java/build.gradle.kts root catalog + BOM 위임

**Files:**
- Modify: `sdk-java/build.gradle.kts`

- [ ] **Step 1: 기존 build.gradle.kts 확인**

Run: `cat sdk-java/build.gradle.kts`

Expected: 기존 외부 standalone 형태 — `plugins { java-library; maven-publish }` + `group / version` + `java { toolchain(17); withSourcesJar }` + `repositories { mavenCentral } ` + 명시 version 의존 (spring-web 6.2.0, jackson 2.21.0 등) + `publishing { ... }`.

- [ ] **Step 2: 새 build.gradle.kts 작성**

`sdk-java/build.gradle.kts`:
```kotlin
plugins {
    `java-library`
    `maven-publish`
}

// group / version 은 root allprojects 가 com.crosscert.passkey / 0.0.1-SNAPSHOT
// 로 설정. 옛 standalone 의 0.1.0-SNAPSHOT 은 monorepo 표준으로 통일.
// toolchain 17 은 root subprojects 가 강제.

java {
    withSourcesJar()
}

dependencies {
    // Spring Boot 3.5 BOM (root subprojects 에서 import) 이 spring-web 6.2.x,
    // jackson, slf4j 를 모두 관리. 명시 version 제거.
    api("org.springframework:spring-web")
    api("com.fasterxml.jackson.core:jackson-databind")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    api(rootProject.libs.nimbus.jose.jwt)
    api("org.slf4j:slf4j-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation(rootProject.libs.wiremock.standalone)
    // junit-platform-launcher 는 root subprojects 가 모든 모듈에 testRuntimeOnly
}

tasks.named<Test>("test") { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("maven") { from(components["java"]) }
    }
}
```

**참고**: root catalog 의 alias 를 subproject build script 에서 참조하려면 `rootProject.libs.nimbus.jose.jwt` 형태 또는 type-safe `libs.nimbus.jose.jwt` (Gradle 8.5+ 자동 노출). Passkey2 root 의 admin-app / passkey-app 이 어떻게 참조하는지 확인 후 동일 패턴 사용. (대부분 그냥 `libs.nimbus.jose.jwt` 직접 사용 — Gradle 자동 노출).

`rootProject.libs.*` 가 못 인식되면 `libs.*` 로 변경. 

- [ ] **Step 3: 기존 모듈의 catalog 사용 패턴 확인**

Run: `grep -rn "libs\." admin-app/build.gradle.kts passkey-app/build.gradle.kts core/build.gradle.kts 2>&1 | head -10`

Expected: `libs.something` 같은 직접 참조 보임. 그 패턴 그대로 사용.

만약 위 출력에서 `rootProject.libs.*` 가 쓰이면 그대로 사용. `libs.*` 가 쓰이면 위 코드 의 `rootProject.libs.*` 를 `libs.*` 로 변경.

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew :sdk-java:compileJava 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL. 컴파일 fail 시 :
- `Unresolved reference: libs` → `rootProject.libs.*` 형태로 변경 or 반대로
- `Could not find dependency org.springframework:spring-web` → BOM import 가 sdk-java 에 적용 안 됨. root subprojects 의 `apply(plugin = "io.spring.dependency-management")` 가 sdk-java 에도 적용되는지 확인. 적용되어야 함.

- [ ] **Step 5: test compile + run**

Run: `./gradlew :sdk-java:test 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL. `PasskeyClientContractIT` (WireMock 기반) 통과.

WireMock fail 시 — `wiremock.standalone` alias 가 잘못된 경로일 수 있음. `gradle/libs.versions.toml` 의 `wiremock-standalone` 키 확인.

- [ ] **Step 6: Commit**

```bash
git add sdk-java/build.gradle.kts
git commit -m "$(cat <<'EOF'
chore(build): sdk-java build.gradle.kts root catalog + BOM 위임

명시 의존 version 제거 — Spring Boot 3.5 BOM (root subprojects 가 import)
이 spring-web 6.2.x, jackson, slf4j 를 모두 관리. nimbus-jose-jwt 와
wiremock-standalone 은 root catalog alias 사용.

group / version / toolchain 도 제거 — root allprojects + subprojects 가
처리. 옛 0.1.0-SNAPSHOT → monorepo 표준 0.0.1-SNAPSHOT.

maven-publish plugin 보존 — :sdk-java:publishToMavenLocal 명령은 그대로
작동 (필요 시 외부 publish 도 가능).

PasskeyClientContractIT 통과 확인.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 7: Codex review**

```
Agent(subagent_type=codex:codex-rescue, prompt="--fresh Review HEAD of worktree /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/monorepo-merge. Task 4: sdk-java/build.gradle.kts 를 root catalog + Spring Boot 3.5 BOM 으로 단순화. group / version / toolchain / repositories / 의존 명시 version 모두 제거. java-library + maven-publish plugin 유지. Verify (a) nimbus-jose-jwt 와 wiremock-standalone catalog alias 가 정상 인식됨 (PasskeyClientContractIT 통과로 입증); (b) spring-web 의 transitive 가 jackson-annotations 2.21+ 와 충돌하지 않음 (root build.gradle.kts 가 jackson-annotations 2.21 강제); (c) maven-publish 의 components['java'] publishing 동작 (선택: ./gradlew :sdk-java:publishToMavenLocal 한 번 시도). 150 words max.")
```

---

## Task 5: sample-rp/build.gradle.kts project(":sdk-java") + catalog 적용

**Files:**
- Modify: `sample-rp/build.gradle.kts`

- [ ] **Step 1: 기존 build.gradle.kts 확인**

Run: `cat sample-rp/build.gradle.kts`

Expected: plugins block (Spring Boot 3.5.0 명시) + group/version + java toolchain + repositories (mavenLocal + mavenCentral) + dependencies (passkey-sdk-java:0.1.0-SNAPSHOT, spring-boot-starter-*, webauthn4j-test:0.31.5.RELEASE) + tasks.test.

- [ ] **Step 2: 새 build.gradle.kts 작성**

`sample-rp/build.gradle.kts`:
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
    // Java 17 UUID reflection access (passkey-app 와 동일 패턴)
    jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
}
```

**제거된 것**:
- `repositories { mavenLocal(); mavenCentral() }` — root `allprojects { repositories { mavenCentral() } }` 가 처리
- `group = "com.crosscert.passkey"`, `version = "0.0.1-SNAPSHOT"` — root allprojects 가 처리
- `java { toolchain ... }` — root subprojects 가 17 강제
- Spring Boot version `3.5.0` 명시 — catalog 의 3.5.14 자동 적용
- `implementation("com.crosscert.passkey:passkey-sdk-java:0.1.0-SNAPSHOT")` → `implementation(project(":sdk-java"))`
- `testImplementation("com.webauthn4j:webauthn4j-test:0.31.5.RELEASE")` → `libs.webauthn4j.test`
- `testRuntimeOnly("org.junit.platform:junit-platform-launcher")` — root subprojects 가 자동

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew :sample-rp:compileJava 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL.

Fail 시 :
- `Could not find org.springframework.boot:spring-boot-starter-web` → Spring Boot plugin apply 가 안 됨. `alias(libs.plugins.spring.boot)` 의 namespace 확인. catalog `[plugins]` 에 `spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }` 가 정의되어 있음 — 이걸 그대로 사용.
- `unresolved reference: libs` — admin-app / passkey-app 의 build.gradle.kts 패턴 따라 수정.

- [ ] **Step 4: test compile + run**

Run: `./gradlew :sample-rp:test 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL. `SampleRpSmokeIT` 통과.

SampleRpSmokeIT fail 시 :
- webauthn4j-test 버전 불일치: catalog 의 `0.31.5.RELEASE` (Passkey2 root 와 동일) 가 SDK + sample-rp 모두 일치하는지 확인
- Spring Boot 3.5.0 → 3.5.14 patch bump 가 영향: 거의 break 없음. 로그 보고 구체 메시지 확인 후 대응.

- [ ] **Step 5: Commit**

```bash
git add sample-rp/build.gradle.kts
git commit -m "$(cat <<'EOF'
chore(build): sample-rp project(":sdk-java") + catalog alias

mavenLocal() 제거 + passkey-sdk-java:0.1.0-SNAPSHOT → project(":sdk-java")
직접 의존. monorepo 안에서 SDK 변경 시 publishToMavenLocal 없이 즉시 반영.

Spring Boot plugin / dep-mgmt 를 catalog alias 로. webauthn4j-test 도 catalog
의 0.31.5.RELEASE 사용. Spring Boot 3.5.0 → 3.5.14 자동 적용 (patch bump,
break 없음).

group / version / toolchain 제거 — root allprojects + subprojects 가 처리.

SampleRpSmokeIT 통과 확인.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 6: Codex review**

```
Agent(subagent_type=codex:codex-rescue, prompt="--fresh Review HEAD of worktree /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/monorepo-merge. Task 5: sample-rp/build.gradle.kts 를 monorepo project(:sdk-java) + catalog 로 갱신. Verify (a) mavenLocal() repository 가 완전 제거됨 (sample-rp/build.gradle.kts grep mavenLocal → no matches); (b) project(':sdk-java') 의존이 sdk-java 의 api dependencies 를 transitive 로 노출 (SampleRpApplication 의 PasskeyClient import 정상); (c) Spring Boot plugin 이 alias 로 적용되어 catalog 의 3.5.14 가 picked up; (d) webauthn4j-test 가 catalog alias 사용 + SampleRpSmokeIT 통과; (e) 다른 곳에 mavenLocal 의존이 남지 않음 — grep -rn 'mavenLocal' --include='*.gradle*' worktree 전체. 200 words max.")
```

---

## Task 6: 전체 회귀 — `./gradlew check`

**Files:**
- 변경 없음 — 회귀 테스트만

- [ ] **Step 1: 전체 check 실행**

Run: `./gradlew check 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL. 다음 모두 통과:
- `:core:test` (BaseEntityCallbackIT, AuditLogTest, etc.)
- `:passkey-app:test` (Fido2EndToEndIT 등 — Testcontainers)
- `:admin-app:test` (9 IT 모두)
- `:sdk-java:test` (PasskeyClientContractIT)
- `:sample-rp:test` (SampleRpSmokeIT)

Fail 시 — 구체 IT 의 메시지를 보고 대응. 단 Task 4 / Task 5 에서 이미 sdk-java / sample-rp 의 test 가 통과한 상태라 다른 모듈에서 break 가 발생할 가능성은 낮다.

가능 시나리오:
- root 의 jackson-annotations 2.21 강제가 sdk-java 에 영향 없음 (root subprojects 적용 범위가 모든 자식 모듈)
- sdk-java 의 transitive 가 admin-app / passkey-app 에 새로 들어와 conflict — 없음 (sdk-java 는 다른 모듈이 의존하지 않음)

- [ ] **Step 2: bootJar 한 번 확인 (선택, 빌드 sanity)**

Run: `./gradlew :sample-rp:bootJar 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL. `sample-rp/build/libs/sample-rp-0.0.1-SNAPSHOT.jar` 생성 (옛 standalone 의 sample-rp-0.0.1-SNAPSHOT.jar 와 동일).

- [ ] **Step 3: Commit (회귀 통과 마커)**

회귀만 돌렸으니 코드 변경 없음 — 명시적 commit 생략. 다음 task 로 진행.

또는 작은 marker commit 가능:

```bash
git commit --allow-empty -m "$(cat <<'EOF'
test: 전체 check 회귀 통과 (sdk-java + sample-rp monorepo 통합 후)

./gradlew check 5 모듈 모두 BUILD SUCCESSFUL:
- :core:test
- :passkey-app:test
- :admin-app:test
- :sdk-java:test (PasskeyClientContractIT)
- :sample-rp:test (SampleRpSmokeIT)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

빈 commit 은 history 가 추적 가능해 권장. 단 사용자 선호에 따라 skip 가능.

- [ ] **Step 4: Codex review**

```
Agent(subagent_type=codex:codex-rescue, prompt="--fresh Review HEAD of worktree /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/monorepo-merge. Task 6: ./gradlew check 전체 회귀 통과. Verify (a) check 가 5 개 subproject 의 모든 test/IT 를 실행했음 (gradle 출력에서 :core:test, :passkey-app:test, :admin-app:test, :sdk-java:test, :sample-rp:test 모두 보임); (b) BUILD SUCCESSFUL; (c) bootJar 한 번 더 검증 시 :sample-rp:bootJar 가 fat jar 생성. 100 words max.")
```

---

## Task 7: docs/dev-setup.md 갱신 + commit

**Files:**
- Modify: `docs/dev-setup.md`

배경: 직전 dev-profile phase 에서 `docs/dev-setup.md` 를 작성했으나 sample-rp 가 별도 저장소라 가정한 채로 commit 하지 않았다. monorepo 후 경로로 갱신 + commit.

- [ ] **Step 1: 현재 dev-setup.md 가 어디 있는지 확인**

Run: `ls docs/dev-setup.md 2>&1`

Expected: 둘 중 하나
- (a) `docs/dev-setup.md` 가 working tree 에 untracked 로 있음 — 그대로 갱신
- (b) `docs/dev-setup.md` 가 없음 — 이전에 작성한 main worktree 의 파일이 worktree 에 안 보이는 상황. 새로 작성해야 함.

worktree 에서는 main 의 untracked 파일이 보이지 않는다. 이 plan 을 만든 시점에 작성된 dev-setup.md 는 main worktree 의 untracked 상태이므로, 이 worktree 에서는 새로 작성한다.

- [ ] **Step 2: docs/dev-setup.md 새로 작성 (monorepo 경로)**

`docs/dev-setup.md`:
```markdown
# Passkey2 — 개발자 환경 기동 가이드

앱 개발자 (RP 측) 가 패스키 등록 / 인증 테스트를 빠르게 시작할 수 있도록 작성한 문서.
**dev profile** 로 띄우면 admin-ui 콘솔과 RP 측 API 가 모두 동작하고, 시드된 tenant
3개 + API key 3개로 즉시 호출할 수 있다.

작성: 2026-05-28
대상 commit: monorepo-merge 후
관련:
- [Activity 페이지 spec](superpowers/specs/2026-05-27-activity-page-design.md)
- [Activity followups](superpowers/followups/2026-05-28-activity-page-followups.md)
- [monorepo 통합 spec](superpowers/specs/2026-05-28-monorepo-merge-design.md)

---

## 1. 사전 준비

### 1.1 의존성 도구

| 도구 | 버전 | 용도 |
|---|---|---|
| Docker | recent | Oracle XE + Redis 컨테이너 |
| JDK | 17 | passkey-app / admin-app / sdk-java / sample-rp |
| Node.js | 18+ | admin-ui (선택, HMR 개발 시) |

### 1.2 저장소 구조 (monorepo)

```
/Users/jhyun/Git/10-work/crosscert/
└── Passkey2/        # 모든 코드 (단일 저장소)
    ├── core/
    ├── passkey-app/
    ├── admin-app/
    ├── admin-ui/
    ├── sdk-java/    # 패스키 SDK (Java)
    └── sample-rp/   # 데모 RP 앱
```

### 1.3 컨테이너 (Oracle + Redis)

Passkey2 루트에서:

```bash
docker compose up -d
docker ps  # passkey-oracle (1521) + passkey-redis (6379) 가 healthy
```

`docker-compose.yml` 은 인프라만 제공. 앱은 로컬에서 직접 실행.

---

## 2. dev profile 이 제공하는 것

**`SPRING_PROFILES_ACTIVE=dev`** 로 admin-app 을 부팅하면 Flyway repeatable migration
`core/src/main/resources/db/dev/R__dev_seed.sql` 가 자동 적용되어 다음 시드 데이터가
DB 에 들어간다.

### 2.1 시드 tenant (3개)

| slug | rp_id | rp_name | tenant_id (RAW(16) hex) |
|---|---|---|---|
| `acme-corp` | localhost | Acme Corp | `7F00DEAD00000000000000000ACE0001` |
| `foo-corp` | localhost | Foo Corp | `7F00DEAD0000000000000000F00C0001` |
| `bar-corp` | localhost | Bar Corp | `7F00DEAD0000000000000000BA1C0001` |

모두 `localhost` 를 rpId 로 사용 — 로컬 브라우저 / 로컬 RP 앱이 그대로 사용 가능.
각 tenant 의 `allowed_origin` 은 `http://localhost:9090` (sample-rp port).
`accepted_format` 은 `none` (passkey 기본).

### 2.2 시드 API key (3개)

`prefix + secret` 를 콜론 없이 이어붙인 **X-API-Key 헤더 값**:

| Tenant | X-API-Key 헤더값 |
|---|---|
| acme-corp | `pk_devacme0dev_acme_secret_known_plaintext_for_local_test_only` |
| foo-corp | `pk_devfoo01dev_foo_secret_known_plaintext_for_local_test_only_x` |
| bar-corp | `pk_devbar02dev_bar_secret_known_plaintext_for_local_test_only_xy` |

scope: `registration` + `authentication` (양쪽 다 허용).

> ⚠️ **dev/local 전용 시드.** 위 plaintext 가 git 에 commit 되어 있다.
> 절대 prod 활성화 금지.

### 2.3 시드 관리자 계정 (V11 + V23 시드 — local profile 에서도 동일)

| email | password | role | tenant |
|---|---|---|---|
| `alice@crosscert.com` | `alice-temp-pw` | PLATFORM_OPERATOR | (전체) |
| `bob@crosscert.com` | `bob-temp-pw` | RP_ADMIN | demo-rp |

`local` profile 의 admin-ui 로그인 화면은 alice 계정을 자동 prefill 한다.

---

## 3. 기동 순서

### 3.1 컨테이너

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
docker compose up -d
```

### 3.2 admin-app (port 8081)

```bash
SPRING_PROFILES_ACTIVE=dev ./gradlew :admin-app:bootRun
```

부팅 로그에서 다음을 확인:

```
INFO ... DbMigrate : Migrating schema "APP_OWNER" with repeatable migration "dev seed"
INFO ... DbMigrate : Successfully applied 1 migration to schema "APP_OWNER"
INFO ... AdminApplication : Started AdminApplication in N seconds
```

### 3.3 passkey-app (port 8080)

```bash
SPRING_PROFILES_ACTIVE=dev ./gradlew :passkey-app:bootRun \
  --args="--passkey.id-token.issuer-base=http://localhost:8080"
```

```bash
curl -s http://localhost:8080/actuator/health
# {"status":"UP"}
```

### 3.4 admin-ui (선택)

#### 옵션 A — 빌드된 dist (port 8081)

브라우저에서 `http://localhost:8081/admin/` 접속.

#### 옵션 B — Vite dev server (HMR + port 5173)

```bash
cd admin-ui
npm install
npm run dev
```

`http://localhost:5173/admin/` 로 접속.

### 3.5 sample-rp (port 9090) — monorepo 안에서 직접 실행

Passkey2 루트에서 단일 명령:

```bash
PASSKEY_BASE_URL=http://localhost:8080 \
PASSKEY_TENANT_ID=7F00DEAD00000000000000000ACE0001 \
PASSKEY_API_KEY=pk_devacme0dev_acme_secret_known_plaintext_for_local_test_only \
PASSKEY_ISSUER_BASE=http://localhost:8080 \
SAMPLE_RP_ORIGIN=http://localhost:9090 \
./gradlew :sample-rp:bootRun
```

| 환경변수 | 값 |
|---|---|
| `PASSKEY_BASE_URL` | passkey-app URL (8080) |
| `PASSKEY_TENANT_ID` | tenant_id (RAW(16) hex, 32자) — dev seed 의 acme-corp |
| `PASSKEY_API_KEY` | X-API-Key 헤더 값 (prefix + secret) |
| `PASSKEY_ISSUER_BASE` | ID token issuer (passkey-app 의 `id-token.issuer-base` 와 동일) |
| `SAMPLE_RP_ORIGIN` | RP 자기 origin — `allowed_origins` 에 들어 있어야 함 |

부팅 후 브라우저에서 `http://localhost:9090/` 접속.

다른 tenant 로 시험하려면 `PASSKEY_TENANT_ID` + `PASSKEY_API_KEY` 만 바꿔서 재기동.

⚠️ SDK 가 monorepo 안의 `:sdk-java` 모듈에 직접 의존하므로 `:sdk-java:publishToMavenLocal`
단계 불필요. sdk-java 코드 변경 즉시 sample-rp 가 반영.

---

## 4. 동작 확인

### 4.1 admin-ui 시점

alice 자동 prefill 로 로그인 후:
- 사이드바: Tenants / Activity / Signing Keys / MDS / Audit Log
- **Tenants** → acme-corp / foo-corp / bar-corp / demo-rp 4개 이상
- **Activity** → 24h KPI + Top 5 + 이벤트 스트림
- **Audit Log** → tenantId UUID 로 필터 가능

### 4.2 RP 측 curl 시점

acme-corp tenant 의 register/start:

```bash
curl -X POST http://localhost:8080/api/v1/rp/registration/start \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: pk_devacme0dev_acme_secret_known_plaintext_for_local_test_only' \
  -d '{
    "userHandle": "ZGV2LXVzZXItMDAx",
    "displayName": "Dev User 001",
    "username": "dev-user-001"
  }'
```

성공 시 응답에 `publicKeyCredentialCreationOptions` + `registrationToken` 포함.

### 4.3 sample-rp 시점

`http://localhost:9090/` 에서 회원가입 → 패스키 등록 → 로그아웃 → 패스키 인증
한 사이클이 모두 정상 동작하면 환경 검증 완료.

---

## 5. 자주 만나는 문제

### 5.1 `Schema-validation: missing column [...]`

admin-app 을 먼저 `dev` 또는 `local` profile 로 띄워서 Flyway 가 V0~V24 +
R__dev_seed 까지 모두 적용하게 한다. 그 다음 passkey-app 을 띄운다.

### 5.2 `Detected failed repeatable migration: dev seed`

```bash
docker exec passkey-oracle bash -c 'echo "
DELETE FROM APP_OWNER.\"flyway_schema_history\"
 WHERE \"description\" LIKE '\''%dev seed%'\'' OR \"success\" = 0;
COMMIT;
EXIT;" | sqlplus -S system/oracle@//localhost:1521/XEPDB1'
```

그 후 admin-app 재기동.

### 5.3 `401 Unauthorized` (RP API 호출 시)

- X-API-Key 헤더에 콜론 없이 `prefix + secret` 가 이어붙어 있는지 확인
- prefix 가 정확히 11자 (`pk_` + 8자) 인지 확인
- API key 가 revoked 되지 않았는지

### 5.4 `ID token issuer mismatch`

passkey-app 의 `--passkey.id-token.issuer-base` 와 sample-rp 의 `PASSKEY_ISSUER_BASE`
가 동일한지 확인.

### 5.5 port 충돌

```bash
lsof -ti :8080 -ti :8081 -ti :5173 -ti :9090 | xargs kill
```

---

## 6. 시드 데이터 재설정 (clean slate)

```bash
docker compose down -v   # volumes 삭제 → Oracle 데이터 완전 초기화
docker compose up -d
SPRING_PROFILES_ACTIVE=dev ./gradlew :admin-app:bootRun
```

⚠️ 컨테이너 시작 후 Oracle XE 가 ready 되기까지 30~60초 걸린다.

---

## 7. 참고

- dev profile 추가: `feat(dev): dev profile + R__dev_seed` commit
- monorepo 통합: `Merge feature/monorepo-merge` commit
- 시드 SQL: [`core/src/main/resources/db/dev/R__dev_seed.sql`](../core/src/main/resources/db/dev/R__dev_seed.sql)
- ApiKey 인증 필터: `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java`
```

- [ ] **Step 3: Commit**

```bash
git add docs/dev-setup.md
git commit -m "$(cat <<'EOF'
docs(dev-setup): 개발자 환경 기동 가이드 — monorepo 통합 후

dev profile 활성화로 RP 등록/인증 테스트를 즉시 시작할 수 있도록 단일
저장소 안에서 admin-app + passkey-app + sample-rp + admin-ui 띄우는 방법
정리.

monorepo 통합 후 sample-rp 는 Passkey2 root 안 :sample-rp 모듈 — 더 이상
별도 저장소 / mavenLocal publish 불필요. ./gradlew :sample-rp:bootRun
단일 명령으로 기동.

자주 만나는 문제 5 + 시드 재설정 + 참고 링크 포함.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: Codex review**

```
Agent(subagent_type=codex:codex-rescue, prompt="--fresh Review HEAD of worktree /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/monorepo-merge. Task 7: docs/dev-setup.md 신규 작성 (monorepo 통합 후 경로). Verify (a) sample-rp 기동 명령이 :sample-rp:bootRun (monorepo 방식); (b) :sdk-java:publishToMavenLocal 단계가 명시적으로 '불필요' 라고 언급; (c) 디렉터리 구조 다이어그램이 monorepo (Passkey2/ 단일 root) 를 정확히 반영; (d) X-API-Key 헤더값 + tenant_id hex 가 dev seed 와 정확히 일치; (e) 자주 만나는 문제 섹션에 sample-rp 의 외부 저장소 경로 잔재 없음. 200 words max.")
```

---

## Task 8: followup 문서 + 옛 외부 디렉터리 cleanup 안내

**Files:**
- Create: `docs/superpowers/followups/2026-05-28-monorepo-merge-followups.md`

- [ ] **Step 1: followup 문서 작성**

`docs/superpowers/followups/2026-05-28-monorepo-merge-followups.md`:
```markdown
# monorepo 통합 Phase — Followups

Phase: monorepo-merge (2026-05-28)
Plan: [docs/superpowers/plans/2026-05-28-monorepo-merge.md](../plans/2026-05-28-monorepo-merge.md)
Spec: [docs/superpowers/specs/2026-05-28-monorepo-merge-design.md](../specs/2026-05-28-monorepo-merge-design.md)

## Manual smoke checklist (main merge 직전 또는 직후)

- [ ] **1. `./gradlew check`** 전체 통과 (`:core:test`, `:passkey-app:test`, `:admin-app:test`, `:sdk-java:test`, `:sample-rp:test`)
- [ ] **2. `:sample-rp:bootRun`** 으로 RP 부팅 → `http://localhost:9090/` 접속 성공
- [ ] **3. dev seed acme-corp API key** 로 등록 → credential 저장 → 로그아웃 → 인증 한 사이클 성공
- [ ] **4. `:sdk-java:publishToMavenLocal`** 명령이 여전히 작동 (외부 publish 필요 시 대비)
- [ ] **5. admin-ui Activity 페이지** 에서 sample-rp 의 audit row 가 보이는지 (이전 phase 의 기능)

## main merge 후 cleanup (사용자 명시적 실행)

**자동화하지 않는다 — 외부 디렉터리 삭제는 사용자가 직접 실행.**

```bash
# 1. tar.gz 백업 (환원 가능 상태 보존)
TS=$(date +%Y%m%d-%H%M%S)
cd /Users/jhyun/Git/10-work/crosscert
tar czf /tmp/sdk-java-pre-monorepo-${TS}.tar.gz sdk-java/
tar czf /tmp/sample-rp-pre-monorepo-${TS}.tar.gz sample-rp/

# 2. 백업 파일 확인
ls -la /tmp/*pre-monorepo*.tar.gz

# 3. 백업 검증 (선택)
tar tzf /tmp/sdk-java-pre-monorepo-${TS}.tar.gz | head -5
tar tzf /tmp/sample-rp-pre-monorepo-${TS}.tar.gz | head -5

# 4. 옛 외부 디렉터리 삭제
rm -rf sdk-java/ sample-rp/

# 5. 확인
ls /Users/jhyun/Git/10-work/crosscert/
# Passkey2/  만 보여야 함
```

## Deferred items (별도 phase 후보)

1. **sdk-java GitHub publish 자동화** — Maven Central / GitHub Packages 로 외부 publish. 외부 RP 가 SDK 를 의존할 때 필요. 현재는 dogfood 단계라 미루기.
2. **sample-rp Dockerfile** — production-like 환경 테스트 또는 staging 배포 시 필요.
3. **sdk-java multi-language SDK** — Python / Node.js / TypeScript SDK. RP 측 언어 다양화 시.
4. **버전 통일 후속** — sdk-java 가 옛 `0.1.0-SNAPSHOT` 에서 `0.0.1-SNAPSHOT` 으로 강제 이전됨. 향후 첫 stable release 때 `passkey-sdk-java` 단독 versioning 결정 필요.

---
*문서 갱신 책임: 각 followup 시작 시 brainstorm/plan 링크 추가, 완료 시 status [DONE] 표시*
```

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/followups/2026-05-28-monorepo-merge-followups.md
git commit -m "$(cat <<'EOF'
docs(followups): monorepo 통합 phase — manual smoke 5 + deferred 4

Manual smoke checklist: ./gradlew check, :sample-rp:bootRun, dev seed
API key 등록/인증 사이클, :sdk-java:publishToMavenLocal 동작 확인, Activity
페이지 회귀.

main merge 후 사용자가 명시적으로 실행할 외부 디렉터리 cleanup 절차
(tar.gz 백업 + rm -rf) — 자동화 X.

Deferred 4: SDK Maven Central publish, sample-rp Dockerfile, multi-language
SDK, sdk-java 단독 versioning 정책.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 3: Codex review (전체 phase 회고)**

```
Agent(subagent_type=codex:codex-rescue, prompt="--fresh Review the entire phase: ~7-8 commits on worktree /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/monorepo-merge (feature/monorepo-merge branch). This phase: (1) rsync 으로 sdk-java + sample-rp 외부 repo 를 Passkey2 root 로 흡수 (.git 제외, 자체 wrapper 제거); (2) settings.gradle.kts 에 :sdk-java + :sample-rp include; (3) sdk-java build.gradle.kts 를 root catalog + Spring Boot BOM 으로 단순화 (group/version/toolchain/repositories/명시 의존 version 제거, maven-publish plugin 유지); (4) sample-rp build.gradle.kts 의 mavenLocal() 제거 + implementation(project(\":sdk-java\")) 직접 의존, Spring Boot 3.5.0 → 3.5.14 자동 적용, webauthn4j-test catalog alias; (5) 전체 ./gradlew check 회귀; (6) docs/dev-setup.md monorepo 경로로 갱신; (7) followup 문서 + cleanup 안내. Verify the overall design coherence — any leftover external-repo path reference, mavenLocal occurrence, or version drift? Run grep -rn 'mavenLocal\\|crosscert/sdk-java\\|crosscert/sample-rp' --include='*.gradle*' --include='*.md' worktree 전체 + git log --oneline -10. 250 words max.")
```

---

## Self-Review

**1. Spec coverage:**
- §1.2 DoD 8 항목 → Task 1 (rsync sdk-java), Task 2 (rsync sample-rp), Task 3 (settings include), Task 4 (sdk-java build), Task 5 (sample-rp build), Task 6 (check 회귀), Task 7 (dev-setup), Task 8 (followup + cleanup 안내)
- §1.3 의도 제외 4 → Task 8 followup deferred 4
- §2.1 rsync exclude → Task 1 / 2 step 1
- §2.2 settings → Task 3
- §2.3 sdk-java standalone artifact 제거 + build 단순화 → Task 1 step 2 + Task 4
- §2.4 sample-rp 동일 + dependency 전환 → Task 2 step 2 + Task 5
- §2.5 root build 변경 없음 → 어떤 task 도 root build.gradle.kts 안 건드림
- §3.1 테스트 → Task 4 step 5, Task 5 step 4, Task 6
- §3.2 dev-setup → Task 7
- §3.3 cleanup → Task 8 step 1 의 followup 문서
- §3.4 mavenLocal 의존자 grep → Task 5 step 6 codex prompt 에 포함
- §3.5 위험 → 각 task 의 codex review
- §3.6 followup → Task 8

**2. Placeholder scan:** No "TBD" / "implement later" / "Similar to Task N". 각 code block 완전. 패스.

**3. Type consistency:**
- `project(":sdk-java")`: Task 3 (settings), Task 5 (sample-rp 의존) 일치
- `libs.plugins.spring.boot`, `libs.plugins.spring.dep.mgmt`, `libs.webauthn4j.test`, `libs.nimbus.jose.jwt`, `libs.wiremock.standalone` — root catalog 의 실제 alias 와 일치 (gradle/libs.versions.toml 확인됨)
- `tenant_id` hex (`7F00DEAD00000000000000000ACE0001` 등) — Task 7 dev-setup 의 표와 dev profile phase 의 R__dev_seed.sql 일치
- 모듈 이름 `:sdk-java`, `:sample-rp` 일관성

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-05-28-monorepo-merge.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
