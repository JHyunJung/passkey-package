package com.crosscert.passkey.webauthn.cbor;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 디코드된 CBOR 값. WebAuthn에 필요한 주요 타입만 모델링한다
 * (RFC 8949 subset: unsigned/negative int, byte string, text string,
 * array, map, simple bool/null, tag).
 *
 * <p>불변 값 타입이다. byte 배열·컬렉션을 담는 record는 방어적 복사 +
 * 내용 기반 equals/hashCode로 호출자 변경에 노출되지 않게 한다 (codex P2).
 */
public sealed interface CborValue {

    record CborInt(long value) implements CborValue {}

    record CborBytes(byte[] value) implements CborValue {
        public CborBytes(byte[] value) {
            this.value = value.clone();
        }
        @Override public byte[] value() { return value.clone(); }
        @Override public boolean equals(Object o) {
            return o instanceof CborBytes b && Arrays.equals(value, b.value);
        }
        @Override public int hashCode() { return Arrays.hashCode(value); }
        @Override public String toString() { return "CborBytes[len=" + value.length + "]"; }
    }

    record CborText(String value) implements CborValue {}

    record CborArray(List<CborValue> items) implements CborValue {
        public CborArray(List<CborValue> items) {
            this.items = List.copyOf(items);
        }
    }

    /** key 순서를 보존하는 map (canonical 검증·디버깅 용이). */
    record CborMap(Map<CborValue, CborValue> entries) implements CborValue {
        public CborMap(Map<CborValue, CborValue> entries) {
            this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        }
    }

    record CborBool(boolean value) implements CborValue {}
    record CborNull() implements CborValue {}
    record CborTag(long tag, CborValue value) implements CborValue {}

    /** map에서 정수 키로 조회 (COSE_Key 라벨 접근용). null이면 미존재. */
    default CborValue get(long intKey) {
        if (this instanceof CborMap m) {
            return m.entries().get(new CborInt(intKey));
        }
        return null;
    }

    /** map에서 문자열 키로 조회 (attStmt 라벨 접근용). null이면 미존재. */
    default CborValue get(String textKey) {
        if (this instanceof CborMap m) {
            return m.entries().get(new CborText(textKey));
        }
        return null;
    }
}
