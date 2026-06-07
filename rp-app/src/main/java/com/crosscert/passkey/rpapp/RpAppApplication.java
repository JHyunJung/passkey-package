package com.crosscert.passkey.rpapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RpAppApplication {
    public static void main(String[] args) {
        SpringApplication.run(RpAppApplication.class, args);
    }
}
