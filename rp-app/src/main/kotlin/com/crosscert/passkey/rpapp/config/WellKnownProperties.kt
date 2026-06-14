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
 *
 * 원본 Java 는 record 였다. Spring Boot 는 @Bean factory-method 로 제공된
 * @ConfigurationProperties 를 무조건 JAVA_BEAN 으로 바인딩하는데(ConfigurationPropertiesBean.get),
 * record 의 접근자는 `android()` 라 JavaBeanBinder(getXxx/isXxx 만 인식)가 프로퍼티로 보지 않아
 * 외부 yml 이 덮어쓰지 못한다(생성자 값 유지). 일반 Kotlin data class 는 getXxx 를 만들어
 * val 이면 "No setter" 로 컨텍스트 로드 실패, var 면 yml 이 fixture @Bean 값을 덮어쓴다.
 * 따라서 @JvmRecord 로 실제 Java record 를 emit 해 원본 동작을 정확히 재현한다.
 */
@JvmRecord
@ConfigurationProperties(prefix = "rp-app.well-known")
data class WellKnownProperties(
    val android: List<AndroidApp>?,
    val ios: Ios?,
) {
    @JvmRecord
    data class AndroidApp(
        val packageName: String,
        val sha256Fingerprints: List<String>?,
    )

    @JvmRecord
    data class Ios(
        val appIds: List<String>?,
    )
}
