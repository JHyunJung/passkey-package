package com.crosscert.passkey.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.crosscert.passkey")
public class PasskeyApplication {
    public static void main(String[] args) {
        SpringApplication.run(PasskeyApplication.class, args);
    }
}
