package com.crosscert.passkey.webauthn.mds;

import java.time.LocalDate;

/**
 * MDS3 status report (FIDO MDS3 §3.1.3). status는 MDS3 원문 토큰을
 * 그대로 보존한다 — enum 강제 안 함(미지 값도 거부하지 않아 MDS3 진화에 견고).
 */
public record MdsStatusReport(String status, LocalDate effectiveDate) {}
