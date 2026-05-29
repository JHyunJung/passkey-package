package com.crosscert.passkey.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

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
@EnableScheduling
@EnableAspectJAutoProxy
public class PasskeyApplication {
    public static void main(String[] args) {
        SpringApplication.run(PasskeyApplication.class, args);
    }
}
