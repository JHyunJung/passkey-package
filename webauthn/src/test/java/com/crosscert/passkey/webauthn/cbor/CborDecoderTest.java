package com.crosscert.passkey.webauthn.cbor;

import com.crosscert.passkey.webauthn.cbor.CborValue.*;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class CborDecoderTest {

    private static byte[] hex(String h) { return HexFormat.of().parseHex(h); }

    @Test
    void decodesUnsignedInt() {
        // RFC 8949 A: 0x00 -> 0, 0x17 -> 23, 0x1818 -> 24, 0x190100 -> 256
        assertEquals(0L, ((CborInt) CborDecoder.decode(hex("00"))).value());
        assertEquals(23L, ((CborInt) CborDecoder.decode(hex("17"))).value());
        assertEquals(24L, ((CborInt) CborDecoder.decode(hex("1818"))).value());
        assertEquals(256L, ((CborInt) CborDecoder.decode(hex("190100"))).value());
    }

    @Test
    void decodesNegativeInt() {
        // RFC 8949 A: 0x20 -> -1, 0x29 -> -10, 0x3863 -> -100
        assertEquals(-1L, ((CborInt) CborDecoder.decode(hex("20"))).value());
        assertEquals(-10L, ((CborInt) CborDecoder.decode(hex("29"))).value());
        assertEquals(-100L, ((CborInt) CborDecoder.decode(hex("3863"))).value());
    }

    @Test
    void decodesByteString() {
        // 0x4401020304 -> h'01020304'
        byte[] v = ((CborBytes) CborDecoder.decode(hex("4401020304"))).value();
        assertArrayEquals(hex("01020304"), v);
    }

    @Test
    void decodesTextString() {
        // 0x6161 -> "a"
        assertEquals("a", ((CborText) CborDecoder.decode(hex("6161"))).value());
    }

    @Test
    void decodesArray() {
        // 0x83010203 -> [1,2,3]
        CborArray a = (CborArray) CborDecoder.decode(hex("83010203"));
        assertEquals(3, a.items().size());
        assertEquals(1L, ((CborInt) a.items().get(0)).value());
    }

    @Test
    void decodesMapWithIntKeys() {
        // 0xa201020304 -> {1:2, 3:4}
        CborValue m = CborDecoder.decode(hex("a201020304"));
        assertEquals(2L, ((CborInt) m.get(1)).value());
        assertEquals(4L, ((CborInt) m.get(3)).value());
    }

    @Test
    void rejectsTrailingBytes() {
        // 0x00 다음에 0xff 잉여
        assertThrows(CborException.class, () -> CborDecoder.decode(hex("00ff")));
    }

    @Test
    void rejectsTruncatedInput() {
        // 0x44 (4바이트 byte string 선언) 인데 데이터 없음
        assertThrows(CborException.class, () -> CborDecoder.decode(hex("44")));
    }

    @Test
    void rejectsLengthBomb() {
        // 0x5bffffffffffffffff (8바이트 길이=거대) 인데 실데이터 없음
        assertThrows(CborException.class,
                () -> CborDecoder.decode(hex("5bffffffffffffffff")));
    }

    @Test
    void rejectsDuplicateMapKey() {
        // 0xa2 0101 0102 -> {1:1, 1:2} 중복 키
        assertThrows(CborException.class, () -> CborDecoder.decode(hex("a201010102")));
    }

    @Test
    void rejectsFloat() {
        // 0xfa47c35000 (single-precision float) -> 미지원 거부
        assertThrows(CborException.class, () -> CborDecoder.decode(hex("fa47c35000")));
    }

    @Test
    void rejectsInvalidUtf8TextString() {
        // 0x62 (text, len 2) + 0xff 0xff (invalid UTF-8 bytes)
        assertThrows(CborException.class, () -> CborDecoder.decode(hex("62ffff")));
    }
}
