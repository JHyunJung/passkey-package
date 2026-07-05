package com.crosscert.passkey.rpapp.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 등록 시작 요청 본문. {@code POST /passkey/register/begin} 에서 받는다.
 *
 * <p>이 엔드포인트는 인증 전(pre-auth)에 호출되므로, 상한 없는 문자열은
 * InMemoryUserStore 의 pending 무한누적 DoS 를 증폭시킬 수 있다. 고객사
 * 인증 시스템(passkey-app RegistrationStartRequest)과 동일하게 @Size(max=256) 으로
 * 조기 거부한다.
 */
public record RegisterStartReq(
        @NotBlank @Size(max = 256) String username,
        @NotBlank @Size(max = 256) String displayName
) {}
