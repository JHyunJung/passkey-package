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
        // 을 유지한다. 나머지 모듈은 Spring Boot 4 BOM — Testcontainers(2.x)·Flyway·Oracle JDBC·
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

    tasks.withType<Test> {
        useJUnitPlatform()
        // Spring Data introspects UUID fields via reflection when building
        // JPA mapping metadata for UUID-PK repositories (e.g. TenantRepository,
        // SchedulerLeaseRepository). Java 17 module system blocks access to
        // java.util.UUID.mostSigBits/leastSigBits without this flag.
        jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
    }
}
