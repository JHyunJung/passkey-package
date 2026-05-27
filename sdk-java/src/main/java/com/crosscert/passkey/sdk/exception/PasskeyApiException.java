package com.crosscert.passkey.sdk.exception;

import com.crosscert.passkey.sdk.envelope.EnvelopeFieldError;

import java.util.List;

public class PasskeyApiException extends RuntimeException {
    private final int httpStatus;
    private final String code;
    private final String traceId;
    private final List<EnvelopeFieldError> fieldErrors;

    public PasskeyApiException(int httpStatus, String code, String message,
                               String traceId, List<EnvelopeFieldError> fieldErrors) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
        this.traceId = traceId;
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public int getHttpStatus() { return httpStatus; }
    public String getCode() { return code; }
    public String getTraceId() { return traceId; }
    public List<EnvelopeFieldError> getFieldErrors() { return fieldErrors; }
}
