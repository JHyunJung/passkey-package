package com.crosscert.passkey.rpapp.common.response

data class FieldError(val field: String?, val rejectedValue: Any?, val reason: String?)
