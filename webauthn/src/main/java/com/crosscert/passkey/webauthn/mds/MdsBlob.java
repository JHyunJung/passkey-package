package com.crosscert.passkey.webauthn.mds;

import java.time.LocalDate;
import java.util.List;

/** MDS3 BLOB payload (§3.1.6). no=버전, nextUpdate=다음 갱신일, entries=authenticator 목록. */
public record MdsBlob(int no, LocalDate nextUpdate, List<MdsBlobEntry> entries) {}
