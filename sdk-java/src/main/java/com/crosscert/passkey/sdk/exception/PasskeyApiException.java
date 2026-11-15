package com.crosscert.passkey.sdk.exception;

import com.crosscert.passkey.sdk.envelope.EnvelopeFieldError;
import java.util.List;

/**
 * Passkey 서버 호출 실패. {@code httpStatus} 는 응답 HTTP 상태이고, HTTP 200 인데 응답 봉투가
 * success=false 면 200, 성공 응답 본문을 해석하지 못하면 0 이다. {@code code} 는 서버 응답 봉투의
 * 오류 코드로, 봉투가 없는 응답(본문 없음, problem+json 등)이면 {@code "C999"} 이다.
 * {@code traceId} 는 서버 응답의 traceId(없으면 null)로 서버 측 로그와 맞춰 볼 때 쓴다.
 * {@code fieldErrors} 는 null 이 아니다(없으면 빈 목록).
 */
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
        this.fieldErrors = (fieldErrors == null) ? List.of() : List.copyOf(fieldErrors);
    }

    public int getHttpStatus() { return httpStatus; }
    public String getCode() { return code; }
    public String getTraceId() { return traceId; }
    public List<EnvelopeFieldError> getFieldErrors() { return fieldErrors; }
}
