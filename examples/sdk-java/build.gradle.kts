plugins {
    `java-library`
    `maven-publish`
}

group = "com.crosscert.passkey"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
    withSourcesJar()
}

repositories { mavenCentral() }

dependencies {
    // Spring Boot 3.5.x BOM 이 spring-web 6.2.x 를 가져오므로 동일 라인업으로 잠근다.
    api("org.springframework:spring-web:6.2.0")
    api("com.fasterxml.jackson.core:jackson-databind:2.21.0")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.21.0")
    api("com.nimbusds:nimbus-jose-jwt:9.40")
    api("org.slf4j:slf4j-api:2.0.16")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.wiremock:wiremock-standalone:3.10.0")
}

tasks.named<Test>("test") { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("maven") { from(components["java"]) }
    }
}
