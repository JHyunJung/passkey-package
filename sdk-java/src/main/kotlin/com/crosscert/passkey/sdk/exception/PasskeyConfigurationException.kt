package com.crosscert.passkey.sdk.exception

/**
 * SDK 설정 오류 — 요청이 네트워크를 타기 전에 감지되는 클라이언트측 구성 문제.
 * 예: API key Supplier 가 null/blank 를 반환(키 소스 미설정/비워짐).
 *
 * wire 응답이 아니므로 [PasskeyApiException] 의 httpStatus/code/traceId
 * 계약에 들어맞지 않아 별도 타입으로 분리한다. unchecked — 호출부가 매 호출
 * try/catch 를 강요받지 않게 한다(잘못된 설정은 fail-fast 가 맞다).
 */
class PasskeyConfigurationException(message: String?) : RuntimeException(message)
