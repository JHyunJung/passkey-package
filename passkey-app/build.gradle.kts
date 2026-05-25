plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":core"))
}

springBoot {
    mainClass.set("com.crosscert.passkey.app.PasskeyApplication")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("passkey-app.jar")
}
