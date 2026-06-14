plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    // kotlin-spring(allopen) 미적용: sdk-java 에 Spring 빈(@Configuration/@Bean/@Component/@Service)
    // 이 전혀 없으므로 라이브러리에 불필요한 allopen 을 넣지 않는다. (kotlin-jvm 만)
}

// group / version 은 root allprojects 가 com.crosscert.passkey / 0.0.1-SNAPSHOT
// 로 설정. 옛 standalone 의 0.1.0-SNAPSHOT 은 monorepo 표준으로 통일.
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
    // jackson-module-kotlin: 공개 DTO 역직렬화가 소비자 쪽에서 일어날 수 있어 api 로 노출
    // (jackson-databind 도 api). Kotlin data class 의 무인자 생성자/널 안정성 매핑 지원.
    api("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation(kotlin("stdlib"))

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

// Kotlin 생성자 파라미터명 보존(-java-parameters). jackson 역직렬화/디버깅에 도움.
// (Phase1 rp-app 과 동일 패턴.)
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions { javaParameters.set(true) }
}
