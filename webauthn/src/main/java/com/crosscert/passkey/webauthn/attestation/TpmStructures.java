package com.crosscert.passkey.webauthn.attestation;

import java.security.MessageDigest;
import java.util.Arrays;

/**
 * TPM 2.0 구조체 파서 (WebAuthn §8.3, fmt=tpm).
 *
 * <p>certInfo(TPMS_ATTEST)와 pubArea(TPMT_PUBLIC)를 big-endian으로 파싱한다.
 * TPM은 TPM2B 구조에 2바이트 UINT16 size prefix를 쓴다. 모든 길이/크기 읽기는
 * {@link Cursor} 경유로 bounds check(size &lt;= remaining)를 거치며, 위반 시
 * {@link AttestationException}을 던진다 (overflow 불가능한 형태).
 */
final class TpmStructures {

    private TpmStructures() {}

    // TPM 상수
    static final long TPM_GENERATED_VALUE = 0xFF544347L;
    static final int TPM_ST_ATTEST_CERTIFY = 0x8017;
    static final int TPM_ALG_RSA = 0x0001;
    static final int TPM_ALG_ECC = 0x0023;
    static final int TPM_ALG_NULL = 0x0010;

    // nameAlg → JCA digest
    static final int TPM_ALG_SHA1 = 0x0004;
    static final int TPM_ALG_SHA256 = 0x000B;
    static final int TPM_ALG_SHA384 = 0x000C;
    static final int TPM_ALG_SHA512 = 0x000D;

    // ECC curve IDs (TPM_ECC_CURVE)
    static final int TPM_ECC_NIST_P256 = 0x0003;

    /** 파싱된 TPMS_ATTEST 중 검증에 필요한 필드. */
    record CertInfo(long magic, int type, byte[] extraData, byte[] attestedName) {}

    /**
     * 파싱된 TPMT_PUBLIC 중 검증에 필요한 필드.
     * RSA면 uniqueModulus·exponent 사용(eccX/eccY null), ECC면 eccX/eccY/eccCurveId 사용(uniqueModulus null).
     * raw는 attestedName 계산을 위한 pubArea 원시 바이트.
     */
    record PubArea(int type, int nameAlg, byte[] uniqueModulus, long exponent,
                   byte[] eccX, byte[] eccY, int eccCurveId, byte[] raw) {}

    /**
     * TPMS_ATTEST 파싱:
     * magic(UINT32) | type(UINT16) | qualifiedSigner(TPM2B) | extraData(TPM2B) |
     * clockInfo(17) | firmwareVersion(8) | attested(TPMS_CERTIFY_INFO).
     * type==CERTIFY일 때 attested = name(TPM2B) || qualifiedName(TPM2B);
     * 첫 TPM2B(name)이 attestedName이다.
     */
    static CertInfo parseCertInfo(byte[] data) {
        Cursor c = new Cursor(data);
        long magic = c.readU32();
        int type = c.readU16();
        c.readTpm2b();                  // qualifiedSigner (skip)
        byte[] extraData = c.readTpm2b();
        c.skip(17);                     // clockInfo (8+4+4+1)
        c.skip(8);                      // firmwareVersion (UINT64)
        byte[] attestedName = c.readTpm2b(); // attested.name (첫 TPM2B)
        c.readTpm2b();                       // attested.qualifiedName (소비만 — 검증엔 불필요)
        // 공격자 바이너리이므로 정확히 끝까지 소비됐는지 확인 (trailing bytes 거부, codex P2).
        c.requireExhausted("tpm certInfo");
        return new CertInfo(magic, type, extraData, attestedName);
    }

    /**
     * TPMT_PUBLIC 파싱:
     * type(UINT16) | nameAlg(UINT16) | objectAttributes(UINT32) | authPolicy(TPM2B) |
     * parameters(type별) | unique(type별).
     */
    static PubArea parsePubArea(byte[] data) {
        Cursor c = new Cursor(data);
        int type = c.readU16();
        int nameAlg = c.readU16();
        c.readU32();                    // objectAttributes (skip)
        c.readTpm2b();                  // authPolicy (skip)

        if (type == TPM_ALG_RSA) {
            // TPMS_RSA_PARMS: symmetric | scheme | keyBits(UINT16) | exponent(UINT32)
            readSymmetric(c);
            readScheme(c);
            c.readU16();                // keyBits
            long exponent = c.readU32();
            byte[] modulus = c.readTpm2b(); // unique = TPM2B_PUBLIC_KEY_RSA
            // 정확히 끝까지 소비됐는지 확인 (trailing bytes 거부, codex P2).
            c.requireExhausted("tpm pubArea");
            return new PubArea(type, nameAlg, modulus, exponent, null, null, 0, data);
        }
        if (type == TPM_ALG_ECC) {
            // TPMS_ECC_PARMS: symmetric | scheme | curveID(UINT16) | kdf
            readSymmetric(c);
            readScheme(c);
            int curveId = c.readU16();  // curveID (검증을 위해 보존, codex P2)
            readScheme(c);              // kdf (TPMT_KDF_SCHEME — NULL이면 2바이트)
            byte[] x = c.readTpm2b();   // unique.x
            byte[] y = c.readTpm2b();   // unique.y
            // 정확히 끝까지 소비됐는지 확인 (trailing bytes 거부, codex P2).
            c.requireExhausted("tpm pubArea");
            return new PubArea(type, nameAlg, null, 0, x, y, curveId, data);
        }
        throw new AttestationException("tpm pubArea unsupported type: 0x" + Integer.toHexString(type));
    }

