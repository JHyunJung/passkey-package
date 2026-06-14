package com.crosscert.passkey.sdk.exception

import com.crosscert.passkey.sdk.envelope.EnvelopeFieldError

class PasskeyAuthException(
    code: String?,
    message: String?,
    traceId: String?,
    fieldErrors: List<EnvelopeFieldError>?,
) : PasskeyApiException(401, code, message, traceId, fieldErrors)
