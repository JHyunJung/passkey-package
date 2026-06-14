package com.crosscert.passkey.rpapp.common.response

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ErrorDetail(val errorCode: String?, val fieldErrors: List<FieldError>?)
