import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

plugins {
    java
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dep.mgmt) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
}

allprojects {
    group = "com.crosscert.passkey"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    extensions.configure<DependencyManagementExtension> {
        // sdk-java 는 외부 RP 에 배포되는 라이브러리라 Spring Boot 3.5 BOM(Spring 6 / Jackson 2)
        // 을 유지한다. 나머지 모듈은 Spring Boot 4 BOM — Testcontainers(2.x)·Oracle JDBC·
        // Jackson(2.21.x + 3.x) 버전까지 이 BOM 이 관리하므로 별도 BOM import 나 버전 강제가 없다.
        // (구 jackson-annotations:2.21 강제는 webauthn4j 의 Jackson 3 가 2.20+ 어노테이션을
        //  요구해서였는데, Boot 4 BOM 의 jackson-bom 2.21.x 가 이미 2.21 을 관리한다.)
        val bootVersion = if (project.name == "sdk-java") {
            rootProject.libs.versions.spring.boot.sdk.get()
        } else {
            rootProject.libs.versions.spring.boot.asProvider().get()
        }
        imports {
            if (project.name == "sdk-java") {
                mavenBom("org.springframework.boot:spring-boot-dependencies:$bootVersion")
            } else {
                // Boot 4 BOM 의 kotlin.version 을 Kotlin 컴파일러(카탈로그 kotlin)와 같게 덮어써
                // kotlin-stdlib/kotlin-reflect 가 컴파일러보다 낮은 버전으로 내려가지 않게 한다.
                mavenBom("org.springframework.boot:spring-boot-dependencies:$bootVersion") {
                    bomProperty("kotlin.version", rootProject.libs.versions.kotlin.get())
                }
            }
        }
    }

    dependencies {
        // Lombok is a compile-time annotation processor only; it is NOT on the
        // runtime classpath (compileOnly), so production artifacts carry zero
        // Lombok dependency. Used solely for @Slf4j and @RequiredArgsConstructor.
        "compileOnly"(rootProject.libs.lombok)
        "annotationProcessor"(rootProject.libs.lombok)
        "testCompileOnly"(rootProject.libs.lombok)
        "testAnnotationProcessor"(rootProject.libs.lombok)

        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        // Align junit-platform-launcher with the BOM-managed junit-platform-engine
        // (Boot 4.1 BOM: JUnit 6.0.x, sdk-java 의 Boot 3.5 BOM: 1.12.x). Gradle 9 는 테스트
        // 런타임에 launcher 를 자동 주입하지 않으므로 명시 선언이 필수이며, 버전은 BOM 이 맞춘다.
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    // Oracle 통합 테스트가 쓰는 DB 초기화 스크립트(test-db-init.sql)를 테스트 클래스패스에 만든다.
    // 배포에 쓰는 scripts/ 의 SQL 을 그대로 이어 붙이므로 테스트가 곧 그 스크립트의 검증이다.
    //   1) bootstrap-schema.sql          — SYS 로 롤·계정 생성
    //   2) full-schema-part2-schema.sql  — PSK_APP_OWNER 로 다시 접속해 스키마 전체 생성
    //   3) src/test/db/test-seed.sql     — 모듈에 있을 때만(테스트 전용 시드)
    // 각 IT 는 이 파일 하나를 컨테이너에 복사해 sqlplus(SYS)로 실행한다. 접속 정보는 IT 들이
    // 공통으로 쓰는 값(XEPDB1, PSK_APP_OWNER 비밀번호 app_owner_pw)과 같아야 한다.
    if (project.name in setOf("core", "admin-app", "passkey-app")) {
        val dbInitParts = listOf(
            rootProject.file("scripts/bootstrap-schema.sql"),
            rootProject.file("scripts/full-schema-part2-schema.sql"),
            project.file("src/test/db/test-seed.sql"),
        ).filter { it.exists() }
        tasks.named<Copy>("processTestResources") {
            inputs.files(dbInitParts)
            val outFile = destinationDir.resolve("test-db-init.sql")
            doLast {
                // 각 스크립트 끝의 EXIT 는 떼고(다음 스크립트가 실행되지 않으므로) 맨 끝에 한 번만 둔다.
                val exitLine = Regex("(?im)^EXIT;?[ \\t]*$")
                val bodies = dbInitParts.map { exitLine.replace(it.readText(), "") }
                outFile.writeText(buildString {
                    appendLine("DEFINE ora_service = XEPDB1")
                    appendLine(bodies[0])
                    appendLine("CONNECT PSK_APP_OWNER/app_owner_pw@localhost:1521/XEPDB1")
                    bodies.drop(1).forEach { appendLine(it) }
                    appendLine("EXIT;")
                })
            }
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // Spring Data introspects UUID fields via reflection when building
        // JPA mapping metadata for UUID-PK repositories (e.g. TenantRepository,
        // SchedulerLeaseRepository). Java 17 module system blocks access to
        // java.util.UUID.mostSigBits/leastSigBits without this flag.
        jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
    }
}
