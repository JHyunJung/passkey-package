package com.crosscert.passkey.rpapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 네이티브 앱 패스키용 Well-Known URI(assetlinks.json / apple-app-site-association)에 들어갈 앱 메타데이터.
 * 고객사는 코드를 고치지 않고 {@code rp-app.well-known.*} 환경변수/yml 로 자사 앱 값만 채운다.
 *
 * <ul>
 *   <li>android: assetlinks.json 의 target 배열. 앱마다 패키지명 + 서명 지문 목록(디버그/릴리즈 지문이 여러 개면 나열).</li>
 *   <li>ios: apple-app-site-association 의 webcredentials.apps. "TeamID.BundleID" 목록.</li>
 * </ul>
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
