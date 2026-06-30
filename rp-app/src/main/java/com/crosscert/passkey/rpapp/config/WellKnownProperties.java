package com.crosscert.passkey.rpapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 네이티브 앱 패스키용 Well-Known URI(assetlinks.json / apple-app-site-association)
 * 에 들어갈 앱 메타데이터. rp-app 를 RP 레퍼런스 구현으로 쓰는 고객사는 코드를
 * 고치지 않고 환경변수/yml override 로 자기 앱 값만 채운다.
 *
 * <ul>
 *   <li>android: assetlinks.json 의 target 배열. 앱마다 패키지명 + 서명 지문 리스트.
 *       디버그/릴리즈 서명이 다르거나 지문이 여러 개면 sha256Fingerprints 에 나열한다.</li>
 *   <li>ios: apple-app-site-association 의 webcredentials.apps. "TeamID.BundleID" 리스트.</li>
 * </ul>
 *
 * 모든 @ConfigurationProperties 가 @ConfigurationPropertiesScan(VALUE_OBJECT 생성자
 * 바인딩) 으로만 등록되므로 Java record 로 외부 yml override 가 정상 동작한다.
 */
@ConfigurationProperties(prefix = "rp-app.well-known")
public record WellKnownProperties(
        List<AndroidApp> android,
        Ios ios
) {
    public record AndroidApp(
            String packageName,
            List<String> sha256Fingerprints
    ) {}

    public record Ios(
            List<String> appIds
    ) {}
}
