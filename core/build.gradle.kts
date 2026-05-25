plugins {
    `java-library`
}

dependencies {
    api("org.springframework.boot:spring-boot-starter")
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-starter-data-redis")
    // Required at RUNTIME by Lettuce pooling (application-common.yml enables it).
    // spring-boot-starter-data-redis bundles Lettuce but NOT commons-pool2.
    // implementation (not api) — app code does not compile against pool2 types.
    implementation("org.apache.commons:commons-pool2")
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("org.springframework.boot:spring-boot-starter-validation")

    api(rootProject.libs.oracle.jdbc)
    api(rootProject.libs.flyway.core)
    api(rootProject.libs.flyway.oracle)

    testImplementation(rootProject.libs.testcontainers.oracle)
    testImplementation(rootProject.libs.testcontainers.junit)
}
