plugins {
    `java-library`
}

dependencies {
    api("org.springframework.boot:spring-boot-starter")
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-starter-data-redis")
    api("org.springframework.session:spring-session-data-redis")
    // Required at RUNTIME by Lettuce pooling (application-common.yml enables it).
    // spring-boot-starter-data-redis bundles Lettuce but NOT commons-pool2.
    // implementation (not api) — app code does not compile against pool2 types.
    implementation("org.apache.commons:commons-pool2")
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("io.micrometer:micrometer-registry-prometheus")
    api("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-aop")

    api(rootProject.libs.oracle.jdbc)
    api(rootProject.libs.flyway.core)
    api(rootProject.libs.flyway.oracle)

    api(rootProject.libs.webauthn4j.core)
    api(rootProject.libs.nimbus.jose.jwt)
    api(rootProject.libs.spring.security.crypto)
    // GlobalExceptionHandler handles AccessDeniedException / AuthenticationException
    // from spring-security-core; the individual apps add the full security starter
    // on top, but core must be able to compile the advice without them.
    api("org.springframework.security:spring-security-core")
    api(rootProject.libs.bucket4j.core)
    api(rootProject.libs.bucket4j.redis)

    testImplementation(rootProject.libs.testcontainers.oracle)
    testImplementation(rootProject.libs.testcontainers.junit)
    testImplementation(rootProject.libs.webauthn4j.test)
    // Ed25519Signer (used by LicenseTestFixtures) requires Google Tink at runtime.
    // Tink is an optional dependency of nimbus-jose-jwt — add it explicitly for tests.
    testImplementation(rootProject.libs.tink)
}

tasks.named<Test>("test") {
    // Testcontainers' shaded docker-java falls back to Docker API v1.32
    // when no api.version is configured, but Docker Engine v25+
    // (Docker Desktop 4.30+) advertises MinAPIVersion=1.40 and rejects
    // older calls with HTTP 400. We pin a modern API version so the
    // Oracle Testcontainer boot does not fail on the first /info call.
    // See: https://github.com/testcontainers/testcontainers-java/issues/9434
    systemProperty("api.version", "1.43")
}

// Copy scripts/bootstrap-vpd.sql onto the test classpath so VpdIsolationIT
// can read it as a classpath resource and ship it into the Testcontainers
// Oracle via MountableFile. Sourcing from scripts/ (the single source of
// truth used by docker-compose) prevents drift between local dev and CI.
tasks.named<Copy>("processTestResources") {
    from(rootProject.file("scripts/bootstrap-vpd.sql"))
}
