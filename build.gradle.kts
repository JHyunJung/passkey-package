import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

plugins {
    java
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dep.mgmt) apply false
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
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:${rootProject.libs.versions.spring.boot.get()}")
            mavenBom("org.testcontainers:testcontainers-bom:${rootProject.libs.versions.testcontainers.get()}")
        }
        // webauthn4j 0.31.5 pulls in tools.jackson.core:jackson-databind:3.1.2
        // (the new Jackson 3 lineage). That JAR references annotations such
        // as JsonSerializeAs that only exist in jackson-annotations 2.20+.
        // Spring Boot's BOM pins 2.19.x, which causes a
        // NoClassDefFoundError at WebAuthnManager construction time.
        // Override the Boot-managed version forward; 2.x annotations
        // are backwards-compatible.
        dependencies {
            dependency("com.fasterxml.jackson.core:jackson-annotations:2.21")
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
        // Align junit-platform-launcher with junit-platform-engine 1.12.x (Spring Boot 3.5 BOM).
        // Gradle 8.10 bundles an older launcher, causing
        // "OutputDirectoryProvider not available" at test discovery time.
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
