package com.crosscert.passkey.sdk.exception

import com.crosscert.passkey.sdk.envelope.EnvelopeFieldError

class PasskeyRateLimitException(
    code: String?,
    message: String?,
    traceId: String?,
    fieldErrors: List<EnvelopeFieldError>?,
    @get:JvmName("retryAfterSeconds") val retryAfterSeconds: Long,
) : PasskeyApiException(429, code, message, traceId, fieldErrors)
