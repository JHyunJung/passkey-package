package com.crosscert.passkey.app

import com.crosscert.passkey.core.config.KstTime
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling
import java.util.TimeZone

// scanBasePackages picks up beans from :core. @EntityScan and
// @EnableJpaRepositories are required separately because Spring Data JPA
// auto-configuration defaults to the @SpringBootApplication's own
// package — without explicit pointers, sibling-package entities and
// repositories in :core would not be discovered.
// @ConfigurationPropertiesScan registers @ConfigurationProperties records
// across all sub-packages of com.crosscert.passkey (including :core).
@SpringBootApplication(scanBasePackages = ["com.crosscert.passkey"])
@ConfigurationPropertiesScan("com.crosscert.passkey")
@EntityScan("com.crosscert.passkey.core.entity")
@EnableJpaRepositories("com.crosscert.passkey.core.repository")
@EnableScheduling
@EnableAspectJAutoProxy
class PasskeyApplication

fun main(args: Array<String>) {
    // 배포 JVM 의 TZ 설정에 의존하지 않도록 기본 타임존을 KST 로 고정한다.
    // runApplication 이전에 호출해야 모든 빈/스케줄러가 KST 로 초기화된다.
    TimeZone.setDefault(TimeZone.getTimeZone(KstTime.ZONE))
    runApplication<PasskeyApplication>(*args) {
        // 기본 추론은 main 이 있는 PasskeyApplicationKt 를 잡아 시작 로그 카테고리·문구
        // ("Started PasskeyApplicationKt")가 바뀐다 — 원 Java 와 같은 클래스로 고정한다.
        setMainApplicationClass(PasskeyApplication::class.java)
    }
}
