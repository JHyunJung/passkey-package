package com.crosscert.passkey.admin.audit

/** 테넌트에 이미 OPEN incident 가 있을 때. → HTTP 409. */
class IncidentConflictException(message: String) : RuntimeException(message)
