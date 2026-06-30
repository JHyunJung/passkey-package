package com.crosscert.passkey.rpapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.TimeZone;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RpAppApplication {

    public static void main(String[] args) {
        // 배포 JVM 의 TZ 설정에 의존하지 않도록 기본 타임존을 KST 로 고정한다.
        // rp-app 은 :core 비의존이라 KstTime 대신 문자열 상수를 직접 쓴다.
        // runApplication 이전에 호출해야 모든 빈이 KST 로 초기화된다.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(RpAppApplication.class, args);
    }
}
