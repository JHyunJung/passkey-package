package com.crosscert.passkey.sdk.exception;

import com.crosscert.passkey.sdk.envelope.EnvelopeFieldError;
import java.util.List;

/** 서버가 401 을 돌려줬을 때. 보통 X-API-Key 가 없거나 틀린 경우다. */
public class PasskeyAuthException extends PasskeyApiException {
    public PasskeyAuthException(String code, String message, String traceId,
                               List<EnvelopeFieldError> fieldErrors) {
        super(401, code, message, traceId, fieldErrors);
    }
}
