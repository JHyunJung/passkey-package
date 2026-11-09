package com.crosscert.passkey.admin.audit

/** 주어진 id 의 incident 가 존재하지 않을 때. → HTTP 404. */
class IncidentNotFoundException(message: String) : RuntimeException(message)
