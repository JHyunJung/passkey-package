package com.crosscert.passkey.rpapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.TimeZone;

/** rp-app 진입점. RP 데모 서버를 기동한다(패스키 등록/인증 릴레이 + ID Token 검증 + 네이티브 앱 well-known 호스팅). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RpAppApplication {

    public static void main(String[] args) {
        // 배포 JVM 의 타임존 설정에 의존하지 않도록 기본 타임존을 KST(Asia/Seoul) 로 고정한다.
        // SpringApplication.run 이전에 호출해야 모든 빈이 KST 기준으로 초기화된다.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(RpAppApplication.class, args);
    }
}
