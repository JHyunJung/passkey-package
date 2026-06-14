package com.crosscert.passkey.sdk.exception

class PasskeyIdTokenException : RuntimeException {
    constructor(message: String?) : super(message)
    constructor(message: String?, cause: Throwable?) : super(message, cause)
}
