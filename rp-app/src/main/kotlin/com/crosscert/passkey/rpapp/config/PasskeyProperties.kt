package com.crosscert.passkey.rpapp.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.nio.file.Path
import java.time.Duration

@ConfigurationProperties(prefix = "passkey")
data class PasskeyProperties(
    val baseUrl: URI?,
    val apiKey: String?,
    /**
     * 설정 시 이 파일에서 API Key 를 핫리로드한다(env 보다 우선). 미설정이면
     * [apiKey] 를 폴백으로 쓴다(기존 동작 보존). 파일 내용은 키 평문 한 줄.
     */
    val apiKeyFile: Path?,
    /** apiKeyFile mtime 폴링 주기. 기본 10s. */
    val apiKeyReload: Duration?,
    val tenantId: String?,
    /**
     * ID Token 의 `iss` claim 비교용 prefix. passkey-app 의 `IdTokenIssuer` 가
     * `passkey.id-token.issuer-base:https://passkey.crosscert.com` + "/" + tenantId 로 발급한다.
     * 로컬 데모에서는 passkey-app 의 `application-local.yml` 등에서 issuer-base 를
     * baseUrl 과 동일하게 override 하거나, 본 프로퍼티를 그 값과 맞춰야 한다.
     */
    val issuerBase: URI?,
    val connectTimeout: Duration?,
    val readTimeout: Duration?,
    val jwksCacheTtl: Duration?,
)
