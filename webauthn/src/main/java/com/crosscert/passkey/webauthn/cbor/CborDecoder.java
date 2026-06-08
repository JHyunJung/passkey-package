package com.crosscert.passkey.webauthn.cbor;

import com.crosscert.passkey.webauthn.cbor.CborValue.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC 8949 CBOR 디코더 (WebAuthn에 필요한 subset).
 *
 * 보안 방어:
 *  - 최대 중첩 깊이 제한 (MAX_DEPTH)
 *  - 컬렉션 길이를 남은 바이트 수로 상한 (length-bomb 방어)
 *  - 최상위 디코드 후 잉여 바이트 거부 (trailing-data 방어)
 *
 * float·indefinite-length·bignum 등 WebAuthn에 불필요한 형식은
 * 명시적으로 거부한다 (관용 파싱은 공격면을 늘린다).
 */
public final class CborDecoder {

    private static final int MAX_DEPTH = 16;

    private final byte[] buf;
    private int pos;

    private CborDecoder(byte[] buf) { this.buf = buf; }

    /** 단일 최상위 CBOR 항목을 디코드. 잉여 바이트가 있으면 거부. */
    public static CborValue decode(byte[] bytes) {
        CborDecoder d = new CborDecoder(bytes);
        CborValue v = d.readItem(0);
        if (d.pos != bytes.length) {
            throw new CborException("trailing bytes after top-level CBOR item");
        }
        return v;
    }

    /**
     * authData 끝의 extensions처럼 "나머지 바이트"를 따로 다뤄야 하는
     * 경우를 위해, 한 항목을 읽고 소비한 바이트 수를 반환한다.
     */
    public static int decodeFirstItemLength(byte[] bytes, int offset) {
        if (offset < 0 || offset >= bytes.length) {
            throw new CborException("offset out of bounds: " + offset);
        }
        CborDecoder d = new CborDecoder(bytes);
        d.pos = offset;
        d.readItem(0);
        return d.pos - offset;
    }

    private CborValue readItem(int depth) {
        if (depth > MAX_DEPTH) throw new CborException("CBOR nesting too deep");
        int ib = readByte();
        int major = (ib >> 5) & 0x07;
        int minor = ib & 0x1f;
        return switch (major) {
            case 0 -> new CborInt(readUint(minor));                       // unsigned
            case 1 -> new CborInt(-1 - readUint(minor));                  // negative
            case 2 -> new CborBytes(readBytes(readLength(minor)));        // byte string
            case 3 -> new CborText(decodeUtf8(readBytes(readLength(minor)))); // text string
            case 4 -> readArray(readLength(minor), depth);               // array
            case 5 -> readMap(readLength(minor), depth);                 // map
            case 6 -> new CborTag(readUint(minor), readItem(depth + 1)); // tag
            case 7 -> readSimple(minor);                                 // simple/float
            default -> throw new CborException("unknown major type " + major);
        };
    }

    private CborArray readArray(int n, int depth) {
        List<CborValue> items = new ArrayList<>(n);
        for (int i = 0; i < n; i++) items.add(readItem(depth + 1));
        return new CborArray(items);
    }

    private CborMap readMap(int n, int depth) {
        Map<CborValue, CborValue> entries = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            CborValue k = readItem(depth + 1);
            CborValue v = readItem(depth + 1);
            if (entries.put(k, v) != null) {
                throw new CborException("duplicate CBOR map key");
            }
        }
        return new CborMap(entries);
    }

    private CborValue readSimple(int minor) {
        return switch (minor) {
            case 20 -> new CborBool(false);
            case 21 -> new CborBool(true);
            case 22 -> new CborNull();
            default -> throw new CborException("unsupported simple/float value " + minor);
        };
    }

    /** RFC 8949 §3.1: text string은 well-formed UTF-8이어야 한다. 잘못되면 거부. */
    private static String decodeUtf8(byte[] bytes) {
        java.nio.charset.CharsetDecoder decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        try {
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new CborException("invalid UTF-8 in CBOR text string");
        }
    }

    /** minor가 길이 인자를 표현 (24/25/26/27 = 다음 1/2/4/8 바이트). */
    private long readUint(int minor) {
        if (minor < 24) return minor;
        return switch (minor) {
            case 24 -> readByte() & 0xffL;
            case 25 -> readN(2);
            case 26 -> readN(4);
            case 27 -> readN(8);
            default -> throw new CborException("invalid additional info " + minor);
        };
    }

    /** 컬렉션/문자열 길이로 사용 — 음수·과대 길이는 즉시 거부. */
    private int readLength(int minor) {
        long len = readUint(minor);
        if (len < 0 || len > buf.length - pos) {
            throw new CborException("CBOR length exceeds remaining bytes: " + len);
        }
        return (int) len;
    }

    private long readN(int n) {
        long v = 0;
        for (int i = 0; i < n; i++) v = (v << 8) | (readByte() & 0xffL);
        if (v < 0) throw new CborException("CBOR integer out of supported range");
        return v;
    }

    private int readByte() {
        if (pos >= buf.length) throw new CborException("unexpected end of CBOR input");
        return buf[pos++] & 0xff;
    }

    private byte[] readBytes(int n) {
        byte[] out = new byte[n];
        System.arraycopy(buf, pos, out, 0, n);
        pos += n;
        return out;
    }
}
