package com.crosscert.passkey.rpapp.web.dto

import com.fasterxml.jackson.databind.JsonNode

/**
 * authenticate/begin 응답. 무상태 릴레이를 위해 단명 authenticationToken 을 클라이언트에
 * 반환한다. 클라이언트는 authenticate/finish 요청에 이 토큰을 다시 실어 보낸다.
 */
data class LoginOptionsResp(
    val publicKeyCredentialRequestOptions: JsonNode?,
    val authenticationToken: String?,
)
