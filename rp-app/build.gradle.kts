plugins {
    java
    alias(libs.plugins.spring.boot)
}

// group / version / toolchain / repositories / dependency-management(BOM) 모두 root allprojects + subprojects 가 처리.

dependencies {
    implementation(project(":sdk-java"))
    // Boot 4: spring-boot-starter-web → spring-boot-starter-webmvc (모듈화 스타터).
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Jackson 2 를 영구 유지한다(Boot 4 기본은 Jackson 3). sdk-java 가 공개 API 로 Jackson 2
    // JsonNode 를 돌려주고 rp-app 이 그것을 응답에 그대로 싣기 때문에, MVC JSON 컨버터와
    // ObjectMapper 빈은 Jackson 2 여야 한다(application.yml 의 preferred-json-mapper 참고).
    implementation("org.springframework.boot:spring-boot-jackson2")
    implementation("org.springframework.session:spring-session-core")

    implementation(rootProject.libs.logstash.logback.encoder)
    runtimeOnly("org.codehaus.janino:janino:3.1.12")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Boot 4: @WebMvcTest/@AutoConfigureMockMvc 는 webmvc-test 모듈, 보안 MockMvc 연동은 security-test 스타터.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
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
