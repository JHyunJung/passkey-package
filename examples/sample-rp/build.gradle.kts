plugins {
    java
    id("org.springframework.boot") version "3.5.0"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.crosscert.passkey"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

repositories {
    mavenLocal()        // sdk-java 픽업
    mavenCentral()
}

dependencies {
    implementation("com.crosscert.passkey:passkey-sdk-java:0.1.0-SNAPSHOT")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.session:spring-session-core")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    // SampleRpSmokeIT 용 webauthn4j-test (ClientPlatform + PackedAuthenticator)
    testImplementation("com.webauthn4j:webauthn4j-test:0.29.4.RELEASE")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // Java 17 UUID reflection access (passkey-app 와 동일 패턴)
    jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
}
