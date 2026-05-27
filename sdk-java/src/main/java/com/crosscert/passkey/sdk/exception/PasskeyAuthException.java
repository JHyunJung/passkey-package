package com.crosscert.passkey.sdk.exception;

import com.crosscert.passkey.sdk.envelope.EnvelopeFieldError;

import java.util.List;

public class PasskeyAuthException extends PasskeyApiException {
    public PasskeyAuthException(String code, String message, String traceId,
                                List<EnvelopeFieldError> fieldErrors) {
        super(401, code, message, traceId, fieldErrors);
    }
}
