package com.crosscert.passkey.admin.web.format;

import java.net.IDN;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * origin 입력 검증(← WebauthnConfigTab.tsx validateOrigin). WebAuthn 스펙상 origin 의 host 는 rpId 와 같거나 rpId 의 서브도메인이어야 한다.
 * 반환: ok 이면 정규화된 origin(scheme://host[:port], 경로·쿼리 제거), 아니면 사유.
 */
public final class OriginValidation {

    private static final Pattern BASE64URL_43 = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern HAS_SCHEME = Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE);

    private OriginValidation() {}

    /** ok 이면 value, 아니면 error 가 채워진다. */
    public record Result(boolean ok, String value, String error) {}

    public static Result validate(String input, String rpId) {
        String raw = input == null ? "" : input.trim();
        if (raw.isEmpty()) return fail("origin 을 입력하세요.");

        // Android 네이티브 앱 origin: 도메인이 아니라 앱 서명키 해시라 rpId 범위 검사를 적용하지 않는다.
        if (ApkKeyHash.isApkKeyHashOrigin(raw)) {
            String body = raw.substring(ApkKeyHash.APK_KEY_HASH_PREFIX.length());
            if (BASE64URL_43.matcher(body).matches()) return new Result(true, raw, null);
            return fail("android:apk-key-hash 값은 43자 base64url 이어야 합니다.");
        }

        // 스킴이 없으면 https:// 를 가정해 파싱(host 만 입력한 경우 허용).
        String withScheme = HAS_SCHEME.matcher(raw).matches() ? raw : "https://" + raw;
        String format = "'" + raw + "' 는 올바른 origin 형식이 아닙니다. 예) https://" + rpId;
        URI uri;
        try {
            uri = new URI(withScheme);
        } catch (java.net.URISyntaxException e) {
            return fail(format);
        }
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || host.isEmpty() || port > 65535) return fail(format);
        try {
            host = IDN.toASCII(host).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return fail(format);
        }
        String base = rpId == null ? "" : rpId.toLowerCase(Locale.ROOT);
        boolean self = host.equals(base);
        boolean sub = host.endsWith("." + base);
        if (!self && !sub) {
            return fail("origin host '" + host + "' 는 rpId '" + rpId + "' 범위를 벗어납니다. rpId 자신이거나 그 서브도메인(예: sub." + rpId + ")만 추가할 수 있습니다.");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        boolean defaultPort = port == -1 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
        return new Result(true, scheme + "://" + host + (defaultPort ? "" : ":" + port), null);
    }

    private static Result fail(String error) {
        return new Result(false, null, error);
    }
}
