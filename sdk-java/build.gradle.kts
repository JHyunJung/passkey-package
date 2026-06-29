plugins {
    `java-library`
    `maven-publish`
}

// group / version 은 root allprojects 가 com.crosscert.passkey / 0.0.1-SNAPSHOT 로 설정.
// toolchain 17 + repositories(mavenCentral) 은 root subprojects 가 처리.

java {
    withSourcesJar()
}

dependencies {
    // Spring Boot 3.5 BOM (root subprojects 에서 import) 이 spring-web 6.2.x,
    // jackson-databind, jackson-jsr310, slf4j-api 를 모두 관리.
    api("org.springframework:spring-web")
    api("com.fasterxml.jackson.core:jackson-databind")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    api(rootProject.libs.nimbus.jose.jwt)
    api("org.slf4j:slf4j-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation(rootProject.libs.wiremock.standalone)
    // junit-platform-launcher 는 root subprojects 가 모든 모듈에 testRuntimeOnly 적용
}

tasks.named<Test>("test") { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("maven") { from(components["java"]) }
    }
}
