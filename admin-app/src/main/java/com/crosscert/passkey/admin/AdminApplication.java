package com.crosscert.passkey.admin;

import com.crosscert.passkey.core.config.KstTime;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.TimeZone;

// scanBasePackages picks up beans from :core. @EntityScan and
// @EnableJpaRepositories are required separately because Spring Data JPA
// auto-configuration defaults to the @SpringBootApplication's own
// package — without explicit pointers, sibling-package entities and
// repositories in :core would not be discovered.
// @ConfigurationPropertiesScan registers @ConfigurationProperties records
// across all sub-packages of com.crosscert.passkey (including :core).
@SpringBootApplication(scanBasePackages = "com.crosscert.passkey")
@ConfigurationPropertiesScan("com.crosscert.passkey")
@EntityScan("com.crosscert.passkey.core.entity")
@EnableJpaRepositories("com.crosscert.passkey.core.repository")
@org.springframework.scheduling.annotation.EnableScheduling
@EnableAspectJAutoProxy
public class AdminApplication {
    public static void main(String[] args) {
        // 배포 JVM 의 TZ 설정에 의존하지 않도록 기본 타임존을 KST 로 고정한다.
        // raw-JDBC bridge 경로(mds_sync_history 등)는 JVM default TZ 로 wall-clock
        // 을 저장하므로, 이 한 줄이 그 경로까지 KST 로 정렬시킨다. SpringApplication.run
        // 이전에 호출해야 모든 빈/스케줄러가 KST 로 초기화된다.
        TimeZone.setDefault(TimeZone.getTimeZone(KstTime.ZONE));
        SpringApplication.run(AdminApplication.class, args);
    }
}
