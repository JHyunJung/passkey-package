plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

// group / version / toolchain / repositories 모두 root allprojects + subprojects 가 처리.

dependencies {
    implementation(project(":sdk-java"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.session:spring-session-core")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    // SampleRpSmokeIT 용 webauthn4j-test (ClientPlatform + PackedAuthenticator).
    testImplementation(rootProject.libs.webauthn4j.test)
    // junit-platform-launcher 는 root subprojects 가 자동 적용
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // Java 17 UUID reflection access (passkey-app 와 동일 패턴)
    jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
}
