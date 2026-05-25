package com.crosscert.passkey.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class CoreClockConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
