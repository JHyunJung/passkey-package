package com.crosscert.passkey.admin.web.format

import java.time.LocalDate

private const val BASE = "passkey-admin-recovery-codes"

/** 안전 문자만 유지, 나머지는 대시. */
private val UNSAFE = Regex("[^a-z0-9._-]+")

/** 연속 대시 축약. */
private val DASHES = Regex("-+")

/** 양끝 대시 제거. */
private val EDGE_DASHES = Regex("^-+|-+$")

/**
 * 복구 코드 다운로드 파일명(← admin-ui lib/recoveryCodeFilename.ts).
 * `passkey-admin-recovery-codes-<account>-<YYYYMMDD>.txt`. 화면에 값으로 내려주고 브라우저 Blob 다운로드가 쓴다.
 * account 는 email 의 @ 앞 local part 를 파일명-안전하게 정리한 것으로, 비면 생략한다.
 */
object RecoveryCodeFilename {

    fun of(email: String?, kstDate: LocalDate): String {
        val stamp = String.format("%04d%02d%02d", kstDate.year, kstDate.monthValue, kstDate.dayOfMonth)
        val account = sanitizeAccount(email)
        return if (account.isEmpty()) "$BASE-$stamp.txt" else "$BASE-$account-$stamp.txt"
    }

    private fun sanitizeAccount(email: String?): String {
        if (email == null || email.isEmpty()) return ""
        val at = email.indexOf('@')
        val local = if (at < 0) email else email.substring(0, at)
        // Regex.replace 는 Java String.replaceAll 과 같은 Matcher.replaceAll 이다.
        return local.lowercase()
            .replace(UNSAFE, "-")
            .replace(DASHES, "-")
            .replace(EDGE_DASHES, "")
    }
}
