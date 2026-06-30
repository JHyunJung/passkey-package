package com.crosscert.passkey.rpapp.common.exception;

/** 도메인 규칙 위반을 나타내는 예외. {@link ErrorCode} 를 실어 GlobalExceptionHandler 가 일관된 응답으로 변환한다. */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
