package com.crosscert.passkey.webauthn.mds;

import java.util.List;

/**
 * MDS3 BLOB entry (§3.1.1). aaguid는 null 가능(legacy U2F → 소비처가 스킵).
 * aaguid byte[]는 방어적 복사로 변조 차단(캐시 키 오염 방지, codex P2).
 */
public record MdsBlobEntry(byte[] aaguid, List<MdsStatusReport> statusReports) {
    public MdsBlobEntry {
        aaguid = aaguid == null ? null : aaguid.clone();
        statusReports = statusReports == null ? List.of() : List.copyOf(statusReports);
    }

    @Override
    public byte[] aaguid() { return aaguid == null ? null : aaguid.clone(); }
}
