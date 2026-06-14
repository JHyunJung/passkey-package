plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

// group / version / toolchain / repositories 모두 root allprojects + subprojects 가 처리.

dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation(kotlin("stdlib"))
    implementation(project(":sdk-java"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.session:spring-session-core")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    // RpAppSmokeIT 용 webauthn4j-test (ClientPlatform + PackedAuthenticator).
    testImplementation(rootProject.libs.webauthn4j.test)
    // junit-platform-launcher 는 root subprojects 가 자동 적용
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("rp-app.jar")
    // 실행 가능한 jar 를 루트 deploy/ 에 모아 배포 편의를 높인다.
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("deploy"))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // Java 17 UUID reflection access (passkey-app 와 동일 패턴)
    jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
}
