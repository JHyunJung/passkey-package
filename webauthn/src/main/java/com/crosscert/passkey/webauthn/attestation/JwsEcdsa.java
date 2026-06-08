package com.crosscert.passkey.webauthn.attestation;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.Arrays;

/** JWS ES256 서명(raw 64바이트 R||S)을 JDK Signature가 받는 DER(SEQUENCE{INTEGER r, INTEGER s})로 변환. */
final class JwsEcdsa {
    private JwsEcdsa() {}

    static byte[] toDer(byte[] raw) {
        if (raw.length != 64) throw new AttestationException("ES256 JWS sig must be 64 bytes");
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(raw, 0, 32));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(raw, 32, 64));
        byte[] rb = toMinimalSigned(r);
        byte[] sb = toMinimalSigned(s);
        ByteArrayOutputStream seq = new ByteArrayOutputStream();
        seq.write(0x02); seq.write(rb.length); seq.writeBytes(rb);
        seq.write(0x02); seq.write(sb.length); seq.writeBytes(sb);
        byte[] body = seq.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // body는 r/s(각 ≤33바이트) 두 INTEGER → 최대 ~70바이트, 단일바이트 길이로 항상 표현 가능.
        out.write(0x30); out.write(body.length); out.writeBytes(body);
        return out.toByteArray();
    }

    /** BigInteger를 DER INTEGER용 최소 부호 있는 big-endian으로 (선행 0x00은 BigInteger가 처리). */
    private static byte[] toMinimalSigned(BigInteger v) {
        return v.toByteArray(); // 이미 minimal two's-complement big-endian; 양수면 필요한 곳에 0x00 패딩 포함
    }
}
