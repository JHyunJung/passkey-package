package com.crosscert.passkey.admin.web.format;

import java.time.LocalDate;
import java.util.Locale;

/**
 * 복구 코드 다운로드 파일명(← admin-ui lib/recoveryCodeFilename.ts).
 * {@code passkey-admin-recovery-codes-<account>-<YYYYMMDD>.txt}. 화면에 값으로 내려주고 브라우저 Blob 다운로드가 쓴다.
 * account 는 email 의 @ 앞 local part 를 파일명-안전하게 정리한 것으로, 비면 생략한다.
 */
public final class RecoveryCodeFilename {

    private static final String BASE = "passkey-admin-recovery-codes";

    private RecoveryCodeFilename() {}

    public static String of(String email, LocalDate kstDate) {
        String stamp = String.format("%04d%02d%02d", kstDate.getYear(), kstDate.getMonthValue(), kstDate.getDayOfMonth());
        String account = sanitizeAccount(email);
        return account.isEmpty() ? BASE + "-" + stamp + ".txt" : BASE + "-" + account + "-" + stamp + ".txt";
    }

    private static String sanitizeAccount(String email) {
        if (email == null || email.isEmpty()) return "";
        int at = email.indexOf('@');
        String local = at < 0 ? email : email.substring(0, at);
        return local.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")  // 안전 문자만 유지, 나머지는 대시
                .replaceAll("-+", "-")             // 연속 대시 축약
                .replaceAll("^-+|-+$", "");        // 양끝 대시 제거
    }
}
