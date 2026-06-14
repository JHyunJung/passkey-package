package com.crosscert.passkey.rpapp.web;

import com.crosscert.passkey.rpapp.config.WellKnownProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 네이티브 앱 패스키용 Well-Known URI 호스팅. OS/CDN(Google Play services, Apple CDN)
 * 이 직접 가져가 "도메인 → 앱" 소유를 검증하는 표준 wire format 이므로, JwksController
 * 처럼 ApiResponse envelope 없이 표준 JSON 을 직접 반환한다.
 *
 * 주의: apple-app-site-association 은 확장자가 없어 Spring 이 Content-Type 을
 * 추측하지 못한다(octet-stream 위험). produces = APPLICATION_JSON_VALUE 로 강제한다.
 * 검증 에이전트는 3xx/비-json 을 거부하므로 컨트롤러 직접 매핑으로 정확한 경로만 200 을 낸다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class WellKnownController {

    /** 패스키 자동완성을 위한 표준 relation 조합 — 고객사가 바꿀 일이 거의 없어 상수로 고정. */
    private static final List<String> RELATIONS = List.of(
            "delegate_permission/common.handle_all_urls",
            "delegate_permission/common.get_login_creds");

    private final WellKnownProperties props;

    @GetMapping(value = "/.well-known/assetlinks.json",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> assetLinks() {
        List<Map<String, Object>> statements = props.android().stream()
                .map(app -> Map.<String, Object>of(
                        "relation", RELATIONS,
                        "target", Map.of(
                                "namespace", "android_app",
                                "package_name", app.packageName(),
                                "sha256_cert_fingerprints", app.sha256Fingerprints())))
                .toList();
        if (statements.isEmpty()) {
            log.warn("assetlinks.json: Android 앱이 설정되지 않았습니다 — Android 패스키가 동작하지 않습니다 (rp-app.well-known.android 확인)");
        } else if (log.isDebugEnabled()) {
            log.debug("assetlinks served: androidApps={}", statements.size());
        }
        return statements;
    }

    @GetMapping(value = "/.well-known/apple-app-site-association",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> appleAppSiteAssociation() {
        List<String> apps = props.ios().appIds();
        if (apps.isEmpty()) {
            log.warn("apple-app-site-association: iOS 앱이 설정되지 않았습니다 — iOS 패스키가 동작하지 않습니다 (rp-app.well-known.ios.app-ids 확인)");
        } else if (log.isDebugEnabled()) {
            log.debug("aasa served: iosApps={}", apps.size());
        }
        return Map.of("webcredentials", Map.of("apps", apps));
    }
}
