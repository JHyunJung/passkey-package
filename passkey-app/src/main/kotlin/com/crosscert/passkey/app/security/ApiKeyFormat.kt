package com.crosscert.passkey.app.security

/** `X-API-Key` 값의 고정 접두사 — 이 모양이 아니면 DB 조회 전에 거부한다. */
internal const val API_KEY_PREFIX = "pk_"

/** 공개 prefix 길이: `"pk_"` + 8 base64url chars. 키 조회([ApiKeyAuthFilter])와 rate-limit 버킷 키([RateLimitFilter])가 공유. */
internal const val API_KEY_PREFIX_LEN = 11
