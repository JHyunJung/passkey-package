package com.crosscert.passkey.sdk.exception

import com.crosscert.passkey.sdk.envelope.EnvelopeFieldError

open class PasskeyApiException(
    val httpStatus: Int,
    val code: String?,
    message: String?,
    val traceId: String?,
    fieldErrors: List<EnvelopeFieldError>?,
) : RuntimeException(message) {

    val fieldErrors: List<EnvelopeFieldError> =
        if (fieldErrors == null) emptyList() else java.util.List.copyOf(fieldErrors)
}