    /**
     * TPMT_SYM_DEF_OBJECT: algorithm(UINT16). attestation 키는 TPM_ALG_NULL이므로
     * 추가 필드(keyBits/mode)가 없다. NULL이 아니면 미지원으로 거부 (안전한 보수적 처리).
     */
    private static void readSymmetric(Cursor c) {
        int alg = c.readU16();
        if (alg != TPM_ALG_NULL) {
            throw new AttestationException(
                    "tpm pubArea symmetric must be TPM_ALG_NULL for attestation key, got 0x"
                            + Integer.toHexString(alg));
        }
    }

    /**
     * TPMT_*_SCHEME: scheme(UINT16). attestation 키는 TPM_ALG_NULL이므로 추가 필드가 없다.
     * NULL이 아니면 미지원으로 거부.
     */
    private static void readScheme(Cursor c) {
        int scheme = c.readU16();
        if (scheme != TPM_ALG_NULL) {
            throw new AttestationException(
                    "tpm pubArea scheme must be TPM_ALG_NULL for attestation key, got 0x"
                            + Integer.toHexString(scheme));
        }
    }

    /** nameAlg(TPM_ALG_ID) → JDK MessageDigest 인스턴스. */
    static MessageDigest digestForNameAlg(int nameAlg) {
        String jca = switch (nameAlg) {
            case TPM_ALG_SHA1 -> "SHA-1";
            case TPM_ALG_SHA256 -> "SHA-256";
            case TPM_ALG_SHA384 -> "SHA-384";
            case TPM_ALG_SHA512 -> "SHA-512";
            default -> throw new AttestationException(
                    "tpm unsupported nameAlg: 0x" + Integer.toHexString(nameAlg));
        };
        try {
            return MessageDigest.getInstance(jca);
        } catch (Exception e) {
            throw new AttestationException("tpm digest unavailable: " + jca, e);
        }
    }

    static boolean constantTimeEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    /** big-endian 바운드 체크 커서. UINT16=2바이트, UINT32=4바이트. */
    private static final class Cursor {
        private final byte[] buf;
        private int pos;

        Cursor(byte[] buf) {
            if (buf == null) throw new AttestationException("tpm structure is null");
            this.buf = buf;
        }

        private int remaining() { return buf.length - pos; }

        private void require(int n) {
            if (n < 0 || n > remaining()) {
                throw new AttestationException(
                        "tpm structure truncated: need " + n + " bytes, have " + remaining());
            }
        }

        int readU16() {
            require(2);
            int v = ((buf[pos] & 0xff) << 8) | (buf[pos + 1] & 0xff);
            pos += 2;
            return v;
        }

        long readU32() {
            require(4);
            long v = ((long) (buf[pos] & 0xff) << 24)
                    | ((long) (buf[pos + 1] & 0xff) << 16)
                    | ((long) (buf[pos + 2] & 0xff) << 8)
                    | (buf[pos + 3] & 0xff);
            pos += 4;
            return v;
        }

        void skip(int n) {
            require(n);
            pos += n;
        }

        /** 구조체가 정확히 끝까지 소비됐는지 확인. trailing bytes가 있으면 거부. */
        void requireExhausted(String what) {
            if (pos != buf.length) {
                throw new AttestationException(what + ": " + (buf.length - pos) + " trailing byte(s)");
            }
        }

        /** TPM2B: UINT16 size + size 바이트. size는 0~65535이라 overflow 불가. */
        byte[] readTpm2b() {
            int size = readU16();
            require(size);
            byte[] out = Arrays.copyOfRange(buf, pos, pos + size);
            pos += size;
            return out;
        }
    }
}
