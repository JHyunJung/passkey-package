package com.crosscert.passkey.rpapp.common.exception

class BusinessException : RuntimeException {
    val errorCode: ErrorCode

    constructor(errorCode: ErrorCode) : super(errorCode.message) {
        this.errorCode = errorCode
    }

    constructor(errorCode: ErrorCode, detail: String?) : super(detail) {
        this.errorCode = errorCode
    }
}
