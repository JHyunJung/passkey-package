plugins {
    `java-library`
}

dependencies {
    api("org.springframework.boot:spring-boot-starter")
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-starter-data-redis")
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("org.springframework.boot:spring-boot-starter-validation")

    api(rootProject.libs.oracle.jdbc)
    api(rootProject.libs.flyway.core)
    api(rootProject.libs.flyway.oracle)

    testImplementation(rootProject.libs.testcontainers.oracle)
    testImplementation(rootProject.libs.testcontainers.junit)
}
