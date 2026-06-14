package com.crosscert.passkey.rpapp.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 네이티브 앱 패스키용 Well-Known URI(assetlinks.json / apple-app-site-association)
 * 에 들어갈 앱 메타데이터. rp-app 를 RP 레퍼런스 구현으로 쓰는 고객사는 코드를
 * 고치지 않고 환경변수/yml override 로 자기 앱 값만 채운다.
 *
 * - android: assetlinks.json 의 target 배열. 앱마다 패키지명 + 서명 지문 리스트.
 *   디버그/릴리즈 서명이 다르거나 지문이 여러 개면 sha256Fingerprints 에 나열한다.
 * - ios: apple-app-site-association 의 webcredentials.apps. "TeamID.BundleID" 리스트.
 */
@ConfigurationProperties(prefix = "rp-app.well-known")
class WellKnownProperties(android: List<AndroidApp>?, ios: Ios?) {

    /** android/ios 미설정 시 NPE 대신 빈 리스트로 안전하게 동작. */
    val android: List<AndroidApp> = android ?: emptyList()
    val ios: Ios = ios ?: Ios(emptyList())

    class AndroidApp(val packageName: String, sha256Fingerprints: List<String>?) {
        val sha256Fingerprints: List<String> = sha256Fingerprints ?: emptyList()
    }

    class Ios(appIds: List<String>?) {
        val appIds: List<String> = appIds ?: emptyList()
    }
}
