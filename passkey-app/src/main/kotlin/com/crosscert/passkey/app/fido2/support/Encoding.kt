package com.crosscert.passkey.app.fido2.support

import java.util.Base64

/** base64url(RFC 4648 §5, 패딩 없음) — WebAuthn challenge·credential id·userHandle·토큰의 표기. */
internal fun ByteArray.toBase64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

/**
 * Last 12 chars of a base64url id for log correlation — never the full id.
 * Short ids (≤12 chars) are masked to "***" so the full value is never logged.
 */
internal fun String.idTail(): String = if (length <= 12) "***" else "..." + takeLast(12)

/** Returns the last 8 chars of a token for correlation only — never the full secret. */
internal fun String.tokenTail(): String = if (length <= 8) "***" else takeLast(8)
