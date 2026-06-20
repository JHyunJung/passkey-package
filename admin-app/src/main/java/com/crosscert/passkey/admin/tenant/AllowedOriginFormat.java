package com.crosscert.passkey.admin.tenant;

import java.util.regex.Pattern;

/**
 * tenant_allowed_origin.origin 의 허용 형식. DB CHECK(ck_tao_origin_format)와
 * 1:1 대응한다.
 *  - web:     https?://host[:port]
 *  - android: android:apk-key-hash:<43자 base64url(no padding)>
 * 형식 위반을 DB ORA 에러 전에 앱 레벨에서 잡아 명확한 400 을 준다.
 */
public final class AllowedOriginFormat {

    private static final Pattern WEB =
            Pattern.compile("^https?://[A-Za-z0-9][A-Za-z0-9.-]*(:[1-9][0-9]{0,4})?$");

    private static final Pattern ANDROID =
            Pattern.compile("^android:apk-key-hash:[A-Za-z0-9_-]{43}$");

    private AllowedOriginFormat() {}

    public static boolean isValid(String origin) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        return WEB.matcher(origin).matches() || ANDROID.matcher(origin).matches();
    }
}
