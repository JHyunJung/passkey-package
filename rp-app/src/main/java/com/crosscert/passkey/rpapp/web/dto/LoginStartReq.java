package com.crosscert.passkey.rpapp.web.dto;

/** 인증 시작 요청 본문. {@code POST /passkey/authenticate/begin} 에서 받는다. username 이 없으면 discoverable(사용자 선택) 로그인. */
public record LoginStartReq(String username) {}
