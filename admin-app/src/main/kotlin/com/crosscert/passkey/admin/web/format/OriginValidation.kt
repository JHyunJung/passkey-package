package com.crosscert.passkey.admin.web.format

import java.net.IDN
import java.net.URI
import java.net.URISyntaxException
import java.util.regex.Pattern

private val BASE64URL_43: Pattern = Pattern.compile("^[A-Za-z0-9_-]{43}$")
private val HAS_SCHEME: Pattern = Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE)

/**
 * origin 입력 검증(← WebauthnConfigTab.tsx validateOrigin). WebAuthn 스펙상 origin 의 host 는 rpId 와 같거나 rpId 의 서브도메인이어야 한다.
 * 반환: ok 이면 정규화된 origin(scheme://host[:port], 경로·쿼리 제거), 아니면 사유.
 */
object OriginValidation {

    /** ok 이면 value, 아니면 error 가 채워진다. */
    data class Result(val ok: Boolean, val value: String?, val error: String?)

    fun validate(input: String?, rpId: String?): Result {
        val raw = input?.javaTrim() ?: ""
        if (raw.isEmpty()) return fail("origin 을 입력하세요.")

        // Android 네이티브 앱 origin: 도메인이 아니라 앱 서명키 해시라 rpId 범위 검사를 적용하지 않는다.
        if (ApkKeyHash.isApkKeyHashOrigin(raw)) {
            val body = raw.substring(ApkKeyHash.APK_KEY_HASH_PREFIX.length)
            if (BASE64URL_43.matcher(body).matches()) return Result(true, raw, null)
            return fail("android:apk-key-hash 값은 43자 base64url 이어야 합니다.")
        }

        // 스킴이 없으면 https:// 를 가정해 파싱(host 만 입력한 경우 허용).
        val withScheme = if (HAS_SCHEME.matcher(raw).matches()) raw else "https://$raw"
        val format = "'$raw' 는 올바른 origin 형식이 아닙니다. 예) https://$rpId"
        val uri = try {
            URI(withScheme)
        } catch (e: URISyntaxException) {
            return fail(format)
        }
        var host: String? = uri.host
        val port = uri.port
        if (host == null || host.isEmpty() || port > 65535) return fail(format)
        host = try {
            IDN.toASCII(host).lowercase()
        } catch (e: IllegalArgumentException) {
            return fail(format)
        }
        val base = rpId?.lowercase() ?: ""
        val self = host == base
        val sub = host.endsWith(".$base")
        if (!self && !sub) {
            return fail("origin host '$host' 는 rpId '$rpId' 범위를 벗어납니다. rpId 자신이거나 그 서브도메인(예: sub.$rpId)만 추가할 수 있습니다.")
        }
        // 스킴은 항상 있다(HAS_SCHEME 이거나 https:// 를 붙였다).
        val scheme = uri.scheme.lowercase()
        val defaultPort = port == -1 || ("https" == scheme && port == 443) || ("http" == scheme && port == 80)
        return Result(true, scheme + "://" + host + (if (defaultPort) "" else ":$port"), null)
    }

    private fun fail(error: String): Result = Result(false, null, error)
}
