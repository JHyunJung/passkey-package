# WebAuthn 등록/인증 검증 코어 자체 구현 (`:webauthn` 모듈) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** webauthn4j 라이브러리를 W3C WebAuthn Level 3 spec 직접 구현으로 대체한다 — 등록/인증 검증 코어를 새 `:webauthn` Gradle 모듈에 자체 구현하고, `WebAuthnVerifier` 인터페이스 뒤에서 webauthn4j 구현체와 점진 스위치한다.

**Architecture:** 접근법 A(어댑터 우선 + 모듈 격리). 새 모듈 `:webauthn`(순수 Java, JDK `java.security`만 사용)에 CBOR 디코더·COSE 키 파서·authData 파서·clientData 검증기·attestation 검증기를 만든다. 앱은 `WebAuthnVerifier` 인터페이스에만 의존하며, `NativeWebAuthnVerifier`(자체)와 `Webauthn4jVerifier`(differential 테스트용, testImplementation에만 존재)를 동일 인터페이스 뒤에 둔다. 검증은 webauthn4j와의 differential 테스트 + W3C/FIDO 공식 test vector로 한다.

**Tech Stack:** Java 17, Gradle (libs.versions.toml), JDK `java.security`(Signature/CertPathValidator/MessageDigest/KeyFactory), JUnit 5, Jackson(clientDataJSON 파싱), webauthn4j 0.31.5(differential 테스트 및 입력 생성에만).

**Spec:** `docs/superpowers/specs/2026-06-08-webauthn-native-verifier-design.md`

---

## 파일 구조 (이 plan이 만들거나 수정하는 파일)

**새 모듈 `:webauthn`** (`webauthn/src/main/java/com/crosscert/passkey/webauthn/`):

| 파일 | 책임 |
|------|------|
| `webauthn/build.gradle.kts` | 모듈 빌드 — 순수 Java, 프로덕션 의존 없음, test에만 webauthn4j |
| `cbor/CborValue.java` | 디코드된 CBOR 값 트리(sealed: Int/Bytes/Text/Array/Map/Bool/Null/Tag) |
| `cbor/CborDecoder.java` | RFC 8949 subset 디코더 (깊이·길이·잉여바이트 방어) |
| `cbor/CborException.java` | CBOR 디코드 오류 |
| `cose/CoseAlgorithm.java` | COSE alg enum (ES256=-7, RS256=-257) + JDK Signature 알고리즘명 매핑 |
| `cose/CoseKey.java` | 파싱된 COSE_Key (kty/alg + PublicKey) |
| `cose/CoseKeyParser.java` | COSE_Key CBOR map → java.security.PublicKey |
| `authdata/AuthenticatorData.java` | 파싱 결과 record (rpIdHash/flags/signCount/attestedCredentialData/extensions) |
| `authdata/AuthenticatorFlags.java` | UP/UV/BE/BS/AT/ED 플래그 |
| `authdata/AttestedCredentialData.java` | aaguid/credentialId/coseKey |
| `authdata/AuthenticatorDataParser.java` | authData byte[] → AuthenticatorData |
| `clientdata/CollectedClientData.java` | type/challenge/origin/crossOrigin |
| `clientdata/ClientDataValidator.java` | clientDataJSON 파싱·검증 |
| `attestation/AttestationStatement.java` | 디코드된 attStmt (fmt + raw CBOR map) |
| `attestation/AttestationVerifier.java` | 인터페이스 — `verify(authData, attStmtMap, clientDataHash)` |
| `attestation/AttestationResult.java` | trust type(NONE/SELF/BASIC/ATTCA) + trust path |
| `attestation/NoneAttestationVerifier.java` | fmt=none |
| `attestation/PackedAttestationVerifier.java` | fmt=packed (self + x5c) |
| `attestation/FidoU2fAttestationVerifier.java` | fmt=fido-u2f |
| `attestation/TpmAttestationVerifier.java` | fmt=tpm |
| `attestation/AndroidKeyAttestationVerifier.java` | fmt=android-key |
| `attestation/AndroidSafetyNetAttestationVerifier.java` | fmt=android-safetynet |
| `attestation/AppleAttestationVerifier.java` | fmt=apple |
| `attestation/AttestationVerifiers.java` | fmt 문자열 → AttestationVerifier 레지스트리 |
| `trust/TrustAnchorProvider.java` | 인터페이스 — aaguid/fmt → Set<TrustAnchor> |
| `trust/EmptyTrustAnchorProvider.java` | 기본 구현 (빈 셋 = self/none만 통과) |
| `trust/CertPathVerifier.java` | X.509 체인 검증 (CertPathValidator 래핑) |
| `verifier/COSEAlgorithm.java` | 앱 경계용 alg enum (계약 타입) |
| `verifier/AttestationTrustPolicy.java` | NONE_ONLY / SELF_ALLOWED / TRUST_CHAIN_REQUIRED |
| `verifier/StoredCredential.java` | DB 로드 credential (credentialId/cosePublicKey/signCount) |
| `verifier/RegistrationInput.java` | 등록 검증 입력 record |
| `verifier/RegistrationResult.java` | 등록 검증 출력 record |
| `verifier/AuthenticationInput.java` | 인증 검증 입력 record |
| `verifier/AuthenticationResult.java` | 인증 검증 출력 record |
| `verifier/WebAuthnVerificationException.java` | 검증 실패 예외 (Reason enum) |
| `verifier/WebAuthnVerifier.java` | 인터페이스 — verifyRegistration / verifyAuthentication |
| `verifier/NativeWebAuthnVerifier.java` | 자체 구현 오케스트레이터 |

**테스트** (`webauthn/src/test/java/com/crosscert/passkey/webauthn/`):
- 각 빌딩블록 단위테스트, `Webauthn4jVerifier`(testImplementation), differential 테스트, 포맷별 vector 테스트.
- `webauthn/src/test/resources/vectors/` — W3C/FIDO 공식 test vector 픽스처.

**수정 (passkey-app / core)**:
| 파일 | 변경 |
|------|------|
| `settings.gradle.kts` | `include(":webauthn")` 추가 |
| `passkey-app/build.gradle.kts` | `:webauthn` 의존 추가 |
| `core/src/main/java/.../entity/Credential.java` | `cosePublicKey` 등 개별 필드 스키마 |
| `core/src/main/resources/db/migration/V44__credential_cose_schema.sql` | 저장 스키마 DDL |
| `passkey-app/.../fido2/Webauthn4jConfig.java` → `WebAuthnVerifierConfig.java` | verifier 빈 구성 |
| `passkey-app/.../fido2/registration/RegistrationFinishService.java` | 본문을 `WebAuthnVerifier` 호출로 |
| `passkey-app/.../fido2/authentication/AuthenticationFinishService.java` | 본문을 `WebAuthnVerifier` 호출로 |

**무변경**: 컨트롤러·API DTO·`*StartService`·MDS 코드·admin-app·sdk-java.

---

## Phase 0 — 모듈 셋업

### Task 0: 새 `:webauthn` Gradle 모듈 생성

**Files:**
- Create: `webauthn/build.gradle.kts`
- Modify: `settings.gradle.kts:3`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/package-info.java`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/ModuleSmokeTest.java`

- [ ] **Step 1: settings.gradle.kts에 모듈 등록**

`settings.gradle.kts`를 다음으로 교체:

```kotlin
rootProject.name = "passkey2"

include(":core", ":passkey-app", ":admin-app", ":sdk-java", ":rp-app", ":webauthn")
```

- [ ] **Step 2: 모듈 build.gradle.kts 작성**

`webauthn/build.gradle.kts` 생성:

```kotlin
plugins {
    `java-library`
}

dependencies {
    // 프로덕션: JSON 파싱(clientDataJSON)에만 Jackson 사용. Spring·webauthn4j 의존 없음.
    api("com.fasterxml.jackson.core:jackson-databind")

    // differential 테스트 전용 — webauthn4j는 절대 프로덕션 classpath에 들어가지 않는다.
    testImplementation(rootProject.libs.webauthn4j.core)
    testImplementation(rootProject.libs.webauthn4j.test)
}
```

- [ ] **Step 3: package-info.java로 빈 소스셋 방지**

`webauthn/src/main/java/com/crosscert/passkey/webauthn/package-info.java` 생성:

```java
/**
 * 자체 구현 WebAuthn 등록/인증 검증 코어. W3C WebAuthn Level 3
 * (https://www.w3.org/TR/webauthn-3/) 직접 구현. JDK java.security
 * 외 암호 의존성 없음.
 */
package com.crosscert.passkey.webauthn;
```

- [ ] **Step 4: 스모크 테스트 작성**

`webauthn/src/test/java/com/crosscert/passkey/webauthn/ModuleSmokeTest.java` 생성:

```java
package com.crosscert.passkey.webauthn;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleSmokeTest {
    @Test
    void moduleCompilesAndTestsRun() {
        assertTrue(true);
    }
}
```

- [ ] **Step 5: 모듈 빌드·테스트 실행**

Run: `./gradlew :webauthn:test`
Expected: BUILD SUCCESSFUL, ModuleSmokeTest 통과

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts webauthn/build.gradle.kts webauthn/src/main/java/com/crosscert/passkey/webauthn/package-info.java webauthn/src/test/java/com/crosscert/passkey/webauthn/ModuleSmokeTest.java
git commit -m "feat(webauthn): :webauthn 모듈 스캐폴드 (순수 Java + JDK java.security)"
```

---

## Phase 1 — 공통 빌딩블록

### Task 1: CborValue 트리 타입

**Files:**
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/cbor/CborValue.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/cbor/CborException.java`

- [ ] **Step 1: CborException 작성**

`cbor/CborException.java`:

```java
package com.crosscert.passkey.webauthn.cbor;

/** CBOR 디코드 실패 (잘못된 형식·깊이 초과·잉여 바이트 등). */
public final class CborException extends RuntimeException {
    public CborException(String message) { super(message); }
}
```

- [ ] **Step 2: CborValue sealed 계층 작성**

`cbor/CborValue.java`:

```java
package com.crosscert.passkey.webauthn.cbor;

import java.util.List;
import java.util.Map;

/**
 * 디코드된 CBOR 값. WebAuthn에 필요한 주요 타입만 모델링한다
 * (RFC 8949 subset: unsigned/negative int, byte string, text string,
 * array, map, simple bool/null, tag).
 */
public sealed interface CborValue {

    record CborInt(long value) implements CborValue {}
    record CborBytes(byte[] value) implements CborValue {}
    record CborText(String value) implements CborValue {}
    record CborArray(List<CborValue> items) implements CborValue {}
    /** key 순서를 보존하는 map (canonical 검증·디버깅 용이). */
    record CborMap(Map<CborValue, CborValue> entries) implements CborValue {}
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
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew :webauthn:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/cbor/
git commit -m "feat(webauthn): CborValue 트리 타입 + CborException"
```

### Task 2: CborDecoder — happy path (int/bytes/text)

**Files:**
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/cbor/CborDecoder.java`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/cbor/CborDecoderTest.java`

- [ ] **Step 1: 실패하는 테스트 작성 (RFC 8949 Appendix A 벡터)**

`cbor/CborDecoderTest.java`:

```java
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
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :webauthn:test --tests '*CborDecoderTest'`
Expected: FAIL — `CborDecoder` 클래스 없음 (컴파일 에러)

- [ ] **Step 3: CborDecoder 최소 구현 (int/bytes/text + map/array/bool/null/tag 골격)**

`cbor/CborDecoder.java`:

```java
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
            case 3 -> new CborText(new String(                           // text string
                    readBytes(readLength(minor)), java.nio.charset.StandardCharsets.UTF_8));
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
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :webauthn:test --tests '*CborDecoderTest'`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/cbor/CborDecoder.java webauthn/src/test/java/com/crosscert/passkey/webauthn/cbor/CborDecoderTest.java
git commit -m "feat(webauthn): CborDecoder int/bytes/text + RFC 8949 벡터 테스트"
```

### Task 3: CborDecoder — array/map/방어 테스트

**Files:**
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/cbor/CborDecoderTest.java` (추가)

- [ ] **Step 1: array/map/방어 테스트 추가**

`CborDecoderTest.java`에 메서드 추가:

```java
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
        assertThrows(CborException.class, () -> CborDecoder.decode(hex("a2010101 02".replace(" ", ""))));
    }

    @Test
    void rejectsFloat() {
        // 0xfa47c35000 (single-precision float) -> 미지원 거부
        assertThrows(CborException.class, () -> CborDecoder.decode(hex("fa47c35000")));
    }
```

- [ ] **Step 2: 테스트 실행 (기존 구현으로 통과해야 함)**

Run: `./gradlew :webauthn:test --tests '*CborDecoderTest'`
Expected: PASS (전체). 만약 `rejectsLengthBomb`이 OOM/실패하면 Task 2의 `readLength` 상한 로직 확인.

- [ ] **Step 3: Commit**

```bash
git add webauthn/src/test/java/com/crosscert/passkey/webauthn/cbor/CborDecoderTest.java
git commit -m "test(webauthn): CborDecoder array/map + 악성입력 방어 테스트"
```

### Task 4: CoseAlgorithm + CoseKey + CoseKeyParser (ES256)

**Files:**
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/cose/CoseAlgorithm.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/cose/CoseKey.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/cose/CoseKeyParser.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/cose/CoseException.java`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/cose/CoseKeyParserTest.java`

- [ ] **Step 1: CoseException + CoseAlgorithm 작성**

`cose/CoseException.java`:

```java
package com.crosscert.passkey.webauthn.cose;

/** COSE_Key 파싱·알고리즘 매핑 실패. */
public final class CoseException extends RuntimeException {
    public CoseException(String message) { super(message); }
    public CoseException(String message, Throwable cause) { super(message, cause); }
}
```

`cose/CoseAlgorithm.java`:

```java
package com.crosscert.passkey.webauthn.cose;

/**
 * 지원하는 COSE 서명 알고리즘. coseValue는 COSE_Key의 alg(라벨 3) 값.
 * jcaSignatureName은 JDK java.security.Signature.getInstance에 넘기는 이름.
 *
 * 이번 범위는 ES256/RS256 한정 (spec §2 allowedAlgorithms). 그 외는
 * UNSUPPORTED_ALGORITHM으로 거부한다.
 */
public enum CoseAlgorithm {
    ES256(-7, "SHA256withECDSA"),
    RS256(-257, "SHA256withRSA");

    private final long coseValue;
    private final String jcaSignatureName;

    CoseAlgorithm(long coseValue, String jcaSignatureName) {
        this.coseValue = coseValue;
        this.jcaSignatureName = jcaSignatureName;
    }

    public long coseValue() { return coseValue; }
    public String jcaSignatureName() { return jcaSignatureName; }

    public static CoseAlgorithm fromCoseValue(long v) {
        for (CoseAlgorithm a : values()) if (a.coseValue == v) return a;
        throw new CoseException("unsupported COSE algorithm: " + v);
    }
}
```

- [ ] **Step 2: CoseKey 작성**

`cose/CoseKey.java`:

```java
package com.crosscert.passkey.webauthn.cose;

import java.security.PublicKey;

/** 파싱된 COSE_Key — 알고리즘과 복원된 JDK 공개키. */
public record CoseKey(CoseAlgorithm algorithm, PublicKey publicKey) {}
```

- [ ] **Step 3: 실패하는 테스트 작성 (ES256 P-256 키, webauthn4j로 기대값 생성)**

`cose/CoseKeyParserTest.java`:

```java
package com.crosscert.passkey.webauthn.cose;

import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.*;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoseKeyParserTest {

    /** kty=2(EC2), alg=-7(ES256), crv=1(P-256), x/y 32바이트인 COSE_Key map을 구성. */
    private CborMap es256CoseMap(byte[] x, byte[] y) {
        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborInt(1), new CborInt(2));    // kty = EC2
        m.put(new CborInt(3), new CborInt(-7));   // alg = ES256
        m.put(new CborInt(-1), new CborInt(1));   // crv = P-256
        m.put(new CborInt(-2), new CborBytes(x)); // x
        m.put(new CborInt(-3), new CborBytes(y)); // y
        return new CborMap(m);
    }

    @Test
    void parsesEs256Key() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        ECPublicKey pub = (ECPublicKey) g.generateKeyPair().getPublic();

        byte[] x = toFixed32(pub.getW().getAffineX());
        byte[] y = toFixed32(pub.getW().getAffineY());

        CoseKey key = CoseKeyParser.parse(es256CoseMap(x, y));

        assertEquals(CoseAlgorithm.ES256, key.algorithm());
        ECPublicKey parsed = (ECPublicKey) key.publicKey();
        assertEquals(pub.getW().getAffineX(), parsed.getW().getAffineX());
        assertEquals(pub.getW().getAffineY(), parsed.getW().getAffineY());
    }

    @Test
    void rejectsUnsupportedAlgorithm() {
        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborInt(1), new CborInt(1));    // kty = OKP (Ed25519 계열)
        m.put(new CborInt(3), new CborInt(-8));   // alg = EdDSA (미지원)
        assertThrows(CoseException.class, () -> CoseKeyParser.parse(new CborMap(m)));
    }

    private static byte[] toFixed32(java.math.BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }
}
```

- [ ] **Step 4: 테스트 실패 확인**

Run: `./gradlew :webauthn:test --tests '*CoseKeyParserTest'`
Expected: FAIL — `CoseKeyParser` 없음

- [ ] **Step 5: CoseKeyParser 구현 (EC2 P-256 + RSA)**

`cose/CoseKeyParser.java`:

```java
package com.crosscert.passkey.webauthn.cose;

import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.*;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;

/**
 * COSE_Key (RFC 9052) CBOR map을 java.security.PublicKey로 복원.
 *
 * 라벨: kty=1, alg=3, EC2: crv=-1, x=-2, y=-3 / RSA: n=-1, e=-2.
 * kty: 2=EC2, 3=RSA. 이번 범위는 ES256(EC2 P-256), RS256(RSA)만 지원.
 */
public final class CoseKeyParser {

    private CoseKeyParser() {}

    private static final int KTY_EC2 = 2;
    private static final int KTY_RSA = 3;

    public static CoseKey parse(CborValue coseMap) {
        long alg = requireInt(coseMap.get(3), "alg");
        CoseAlgorithm algorithm = CoseAlgorithm.fromCoseValue(alg);
        long kty = requireInt(coseMap.get(1), "kty");

        PublicKey pub = switch ((int) kty) {
            case KTY_EC2 -> parseEc2(coseMap, algorithm);
            case KTY_RSA -> parseRsa(coseMap, algorithm);
            default -> throw new CoseException("unsupported COSE kty: " + kty);
        };
        return new CoseKey(algorithm, pub);
    }

    private static PublicKey parseEc2(CborValue m, CoseAlgorithm alg) {
        if (alg != CoseAlgorithm.ES256) {
            throw new CoseException("EC2 key with non-ES256 alg: " + alg);
        }
        long crv = requireInt(m.get(-1), "crv");
        if (crv != 1) throw new CoseException("unsupported EC curve (need P-256): " + crv);
        byte[] x = requireBytes(m.get(-2), "x");
        byte[] y = requireBytes(m.get(-3), "y");
        if (x.length != 32 || y.length != 32) {
            throw new CoseException("P-256 x/y must be 32 bytes");
        }
        try {
            ECParameterSpec p256 = P256.parameterSpec();
            ECPoint point = new ECPoint(new BigInteger(1, x), new BigInteger(1, y));
            return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, p256));
        } catch (Exception e) {
            throw new CoseException("failed to build EC public key", e);
        }
    }

    private static PublicKey parseRsa(CborValue m, CoseAlgorithm alg) {
        if (alg != CoseAlgorithm.RS256) {
            throw new CoseException("RSA key with non-RS256 alg: " + alg);
        }
        byte[] n = requireBytes(m.get(-1), "n");
        byte[] e = requireBytes(m.get(-2), "e");
        try {
            return KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(new BigInteger(1, n), new BigInteger(1, e)));
        } catch (Exception ex) {
            throw new CoseException("failed to build RSA public key", ex);
        }
    }

    private static long requireInt(CborValue v, String field) {
        if (v instanceof CborInt i) return i.value();
        throw new CoseException("COSE_Key missing/invalid int field: " + field);
    }

    private static byte[] requireBytes(CborValue v, String field) {
        if (v instanceof CborBytes b) return b.value();
        throw new CoseException("COSE_Key missing/invalid bytes field: " + field);
    }
}
```

- [ ] **Step 6: P-256 파라미터 헬퍼 작성**

`cose/P256.java`:

```java
package com.crosscert.passkey.webauthn.cose;

import java.security.AlgorithmParameters;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;

/** secp256r1(P-256) 곡선 파라미터를 JDK에서 한 번 얻어 캐시. */
final class P256 {
    private P256() {}

    private static final ECParameterSpec SPEC;

    static {
        try {
            AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
            ap.init(new ECGenParameterSpec("secp256r1"));
            SPEC = ap.getParameterSpec(ECParameterSpec.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static ECParameterSpec parameterSpec() { return SPEC; }
}
```

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew :webauthn:test --tests '*CoseKeyParserTest'`
Expected: PASS (2 tests)

- [ ] **Step 8: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/cose/ webauthn/src/test/java/com/crosscert/passkey/webauthn/cose/CoseKeyParserTest.java
git commit -m "feat(webauthn): CoseKeyParser (ES256/RS256) + JDK KeyFactory 복원"
```

### Task 5: AuthenticatorData 파서

**Files:**
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/authdata/AuthenticatorFlags.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/authdata/AttestedCredentialData.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/authdata/AuthenticatorData.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/authdata/AuthenticatorDataParser.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/authdata/AuthDataException.java`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/authdata/AuthenticatorDataParserTest.java`

- [ ] **Step 1: 값 타입들 작성**

`authdata/AuthDataException.java`:

```java
package com.crosscert.passkey.webauthn.authdata;

/** authenticatorData 파싱 실패. */
public final class AuthDataException extends RuntimeException {
    public AuthDataException(String message) { super(message); }
}
```

`authdata/AuthenticatorFlags.java`:

```java
package com.crosscert.passkey.webauthn.authdata;

/** authenticatorData flags 바이트 (WebAuthn §6.1). */
public record AuthenticatorFlags(
        boolean userPresent,        // UP (bit 0)
        boolean userVerified,       // UV (bit 2)
        boolean backupEligible,     // BE (bit 3)
        boolean backupState,        // BS (bit 4)
        boolean attestedCredentialDataIncluded, // AT (bit 6)
        boolean extensionDataIncluded           // ED (bit 7)
) {
    public static AuthenticatorFlags fromByte(int b) {
        return new AuthenticatorFlags(
                (b & 0x01) != 0,
                (b & 0x04) != 0,
                (b & 0x08) != 0,
                (b & 0x10) != 0,
                (b & 0x40) != 0,
                (b & 0x80) != 0);
    }
}
```

`authdata/AttestedCredentialData.java`:

```java
package com.crosscert.passkey.webauthn.authdata;

import com.crosscert.passkey.webauthn.cbor.CborValue;

/** AT 플래그가 켜졌을 때 authData에 포함되는 등록 자격증명 데이터. */
public record AttestedCredentialData(
        byte[] aaguid,            // 16바이트
        byte[] credentialId,      // 가변 길이
        CborValue coseKeyMap,     // 원시 COSE_Key CBOR map (CoseKeyParser가 소비)
        byte[] coseKeyBytes       // 그 map의 정확한 바이트 표현 (저장용)
) {}
```

`authdata/AuthenticatorData.java`:

```java
package com.crosscert.passkey.webauthn.authdata;

/** 파싱된 authenticatorData (WebAuthn §6.1). attestedCredentialData는 등록 시에만 존재(null 가능). */
public record AuthenticatorData(
        byte[] rpIdHash,                              // 32바이트
        AuthenticatorFlags flags,
        long signCount,                              // unsigned 32-bit
        AttestedCredentialData attestedCredentialData // nullable
) {}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`authdata/AuthenticatorDataParserTest.java`:

```java
package com.crosscert.passkey.webauthn.authdata;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticatorDataParserTest {

    /** rpIdHash(32) + flags(1) + signCount(4) 만 있는 인증용 authData. */
    @Test
    void parsesAssertionAuthData() {
        byte[] rpIdHash = new byte[32];
        for (int i = 0; i < 32; i++) rpIdHash[i] = (byte) i;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(rpIdHash);
        out.write(0x05); // UP(0x01) + UV(0x04)
        out.writeBytes(new byte[]{0x00, 0x00, 0x00, 0x2a}); // signCount = 42

        AuthenticatorData ad = AuthenticatorDataParser.parse(out.toByteArray());

        assertArrayEquals(rpIdHash, ad.rpIdHash());
        assertTrue(ad.flags().userPresent());
        assertTrue(ad.flags().userVerified());
        assertFalse(ad.flags().attestedCredentialDataIncluded());
        assertEquals(42L, ad.signCount());
        assertNull(ad.attestedCredentialData());
    }

    /** AT 플래그 + attestedCredentialData(aaguid + credId + COSE_Key). */
    @Test
    void parsesRegistrationAuthDataWithAttestedCredential() {
        byte[] rpIdHash = new byte[32];
        byte[] aaguid = HexFormat.of().parseHex("00112233445566778899aabbccddeeff");
        byte[] credId = HexFormat.of().parseHex("cafe");
        // 최소 COSE_Key: {1:2} (kty=EC2) — 파서는 바이트만 떼어내면 됨
        byte[] coseKey = HexFormat.of().parseHex("a10102");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(rpIdHash);
        out.write(0x41); // UP(0x01) + AT(0x40)
        out.writeBytes(new byte[]{0x00, 0x00, 0x00, 0x00}); // signCount = 0
        out.writeBytes(aaguid);
        out.writeBytes(new byte[]{0x00, 0x02}); // credIdLen = 2
        out.writeBytes(credId);
        out.writeBytes(coseKey);

        AuthenticatorData ad = AuthenticatorDataParser.parse(out.toByteArray());

        assertTrue(ad.flags().attestedCredentialDataIncluded());
        assertNotNull(ad.attestedCredentialData());
        assertArrayEquals(aaguid, ad.attestedCredentialData().aaguid());
        assertArrayEquals(credId, ad.attestedCredentialData().credentialId());
        assertArrayEquals(coseKey, ad.attestedCredentialData().coseKeyBytes());
    }

    @Test
    void rejectsTooShortAuthData() {
        assertThrows(AuthDataException.class,
                () -> AuthenticatorDataParser.parse(new byte[10]));
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew :webauthn:test --tests '*AuthenticatorDataParserTest'`
Expected: FAIL — `AuthenticatorDataParser` 없음

- [ ] **Step 4: AuthenticatorDataParser 구현**

`authdata/AuthenticatorDataParser.java`:

```java
package com.crosscert.passkey.webauthn.authdata;

import com.crosscert.passkey.webauthn.cbor.CborDecoder;
import com.crosscert.passkey.webauthn.cbor.CborException;
import com.crosscert.passkey.webauthn.cbor.CborValue;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * authenticatorData 파서 (WebAuthn §6.1 고정 오프셋):
 *   rpIdHash(32) || flags(1) || signCount(4, big-endian)
 *   [ AT 플래그 시: aaguid(16) || credIdLen(2) || credId || COSE_Key ]
 *   [ ED 플래그 시: extensions CBOR map ]
 */
public final class AuthenticatorDataParser {

    private AuthenticatorDataParser() {}

    private static final int RP_ID_HASH_LEN = 32;
    private static final int FLAGS_LEN = 1;
    private static final int SIGN_COUNT_LEN = 4;
    private static final int FIXED_LEN = RP_ID_HASH_LEN + FLAGS_LEN + SIGN_COUNT_LEN; // 37
    private static final int AAGUID_LEN = 16;

    public static AuthenticatorData parse(byte[] data) {
        if (data.length < FIXED_LEN) {
            throw new AuthDataException("authenticatorData too short: " + data.length);
        }
        byte[] rpIdHash = Arrays.copyOfRange(data, 0, RP_ID_HASH_LEN);
        AuthenticatorFlags flags = AuthenticatorFlags.fromByte(data[RP_ID_HASH_LEN] & 0xff);
        long signCount = ByteBuffer.wrap(data, RP_ID_HASH_LEN + FLAGS_LEN, SIGN_COUNT_LEN)
                .getInt() & 0xffffffffL;

        AttestedCredentialData acd = null;
        int pos = FIXED_LEN;
        if (flags.attestedCredentialDataIncluded()) {
            if (data.length < pos + AAGUID_LEN + 2) {
                throw new AuthDataException("authenticatorData missing attestedCredentialData");
            }
            byte[] aaguid = Arrays.copyOfRange(data, pos, pos + AAGUID_LEN);
            pos += AAGUID_LEN;
            int credIdLen = ((data[pos] & 0xff) << 8) | (data[pos + 1] & 0xff);
            pos += 2;
            if (data.length < pos + credIdLen) {
                throw new AuthDataException("credentialId length exceeds authenticatorData");
            }
            byte[] credId = Arrays.copyOfRange(data, pos, pos + credIdLen);
            pos += credIdLen;

            // 남은 바이트 앞쪽에서 COSE_Key 한 항목을 떼어내고 그 길이를 측정.
            int coseLen;
            try {
                coseLen = CborDecoder.decodeFirstItemLength(data, pos);
            } catch (CborException e) {
                throw new AuthDataException("invalid COSE_Key in attestedCredentialData: " + e.getMessage());
            }
            byte[] coseBytes = Arrays.copyOfRange(data, pos, pos + coseLen);
            CborValue coseMap = CborDecoder.decode(coseBytes);
            pos += coseLen;

            acd = new AttestedCredentialData(aaguid, credId, coseMap, coseBytes);
        }
        // extensions(ED)는 이번 범위에서 파싱하지 않고 무시 (남은 바이트는 검증에 불필요).
        return new AuthenticatorData(rpIdHash, flags, signCount, acd);
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :webauthn:test --tests '*AuthenticatorDataParserTest'`
Expected: PASS (3 tests)

- [ ] **Step 6: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/authdata/ webauthn/src/test/java/com/crosscert/passkey/webauthn/authdata/AuthenticatorDataParserTest.java
git commit -m "feat(webauthn): AuthenticatorDataParser (flags/signCount/attestedCredentialData)"
```

### Task 6: ClientData 검증기

**Files:**
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/clientdata/CollectedClientData.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/clientdata/ClientDataValidator.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/clientdata/ClientDataException.java`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/clientdata/ClientDataValidatorTest.java`

- [ ] **Step 1: 값 타입 + 예외 작성**

`clientdata/ClientDataException.java`:

```java
package com.crosscert.passkey.webauthn.clientdata;

/** clientDataJSON 파싱·검증 실패. reason은 호출부가 상위 예외로 매핑. */
public final class ClientDataException extends RuntimeException {
    public enum Reason { MALFORMED, TYPE_MISMATCH, CHALLENGE_MISMATCH, ORIGIN_MISMATCH }
    private final Reason reason;
    public ClientDataException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
```

`clientdata/CollectedClientData.java`:

```java
package com.crosscert.passkey.webauthn.clientdata;

/** 파싱된 clientDataJSON (WebAuthn §5.8.1). */
public record CollectedClientData(String type, String challenge, String origin, boolean crossOrigin) {}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`clientdata/ClientDataValidatorTest.java`:

```java
package com.crosscert.passkey.webauthn.clientdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ClientDataValidatorTest {

    private final ClientDataValidator validator = new ClientDataValidator(new ObjectMapper());

    private byte[] clientDataJson(String type, String challengeB64url, String origin) {
        String json = "{\"type\":\"" + type + "\",\"challenge\":\"" + challengeB64url
                + "\",\"origin\":\"" + origin + "\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    @Test
    void acceptsValidCreateClientData() {
        byte[] challenge = "the-challenge".getBytes(StandardCharsets.UTF_8);
        byte[] cd = clientDataJson("webauthn.create", b64url(challenge), "https://example.com");

        CollectedClientData parsed = validator.validate(
                cd, "webauthn.create", challenge, Set.of("https://example.com"));

        assertEquals("webauthn.create", parsed.type());
        assertEquals("https://example.com", parsed.origin());
    }

    @Test
    void rejectsWrongType() {
        byte[] challenge = "c".getBytes(StandardCharsets.UTF_8);
        byte[] cd = clientDataJson("webauthn.get", b64url(challenge), "https://example.com");
        ClientDataException ex = assertThrows(ClientDataException.class,
                () -> validator.validate(cd, "webauthn.create", challenge, Set.of("https://example.com")));
        assertEquals(ClientDataException.Reason.TYPE_MISMATCH, ex.reason());
    }

    @Test
    void rejectsWrongChallenge() {
        byte[] cd = clientDataJson("webauthn.create",
                b64url("attacker".getBytes(StandardCharsets.UTF_8)), "https://example.com");
        ClientDataException ex = assertThrows(ClientDataException.class,
                () -> validator.validate(cd, "webauthn.create",
                        "server".getBytes(StandardCharsets.UTF_8), Set.of("https://example.com")));
        assertEquals(ClientDataException.Reason.CHALLENGE_MISMATCH, ex.reason());
    }

    @Test
    void rejectsWrongOrigin() {
        byte[] challenge = "c".getBytes(StandardCharsets.UTF_8);
        byte[] cd = clientDataJson("webauthn.create", b64url(challenge), "https://evil.com");
        ClientDataException ex = assertThrows(ClientDataException.class,
                () -> validator.validate(cd, "webauthn.create", challenge, Set.of("https://example.com")));
        assertEquals(ClientDataException.Reason.ORIGIN_MISMATCH, ex.reason());
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew :webauthn:test --tests '*ClientDataValidatorTest'`
Expected: FAIL — `ClientDataValidator` 없음

- [ ] **Step 4: ClientDataValidator 구현**

`clientdata/ClientDataValidator.java`:

```java
package com.crosscert.passkey.webauthn.clientdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.Base64;
import java.util.Set;

/**
 * clientDataJSON 파싱·검증 (WebAuthn §7.1 step 7~11 / §7.2 step 11~15).
 * challenge는 상수시간 바이트 비교, origin은 화이트리스트 정확 일치.
 */
public final class ClientDataValidator {

    private final ObjectMapper mapper;

    public ClientDataValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param expectedType "webauthn.create"(등록) 또는 "webauthn.get"(인증)
     * @param expectedChallenge 서버가 발급한 challenge 원본 바이트
     * @param allowedOrigins 테넌트 origin 화이트리스트
     */
    public CollectedClientData validate(byte[] clientDataJson, String expectedType,
                                        byte[] expectedChallenge, Set<String> allowedOrigins) {
        JsonNode root;
        try {
            root = mapper.readTree(clientDataJson);
        } catch (Exception e) {
            throw new ClientDataException(ClientDataException.Reason.MALFORMED,
                    "clientDataJSON parse failed");
        }
        String type = text(root, "type");
        String challengeB64 = text(root, "challenge");
        String origin = text(root, "origin");
        boolean crossOrigin = root.hasNonNull("crossOrigin") && root.get("crossOrigin").asBoolean();

        if (!expectedType.equals(type)) {
            throw new ClientDataException(ClientDataException.Reason.TYPE_MISMATCH,
                    "clientData.type expected " + expectedType + " got " + type);
        }

        byte[] gotChallenge;
        try {
            gotChallenge = Base64.getUrlDecoder().decode(challengeB64);
        } catch (RuntimeException e) {
            throw new ClientDataException(ClientDataException.Reason.MALFORMED,
                    "clientData.challenge not base64url");
        }
        if (!java.security.MessageDigest.isEqual(expectedChallenge, gotChallenge)) {
            throw new ClientDataException(ClientDataException.Reason.CHALLENGE_MISMATCH,
                    "clientData.challenge mismatch");
        }

        if (!allowedOrigins.contains(origin)) {
            throw new ClientDataException(ClientDataException.Reason.ORIGIN_MISMATCH,
                    "clientData.origin not allowed: " + origin);
        }

        return new CollectedClientData(type, challengeB64, origin, crossOrigin);
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || !n.isTextual()) {
            throw new ClientDataException(ClientDataException.Reason.MALFORMED,
                    "clientData missing field: " + field);
        }
        return n.asText();
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :webauthn:test --tests '*ClientDataValidatorTest'`
Expected: PASS (4 tests)

- [ ] **Step 6: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/clientdata/ webauthn/src/test/java/com/crosscert/passkey/webauthn/clientdata/ClientDataValidatorTest.java
git commit -m "feat(webauthn): ClientDataValidator (type/challenge/origin 검증)"
```

---

## Phase 2 — 인터페이스 계약 + Trust + Attestation(none/packed)

### Task 7: 경계 계약 타입 (verifier 패키지의 record/enum/예외)

**Files:**
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/verifier/COSEAlgorithm.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/verifier/AttestationTrustPolicy.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/verifier/StoredCredential.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/verifier/RegistrationInput.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/verifier/RegistrationResult.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/verifier/AuthenticationInput.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/verifier/AuthenticationResult.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/verifier/WebAuthnVerificationException.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/verifier/WebAuthnVerifier.java`

- [ ] **Step 1: COSEAlgorithm + AttestationTrustPolicy 작성**

`verifier/COSEAlgorithm.java`:

```java
package com.crosscert.passkey.webauthn.verifier;

/** 앱 경계용 알고리즘 enum — 앱이 allowedAlgorithms로 넘긴다. */
public enum COSEAlgorithm { ES256, RS256 }
```

`verifier/AttestationTrustPolicy.java`:

```java
package com.crosscert.passkey.webauthn.verifier;

/**
 * attestation trust 강제 수준.
 *  - NONE_ONLY: fmt=none만 허용 (attestation trust 검사 안 함)
 *  - SELF_ALLOWED: self/none 허용, x5c가 있으면 서명만 검증(체인 미강제)
 *  - TRUST_CHAIN_REQUIRED: x5c 체인을 TrustAnchorProvider로 검증
 */
public enum AttestationTrustPolicy { NONE_ONLY, SELF_ALLOWED, TRUST_CHAIN_REQUIRED }
```

- [ ] **Step 2: StoredCredential + Input/Result record 작성**

`verifier/StoredCredential.java`:

```java
package com.crosscert.passkey.webauthn.verifier;

/** DB에서 로드한 credential (인증 검증 입력). cosePublicKey는 COSE_Key CBOR 바이트. */
public record StoredCredential(byte[] credentialId, byte[] cosePublicKey, long signCount) {}
```

`verifier/RegistrationInput.java`:

```java
package com.crosscert.passkey.webauthn.verifier;

import java.util.Set;

public record RegistrationInput(
        String credentialJson,          // 클라가 보낸 PublicKeyCredential JSON 전체
        byte[] challenge,               // 서버 발급 challenge
        Set<String> allowedOrigins,
        String rpId,
        boolean userVerificationRequired,
        Set<COSEAlgorithm> allowedAlgorithms,
        Set<String> acceptedAttestationFormats,
        AttestationTrustPolicy trustPolicy
) {}
```

`verifier/RegistrationResult.java`:

```java
package com.crosscert.passkey.webauthn.verifier;

import java.util.Set;

public record RegistrationResult(
        byte[] credentialId,
        byte[] cosePublicKey,           // 저장 스키마 핵심 (COSE_Key CBOR)
        long signCount,
        byte[] aaguid,
        String attestationFormat,
        Set<String> transports,
        boolean uvVerified,
        boolean upVerified,
        boolean backupEligible,
        boolean backupState
) {}
```

`verifier/AuthenticationInput.java`:

```java
package com.crosscert.passkey.webauthn.verifier;

import java.util.Set;

public record AuthenticationInput(
        String credentialJson,
        byte[] challenge,
        Set<String> allowedOrigins,
        String rpId,
        boolean userVerificationRequired,
        StoredCredential storedCredential
) {}
```

`verifier/AuthenticationResult.java`:

```java
package com.crosscert.passkey.webauthn.verifier;

public record AuthenticationResult(
        byte[] credentialId,
        long newSignCount,
        boolean uvVerified,
        boolean upVerified,
        boolean backupState
) {}
```

- [ ] **Step 3: 예외 + 인터페이스 작성**

`verifier/WebAuthnVerificationException.java`:

```java
package com.crosscert.passkey.webauthn.verifier;

/** WebAuthn 검증 실패. reason은 앱이 ErrorCode로 매핑한다. */
public final class WebAuthnVerificationException extends Exception {

    public enum Reason {
        MALFORMED_INPUT,
        BAD_SIGNATURE,
        ORIGIN_MISMATCH,
        CHALLENGE_MISMATCH,
        TYPE_MISMATCH,
        RP_ID_HASH_MISMATCH,
        UP_REQUIRED,
        UV_REQUIRED,
        UNSUPPORTED_ALGORITHM,
        UNSUPPORTED_ATTESTATION_FORMAT,
        ATTESTATION_FORMAT_NOT_ACCEPTED,
        ATTESTATION_UNTRUSTED
    }

    private final Reason reason;

    public WebAuthnVerificationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public WebAuthnVerificationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
```

`verifier/WebAuthnVerifier.java`:

```java
package com.crosscert.passkey.webauthn.verifier;

/**
 * 앱과 검증 구현 사이의 유일한 경계. webauthn4j 타입은 이 경계를
 * 절대 넘지 않는다. 구현체: NativeWebAuthnVerifier(프로덕션),
 * Webauthn4jVerifier(differential 테스트 전용).
 */
public interface WebAuthnVerifier {
    RegistrationResult verifyRegistration(RegistrationInput input)
            throws WebAuthnVerificationException;

    AuthenticationResult verifyAuthentication(AuthenticationInput input)
            throws WebAuthnVerificationException;
}
```

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew :webauthn:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/verifier/
git commit -m "feat(webauthn): WebAuthnVerifier 인터페이스 + 경계 계약 타입(Input/Result/Reason)"
```

### Task 8: Trust 인프라 (TrustAnchorProvider + CertPathVerifier)

**Files:**
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/trust/TrustAnchorProvider.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/trust/EmptyTrustAnchorProvider.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/trust/CertPathVerifier.java`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/trust/CertPathVerifierTest.java`

- [ ] **Step 1: TrustAnchorProvider + Empty 구현 작성**

`trust/TrustAnchorProvider.java`:

```java
package com.crosscert.passkey.webauthn.trust;

import java.security.cert.TrustAnchor;
import java.util.Set;

/**
 * attestation 체인 검증의 루트 신뢰 앵커 공급자. 본래 MDS에서 오지만
 * 이번 범위에서는 인터페이스만 두고, MDS 서브프로젝트가 구현해 끼운다.
 *
 * @param aaguid 등록 authenticator의 AAGUID (16바이트, nullable)
 * @param attestationFormat "packed", "tpm" 등
 */
public interface TrustAnchorProvider {
    Set<TrustAnchor> trustAnchors(byte[] aaguid, String attestationFormat);
}
```

`trust/EmptyTrustAnchorProvider.java`:

```java
package com.crosscert.passkey.webauthn.trust;

import java.security.cert.TrustAnchor;
import java.util.Set;

/** 신뢰 앵커 없음 — TRUST_CHAIN_REQUIRED를 만족시키지 못하므로 self/none만 통과. */
public final class EmptyTrustAnchorProvider implements TrustAnchorProvider {
    @Override
    public Set<TrustAnchor> trustAnchors(byte[] aaguid, String attestationFormat) {
        return Set.of();
    }
}
```

- [ ] **Step 2: 실패하는 테스트 작성 (self-signed 루트로 leaf 검증)**

`trust/CertPathVerifierTest.java`:

```java
package com.crosscert.passkey.webauthn.trust;

import org.junit.jupiter.api.Test;

import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CertPathVerifierTest {

    @Test
    void acceptsChainToTrustedRoot() throws Exception {
        TestCa ca = TestCa.create();                 // 헬퍼: 루트 + leaf 발급
        X509Certificate leaf = ca.issueLeaf("CN=authenticator");

        CertPathVerifier verifier = new CertPathVerifier();
        boolean ok = verifier.verify(
                List.of(leaf),
                Set.of(new TrustAnchor(ca.root(), null)));

        assertTrue(ok);
    }

    @Test
    void rejectsChainWithUntrustedRoot() throws Exception {
        TestCa ca = TestCa.create();
        TestCa otherCa = TestCa.create();
        X509Certificate leaf = ca.issueLeaf("CN=authenticator");

        CertPathVerifier verifier = new CertPathVerifier();
        boolean ok = verifier.verify(
                List.of(leaf),
                Set.of(new TrustAnchor(otherCa.root(), null)));  // 잘못된 루트

        assertFalse(ok);
    }
}
```

> **참고:** `TestCa`는 다음 step에서 작성하는 테스트 헬퍼다. JDK만으로 self-signed 인증서를 만들기는 번거로우므로, 이 헬퍼는 테스트 소스셋에서 `sun.security.x509`/`sun.security.tools.keytool` 대신 **BouncyCastle 테스트 의존**을 쓰는 것이 가장 단순하다. `webauthn/build.gradle.kts`의 testImplementation에 `org.bouncycastle:bcpkix-jdk18on:1.78.1`을 추가한다 (테스트 전용 — 프로덕션 무관).

- [ ] **Step 3: TestCa 헬퍼 작성 + BouncyCastle 테스트 의존 추가**

`webauthn/build.gradle.kts`의 dependencies에 추가:

```kotlin
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
```

`webauthn/src/test/java/com/crosscert/passkey/webauthn/trust/TestCa.java`:

```java
package com.crosscert.passkey.webauthn.trust;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;

/** 테스트용 미니 CA — self-signed 루트와 그 루트가 서명한 leaf를 발급. */
final class TestCa {

    private final KeyPair rootKeyPair;
    private final X509Certificate root;

    private TestCa(KeyPair rootKeyPair, X509Certificate root) {
        this.rootKeyPair = rootKeyPair;
        this.root = root;
    }

    static TestCa create() throws Exception {
        KeyPair kp = ecKeyPair();
        X509Certificate root = selfSign(kp, "CN=test-root");
        return new TestCa(kp, root);
    }

    X509Certificate root() { return root; }

    X509Certificate issueLeaf(String subjectDn) throws Exception {
        KeyPair leafKp = ecKeyPair();
        X500Name issuer = new X500Name("CN=test-root");
        X500Name subject = new X500Name(subjectDn);
        var builder = new JcaX509v3CertificateBuilder(
                issuer, BigInteger.valueOf(2),
                new Date(System.currentTimeMillis() - 86400_000L),
                new Date(System.currentTimeMillis() + 86400_000L),
                subject, leafKp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(rootKeyPair.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static X509Certificate selfSign(KeyPair kp, String dn) throws Exception {
        X500Name name = new X500Name(dn);
        var builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.ONE,
                new Date(System.currentTimeMillis() - 86400_000L),
                new Date(System.currentTimeMillis() + 86400_000L),
                name, kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static KeyPair ecKeyPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }
}
```

- [ ] **Step 4: 테스트 실패 확인**

Run: `./gradlew :webauthn:test --tests '*CertPathVerifierTest'`
Expected: FAIL — `CertPathVerifier` 없음

- [ ] **Step 5: CertPathVerifier 구현**

`trust/CertPathVerifier.java`:

```java
package com.crosscert.passkey.webauthn.trust;

import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

/**
 * X.509 인증서 체인 검증 (leaf → 루트). JDK CertPathValidator(PKIX) 래핑.
 * 폐기(CRL/OCSP) 검사는 비활성 — attestation 인증서는 보통 폐기 정보가 없고,
 * 디바이스 신뢰는 MDS status report로 별도 관리하기 때문이다.
 */
public final class CertPathVerifier {

    /** chain[0]=leaf 순서. anchors가 비면 항상 false. 검증 실패 시 false(예외 흡수). */
    public boolean verify(List<X509Certificate> chain, Set<TrustAnchor> anchors) {
        if (anchors == null || anchors.isEmpty() || chain == null || chain.isEmpty()) {
            return false;
        }
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            CertPath path = cf.generateCertPath(chain);
            PKIXParameters params = new PKIXParameters(anchors);
            params.setRevocationEnabled(false);
            CertPathValidator.getInstance("PKIX").validate(path, params);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew :webauthn:test --tests '*CertPathVerifierTest'`
Expected: PASS (2 tests)

- [ ] **Step 7: Commit**

```bash
git add webauthn/build.gradle.kts webauthn/src/main/java/com/crosscert/passkey/webauthn/trust/ webauthn/src/test/java/com/crosscert/passkey/webauthn/trust/
git commit -m "feat(webauthn): TrustAnchorProvider + CertPathVerifier(PKIX) + TestCa 헬퍼"
```

### Task 9: AttestationVerifier 인터페이스 + none 포맷

**Files:**
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AttestationResult.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AttestationException.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AttestationVerifier.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/NoneAttestationVerifier.java`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/attestation/NoneAttestationVerifierTest.java`

- [ ] **Step 1: AttestationResult + 예외 + 인터페이스 작성**

`attestation/AttestationResult.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import java.security.cert.X509Certificate;
import java.util.List;

/** attestation 검증 결과 — trust type과 (있으면) 체인. */
public record AttestationResult(Type type, List<X509Certificate> trustPath) {
    public enum Type { NONE, SELF, BASIC, ATT_CA }

    public static AttestationResult none() { return new AttestationResult(Type.NONE, List.of()); }
    public static AttestationResult self() { return new AttestationResult(Type.SELF, List.of()); }
    public static AttestationResult basic(List<X509Certificate> path) {
        return new AttestationResult(Type.BASIC, path);
    }
}
```

`attestation/AttestationException.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

/** attestation statement 검증 실패. */
public final class AttestationException extends RuntimeException {
    public AttestationException(String message) { super(message); }
    public AttestationException(String message, Throwable cause) { super(message, cause); }
}
```

`attestation/AttestationVerifier.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.cbor.CborValue;

/**
 * 포맷별 attestation statement 검증기 (WebAuthn §8).
 * attStmt 서명을 검증하고 trust path를 돌려준다. trust anchor 강제는
 * 상위(NativeWebAuthnVerifier)가 정책에 따라 수행한다.
 *
 * @param rawAuthData authData의 원시 바이트 (서명 입력 재구성에 필요)
 * @param attStmt 디코드된 attStmt CBOR map
 * @param clientDataHash SHA-256(rawClientDataJSON)
 */
public interface AttestationVerifier {
    String format();
    AttestationResult verify(AuthenticatorData authData, byte[] rawAuthData,
                             CborValue attStmt, byte[] clientDataHash);
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`attestation/NoneAttestationVerifierTest.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.cbor.CborValue.CborMap;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

class NoneAttestationVerifierTest {

    private final NoneAttestationVerifier verifier = new NoneAttestationVerifier();

    @Test
    void formatIsNone() {
        assertEquals("none", verifier.format());
    }

    @Test
    void acceptsEmptyAttStmt() {
        CborMap empty = new CborMap(new LinkedHashMap<>());
        AttestationResult r = verifier.verify(null, new byte[0], empty, new byte[32]);
        assertEquals(AttestationResult.Type.NONE, r.type());
    }

    @Test
    void rejectsNonEmptyAttStmt() {
        LinkedHashMap<com.crosscert.passkey.webauthn.cbor.CborValue,
                com.crosscert.passkey.webauthn.cbor.CborValue> m = new LinkedHashMap<>();
        m.put(new com.crosscert.passkey.webauthn.cbor.CborValue.CborText("x"),
                new com.crosscert.passkey.webauthn.cbor.CborValue.CborInt(1));
        CborMap nonEmpty = new CborMap(m);
        assertThrows(AttestationException.class,
                () -> verifier.verify(null, new byte[0], nonEmpty, new byte[32]));
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew :webauthn:test --tests '*NoneAttestationVerifierTest'`
Expected: FAIL — `NoneAttestationVerifier` 없음

- [ ] **Step 4: NoneAttestationVerifier 구현**

`attestation/NoneAttestationVerifier.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.CborMap;

/** fmt=none (WebAuthn §8.7) — attStmt는 반드시 빈 map. */
public final class NoneAttestationVerifier implements AttestationVerifier {

    @Override
    public String format() { return "none"; }

    @Override
    public AttestationResult verify(AuthenticatorData authData, byte[] rawAuthData,
                                    CborValue attStmt, byte[] clientDataHash) {
        if (!(attStmt instanceof CborMap m) || !m.entries().isEmpty()) {
            throw new AttestationException("none attestation must have empty attStmt");
        }
        return AttestationResult.none();
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :webauthn:test --tests '*NoneAttestationVerifierTest'`
Expected: PASS (3 tests)

- [ ] **Step 6: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/ webauthn/src/test/java/com/crosscert/passkey/webauthn/attestation/NoneAttestationVerifierTest.java
git commit -m "feat(webauthn): AttestationVerifier 인터페이스 + none 포맷"
```

### Task 10: packed attestation (self + x5c)

**Files:**
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/PackedAttestationVerifier.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AttestationSignatures.java`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/attestation/PackedAttestationVerifierTest.java`

- [ ] **Step 1: 서명 검증 공통 헬퍼 작성**

`attestation/AttestationSignatures.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import java.security.PublicKey;
import java.security.Signature;

/** attestation 서명 검증 공통 로직 — 알고리즘명으로 JDK Signature 사용. */
final class AttestationSignatures {

    private AttestationSignatures() {}

    /** signed = authData || clientDataHash. jcaName 예: "SHA256withECDSA". */
    static boolean verify(String jcaName, PublicKey key, byte[] rawAuthData,
                          byte[] clientDataHash, byte[] signature) {
        try {
            Signature sig = Signature.getInstance(jcaName);
            sig.initVerify(key);
            sig.update(rawAuthData);
            sig.update(clientDataHash);
            return sig.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **Step 2: 실패하는 테스트 작성 (self-attestation, ES256으로 직접 서명)**

`attestation/PackedAttestationVerifierTest.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.authdata.AuthenticatorDataParser;
import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PackedAttestationVerifierTest {

    private final PackedAttestationVerifier verifier = new PackedAttestationVerifier();

    @Test
    void formatIsPacked() {
        assertEquals("packed", verifier.format());
    }

    @Test
    void acceptsValidSelfAttestation() throws Exception {
        // 1) 자격증명 키쌍 (self-attestation은 이 키로 서명)
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair credKp = g.generateKeyPair();
        ECPublicKey credPub = (ECPublicKey) credKp.getPublic();

        // 2) COSE_Key 구성 (ES256)
        byte[] x = fixed32(credPub.getW().getAffineX());
        byte[] y = fixed32(credPub.getW().getAffineY());
        byte[] cose = es256Cose(x, y);

        // 3) authData 구성 (AT 플래그)
        byte[] rawAuthData = authDataWithCredential(cose);
        AuthenticatorData authData = AuthenticatorDataParser.parse(rawAuthData);

        // 4) clientDataHash + signed = authData||hash 를 credKp로 서명
        byte[] clientDataHash = new byte[32];
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(credKp.getPrivate());
        sig.update(rawAuthData);
        sig.update(clientDataHash);
        byte[] signature = sig.sign();

        // 5) attStmt = { alg: -7, sig: <signature> }  (x5c 없음 = self)
        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborText("alg"), new CborInt(-7));
        m.put(new CborText("sig"), new CborBytes(signature));
        CborMap attStmt = new CborMap(m);

        AttestationResult r = verifier.verify(authData, rawAuthData, attStmt, clientDataHash);
        assertEquals(AttestationResult.Type.SELF, r.type());
    }

    @Test
    void rejectsTamperedSelfAttestation() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair credKp = g.generateKeyPair();
        ECPublicKey credPub = (ECPublicKey) credKp.getPublic();
        byte[] cose = es256Cose(fixed32(credPub.getW().getAffineX()), fixed32(credPub.getW().getAffineY()));
        byte[] rawAuthData = authDataWithCredential(cose);
        AuthenticatorData authData = AuthenticatorDataParser.parse(rawAuthData);

        // 잘못된 서명 (랜덤 바이트)
        Map<CborValue, CborValue> m = new LinkedHashMap<>();
        m.put(new CborText("alg"), new CborInt(-7));
        m.put(new CborText("sig"), new CborBytes(new byte[64]));
        CborMap attStmt = new CborMap(m);

        assertThrows(AttestationException.class,
                () -> verifier.verify(authData, rawAuthData, attStmt, new byte[32]));
    }

    // --- 헬퍼 ---

    private static byte[] es256Cose(byte[] x, byte[] y) {
        // CBOR: a5 0102 0326 20 01 21 5820<x> 22 5820<y>
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xa5);
        o.write(0x01); o.write(0x02);             // 1:2 (EC2)
        o.write(0x03); o.write(0x26);             // 3:-7 (ES256)
        o.write(0x20); o.write(0x01);             // -1:1 (P-256)
        o.write(0x21); o.write(0x58); o.write(0x20); o.writeBytes(x); // -2: bytes(32)
        o.write(0x22); o.write(0x58); o.write(0x20); o.writeBytes(y); // -3: bytes(32)
        return o.toByteArray();
    }

    private static byte[] authDataWithCredential(byte[] cose) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(new byte[32]);                 // rpIdHash
        o.write(0x41);                              // UP + AT
        o.writeBytes(new byte[]{0, 0, 0, 0});       // signCount
        o.writeBytes(new byte[16]);                 // aaguid
        o.writeBytes(new byte[]{0, 4});             // credIdLen = 4
        o.writeBytes(new byte[]{1, 2, 3, 4});       // credId
        o.writeBytes(cose);                         // COSE_Key
        return o.toByteArray();
    }

    private static byte[] fixed32(java.math.BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew :webauthn:test --tests '*PackedAttestationVerifierTest'`
Expected: FAIL — `PackedAttestationVerifier` 없음

- [ ] **Step 4: PackedAttestationVerifier 구현**

`attestation/PackedAttestationVerifier.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.*;
import com.crosscert.passkey.webauthn.cose.CoseAlgorithm;
import com.crosscert.passkey.webauthn.cose.CoseKey;
import com.crosscert.passkey.webauthn.cose.CoseKeyParser;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * fmt=packed (WebAuthn §8.2).
 *  - x5c 있음: leaf 인증서 공개키로 sig 검증 → BASIC (체인은 상위가 trust anchor로 검증)
 *  - x5c 없음(self): credential 공개키로 sig 검증, alg가 credential alg와 일치 → SELF
 */
public final class PackedAttestationVerifier implements AttestationVerifier {

    @Override
    public String format() { return "packed"; }

    @Override
    public AttestationResult verify(AuthenticatorData authData, byte[] rawAuthData,
                                    CborValue attStmt, byte[] clientDataHash) {
        long alg = requireInt(attStmt.get("alg"), "alg");
        byte[] signature = requireBytes(attStmt.get("sig"), "sig");
        CborValue x5c = attStmt.get("x5c");

        CoseAlgorithm coseAlg = CoseAlgorithm.fromCoseValue(alg);

        if (x5c instanceof CborArray arr && !arr.items().isEmpty()) {
            // x5c 경로: leaf 인증서로 검증
            List<X509Certificate> chain = parseChain(arr);
            X509Certificate leaf = chain.get(0);
            boolean ok = AttestationSignatures.verify(
                    coseAlg.jcaSignatureName(), leaf.getPublicKey(),
                    rawAuthData, clientDataHash, signature);
            if (!ok) throw new AttestationException("packed x5c signature invalid");
            return AttestationResult.basic(chain);
        }

        // self 경로: credential 공개키로 검증, alg 일치 강제
        if (authData.attestedCredentialData() == null) {
            throw new AttestationException("packed self attestation requires attestedCredentialData");
        }
        CoseKey credKey = CoseKeyParser.parse(authData.attestedCredentialData().coseKeyMap());
        if (credKey.algorithm() != coseAlg) {
            throw new AttestationException("packed self attestation alg mismatch");
        }
        boolean ok = AttestationSignatures.verify(
                coseAlg.jcaSignatureName(), credKey.publicKey(),
                rawAuthData, clientDataHash, signature);
        if (!ok) throw new AttestationException("packed self signature invalid");
        return AttestationResult.self();
    }

    private static List<X509Certificate> parseChain(CborArray arr) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = new ArrayList<>();
            for (CborValue c : arr.items()) {
                if (!(c instanceof CborBytes b)) {
                    throw new AttestationException("x5c entry not a byte string");
                }
                chain.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(b.value())));
            }
            return chain;
        } catch (AttestationException e) {
            throw e;
        } catch (Exception e) {
            throw new AttestationException("x5c parse failed", e);
        }
    }

    private static long requireInt(CborValue v, String f) {
        if (v instanceof CborInt i) return i.value();
        throw new AttestationException("packed attStmt missing int: " + f);
    }

    private static byte[] requireBytes(CborValue v, String f) {
        if (v instanceof CborBytes b) return b.value();
        throw new AttestationException("packed attStmt missing bytes: " + f);
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :webauthn:test --tests '*PackedAttestationVerifierTest'`
Expected: PASS (3 tests)

- [ ] **Step 6: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/PackedAttestationVerifier.java webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AttestationSignatures.java webauthn/src/test/java/com/crosscert/passkey/webauthn/attestation/PackedAttestationVerifierTest.java
git commit -m "feat(webauthn): packed attestation (self + x5c) 검증"
```

### Task 11: AttestationVerifiers 레지스트리

**Files:**
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AttestationVerifiers.java`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/attestation/AttestationVerifiersTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`attestation/AttestationVerifiersTest.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AttestationVerifiersTest {

    private final AttestationVerifiers registry = AttestationVerifiers.defaults();

    @Test
    void resolvesNone() {
        assertEquals("none", registry.forFormat("none").format());
    }

    @Test
    void resolvesPacked() {
        assertEquals("packed", registry.forFormat("packed").format());
    }

    @Test
    void unknownFormatReturnsNull() {
        assertNull(registry.forFormat("no-such-format"));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :webauthn:test --tests '*AttestationVerifiersTest'`
Expected: FAIL — `AttestationVerifiers` 없음

- [ ] **Step 3: AttestationVerifiers 구현 (현재 등록된 포맷만)**

`attestation/AttestationVerifiers.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** fmt 문자열 → AttestationVerifier 레지스트리. */
public final class AttestationVerifiers {

    private final Map<String, AttestationVerifier> byFormat;

    private AttestationVerifiers(List<AttestationVerifier> verifiers) {
        Map<String, AttestationVerifier> m = new LinkedHashMap<>();
        for (AttestationVerifier v : verifiers) m.put(v.format(), v);
        this.byFormat = m;
    }

    /** 현재 구현된 포맷 전부 등록. 포맷 확장 시 이 목록에 추가. */
    public static AttestationVerifiers defaults() {
        return new AttestationVerifiers(List.of(
                new NoneAttestationVerifier(),
                new PackedAttestationVerifier()));
    }

    public static AttestationVerifiers of(List<AttestationVerifier> verifiers) {
        return new AttestationVerifiers(verifiers);
    }

    /** 미지원 포맷이면 null. */
    public AttestationVerifier forFormat(String format) {
        return byFormat.get(format);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :webauthn:test --tests '*AttestationVerifiersTest'`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AttestationVerifiers.java webauthn/src/test/java/com/crosscert/passkey/webauthn/attestation/AttestationVerifiersTest.java
git commit -m "feat(webauthn): AttestationVerifiers 레지스트리"
```

---

## Phase 3 — NativeWebAuthnVerifier 오케스트레이터

### Task 12: 등록 JSON 파서 (credentialJson → 원시 구성요소)

**Files:**
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/verifier/CredentialJsonParser.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/verifier/ParsedRegistration.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/verifier/ParsedAuthentication.java`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/verifier/CredentialJsonParserTest.java`

- [ ] **Step 1: 파싱 결과 타입 작성**

`verifier/ParsedRegistration.java`:

```java
package com.crosscert.passkey.webauthn.verifier;

import java.util.Set;

/** registration credentialJson에서 떼어낸 원시 구성요소. */
public record ParsedRegistration(
        byte[] rawId,
        byte[] clientDataJson,
        byte[] attestationObject,
        Set<String> transports
) {}
```

`verifier/ParsedAuthentication.java`:

```java
package com.crosscert.passkey.webauthn.verifier;

/** authentication credentialJson에서 떼어낸 원시 구성요소. */
public record ParsedAuthentication(
        byte[] rawId,
        byte[] clientDataJson,
        byte[] authenticatorData,
        byte[] signature,
        byte[] userHandle   // nullable
) {}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`verifier/CredentialJsonParserTest.java`:

```java
package com.crosscert.passkey.webauthn.verifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class CredentialJsonParserTest {

    private final CredentialJsonParser parser = new CredentialJsonParser(new ObjectMapper());
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    @Test
    void parsesRegistrationJson() {
        String json = "{"
                + "\"id\":\"" + B64.encodeToString(new byte[]{1, 2}) + "\","
                + "\"rawId\":\"" + B64.encodeToString(new byte[]{1, 2}) + "\","
                + "\"type\":\"public-key\","
                + "\"response\":{"
                + "  \"clientDataJSON\":\"" + B64.encodeToString("{}".getBytes()) + "\","
                + "  \"attestationObject\":\"" + B64.encodeToString(new byte[]{9}) + "\","
                + "  \"transports\":[\"usb\",\"nfc\"]"
                + "}}";
        ParsedRegistration p = parser.parseRegistration(json);
        assertArrayEquals(new byte[]{1, 2}, p.rawId());
        assertArrayEquals(new byte[]{9}, p.attestationObject());
        assertTrue(p.transports().contains("usb"));
        assertTrue(p.transports().contains("nfc"));
    }

    @Test
    void parsesAuthenticationJson() {
        String json = "{"
                + "\"id\":\"" + B64.encodeToString(new byte[]{1}) + "\","
                + "\"rawId\":\"" + B64.encodeToString(new byte[]{1}) + "\","
                + "\"type\":\"public-key\","
                + "\"response\":{"
                + "  \"clientDataJSON\":\"" + B64.encodeToString("{}".getBytes()) + "\","
                + "  \"authenticatorData\":\"" + B64.encodeToString(new byte[]{7}) + "\","
                + "  \"signature\":\"" + B64.encodeToString(new byte[]{8}) + "\""
                + "}}";
        ParsedAuthentication p = parser.parseAuthentication(json);
        assertArrayEquals(new byte[]{7}, p.authenticatorData());
        assertArrayEquals(new byte[]{8}, p.signature());
        assertNull(p.userHandle());
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(RuntimeException.class, () -> parser.parseRegistration("not-json"));
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew :webauthn:test --tests '*CredentialJsonParserTest'`
Expected: FAIL — `CredentialJsonParser` 없음

- [ ] **Step 4: CredentialJsonParser 구현**

`verifier/CredentialJsonParser.java`:

```java
package com.crosscert.passkey.webauthn.verifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * WebAuthn PublicKeyCredential JSON(WebAuthn §5.1, base64url 필드)을
 * 원시 바이트 구성요소로 분해. 검증은 하지 않고 추출만 한다.
 */
public final class CredentialJsonParser {

    private final ObjectMapper mapper;
    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();

    public CredentialJsonParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ParsedRegistration parseRegistration(String json) {
        JsonNode root = readTree(json);
        JsonNode resp = require(root, "response");
        Set<String> transports = new LinkedHashSet<>();
        JsonNode tr = resp.get("transports");
        if (tr != null && tr.isArray()) {
            for (JsonNode t : tr) if (t.isTextual()) transports.add(t.asText());
        }
        return new ParsedRegistration(
                decode(require(root, "rawId").asText()),
                decode(require(resp, "clientDataJSON").asText()),
                decode(require(resp, "attestationObject").asText()),
                transports);
    }

    public ParsedAuthentication parseAuthentication(String json) {
        JsonNode root = readTree(json);
        JsonNode resp = require(root, "response");
        JsonNode uh = resp.get("userHandle");
        byte[] userHandle = (uh != null && uh.isTextual() && !uh.asText().isEmpty())
                ? decode(uh.asText()) : null;
        return new ParsedAuthentication(
                decode(require(root, "rawId").asText()),
                decode(require(resp, "clientDataJSON").asText()),
                decode(require(resp, "authenticatorData").asText()),
                decode(require(resp, "signature").asText()),
                userHandle);
    }

    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("credential JSON parse failed", e);
        }
    }

    private static JsonNode require(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            throw new IllegalArgumentException("credential JSON missing field: " + field);
        }
        return n;
    }

    private static byte[] decode(String b64url) {
        try {
            return B64URL.decode(b64url);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("credential JSON field not base64url");
        }
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :webauthn:test --tests '*CredentialJsonParserTest'`
Expected: PASS (3 tests)

- [ ] **Step 6: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/verifier/CredentialJsonParser.java webauthn/src/main/java/com/crosscert/passkey/webauthn/verifier/ParsedRegistration.java webauthn/src/main/java/com/crosscert/passkey/webauthn/verifier/ParsedAuthentication.java webauthn/src/test/java/com/crosscert/passkey/webauthn/verifier/CredentialJsonParserTest.java
git commit -m "feat(webauthn): CredentialJsonParser (PublicKeyCredential JSON 분해)"
```

### Task 13: AttestationObject 디코더

**Files:**
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AttestationObject.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AttestationObjectDecoder.java`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/attestation/AttestationObjectDecoderTest.java`

- [ ] **Step 1: AttestationObject 타입 작성**

`attestation/AttestationObject.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.cbor.CborValue;

/** 디코드된 attestationObject (WebAuthn §6.5): fmt + authData + attStmt. */
public record AttestationObject(
        String format,
        byte[] rawAuthData,
        AuthenticatorData authData,
        CborValue attStmt
) {}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`attestation/AttestationObjectDecoderTest.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class AttestationObjectDecoderTest {

    @Test
    void decodesNoneAttestationObject() {
        // attestationObject = {"fmt":"none","attStmt":{},"authData":<bytes>}
        byte[] authData = new byte[37]; // rpIdHash(32)+flags(1)+signCount(4), flags=0
        byte[] ao = cborMap3(authData);

        AttestationObject obj = AttestationObjectDecoder.decode(ao);

        assertEquals("none", obj.format());
        assertArrayEquals(authData, obj.rawAuthData());
        assertNotNull(obj.authData());
    }

    /** {"authData":h'..', "fmt":"none", "attStmt":{}} 를 손으로 CBOR 인코드. */
    private static byte[] cborMap3(byte[] authData) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xa3); // map(3)
        // "fmt":"none"
        o.write(0x63); o.writeBytes("fmt".getBytes());
        o.write(0x64); o.writeBytes("none".getBytes());
        // "attStmt":{}
        o.write(0x67); o.writeBytes("attStmt".getBytes());
        o.write(0xa0);
        // "authData": bytes
        o.write(0x68); o.writeBytes("authData".getBytes());
        o.write(0x58); o.write(authData.length); o.writeBytes(authData);
        return o.toByteArray();
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew :webauthn:test --tests '*AttestationObjectDecoderTest'`
Expected: FAIL — `AttestationObjectDecoder` 없음

- [ ] **Step 4: AttestationObjectDecoder 구현**

`attestation/AttestationObjectDecoder.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.authdata.AuthenticatorDataParser;
import com.crosscert.passkey.webauthn.cbor.CborDecoder;
import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.*;

/** attestationObject CBOR map → AttestationObject. */
public final class AttestationObjectDecoder {

    private AttestationObjectDecoder() {}

    public static AttestationObject decode(byte[] attestationObject) {
        CborValue root = CborDecoder.decode(attestationObject);
        if (!(root instanceof CborMap)) {
            throw new AttestationException("attestationObject is not a CBOR map");
        }
        CborValue fmtNode = root.get("fmt");
        if (!(fmtNode instanceof CborText fmt)) {
            throw new AttestationException("attestationObject.fmt missing");
        }
        CborValue authDataNode = root.get("authData");
        if (!(authDataNode instanceof CborBytes ad)) {
            throw new AttestationException("attestationObject.authData missing");
        }
        CborValue attStmt = root.get("attStmt");
        if (attStmt == null) {
            throw new AttestationException("attestationObject.attStmt missing");
        }
        AuthenticatorData parsed = AuthenticatorDataParser.parse(ad.value());
        return new AttestationObject(fmt.value(), ad.value(), parsed, attStmt);
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :webauthn:test --tests '*AttestationObjectDecoderTest'`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AttestationObject.java webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AttestationObjectDecoder.java webauthn/src/test/java/com/crosscert/passkey/webauthn/attestation/AttestationObjectDecoderTest.java
git commit -m "feat(webauthn): AttestationObjectDecoder (fmt/authData/attStmt)"
```

### Task 14: NativeWebAuthnVerifier — 등록 검증

**Files:**
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/verifier/NativeWebAuthnVerifier.java`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/verifier/NativeRegistrationTest.java`

- [ ] **Step 1: 실패하는 테스트 작성 (none attestation 등록 end-to-end)**

`verifier/NativeRegistrationTest.java`:

```java
package com.crosscert.passkey.webauthn.verifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NativeRegistrationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final NativeWebAuthnVerifier verifier = NativeWebAuthnVerifier.withDefaults(mapper);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    @Test
    void verifiesNoneRegistration() throws Exception {
        String rpId = "example.com";
        String origin = "https://example.com";
        byte[] challenge = "reg-challenge".getBytes(StandardCharsets.UTF_8);

        // clientDataJSON
        String clientData = "{\"type\":\"webauthn.create\",\"challenge\":\""
                + B64.encodeToString(challenge) + "\",\"origin\":\"" + origin + "\"}";
        byte[] clientDataBytes = clientData.getBytes(StandardCharsets.UTF_8);

        // authData: rpIdHash(SHA-256(rpId)) + flags(UP+AT) + signCount + attestedCredentialData
        byte[] rpIdHash = MessageDigest.getInstance("SHA-256").digest(rpId.getBytes(StandardCharsets.UTF_8));
        byte[] cose = TestCose.es256(); // 임의 유효 COSE_Key
        byte[] authData = buildAuthData(rpIdHash, cose);

        // attestationObject = {"fmt":"none","attStmt":{},"authData":authData}
        byte[] ao = TestAttestationObject.none(authData);

        String credJson = "{\"id\":\"AQ\",\"rawId\":\"AQ\",\"type\":\"public-key\",\"response\":{"
                + "\"clientDataJSON\":\"" + B64.encodeToString(clientDataBytes) + "\","
                + "\"attestationObject\":\"" + B64.encodeToString(ao) + "\"}}";

        RegistrationInput input = new RegistrationInput(
                credJson, challenge, Set.of(origin), rpId,
                false, Set.of(COSEAlgorithm.ES256), Set.of("none"),
                AttestationTrustPolicy.NONE_ONLY);

        RegistrationResult result = verifier.verifyRegistration(input);

        assertNotNull(result.credentialId());
        assertArrayEquals(cose, result.cosePublicKey());
        assertEquals("none", result.attestationFormat());
        assertTrue(result.upVerified());
    }

    @Test
    void rejectsWrongChallenge() throws Exception {
        String rpId = "example.com";
        String origin = "https://example.com";
        String clientData = "{\"type\":\"webauthn.create\",\"challenge\":\""
                + B64.encodeToString("attacker".getBytes()) + "\",\"origin\":\"" + origin + "\"}";
        byte[] rpIdHash = MessageDigest.getInstance("SHA-256").digest(rpId.getBytes());
        byte[] ao = TestAttestationObject.none(buildAuthData(rpIdHash, TestCose.es256()));
        String credJson = "{\"id\":\"AQ\",\"rawId\":\"AQ\",\"type\":\"public-key\",\"response\":{"
                + "\"clientDataJSON\":\"" + B64.encodeToString(clientData.getBytes()) + "\","
                + "\"attestationObject\":\"" + B64.encodeToString(ao) + "\"}}";

        RegistrationInput input = new RegistrationInput(
                credJson, "server".getBytes(), Set.of(origin), rpId,
                false, Set.of(COSEAlgorithm.ES256), Set.of("none"),
                AttestationTrustPolicy.NONE_ONLY);

        WebAuthnVerificationException ex = assertThrows(WebAuthnVerificationException.class,
                () -> verifier.verifyRegistration(input));
        assertEquals(WebAuthnVerificationException.Reason.CHALLENGE_MISMATCH, ex.reason());
    }

    private static byte[] buildAuthData(byte[] rpIdHash, byte[] cose) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(rpIdHash);
        o.write(0x41); // UP + AT
        o.writeBytes(new byte[]{0, 0, 0, 0});
        o.writeBytes(new byte[16]);          // aaguid
        o.writeBytes(new byte[]{0, 1});      // credIdLen = 1
        o.write(0x01);                       // credId
        o.writeBytes(cose);
        return o.toByteArray();
    }
}
```

> **참고:** `TestCose.es256()`와 `TestAttestationObject.none(...)`는 테스트 헬퍼다. `TestCose`는 Task 10 테스트의 `es256Cose`와 동일한 인코딩을, `TestAttestationObject`는 Task 13 테스트의 `cborMap3`와 동일한 인코딩을 재사용한다. 두 헬퍼를 `webauthn/src/test/java/com/crosscert/passkey/webauthn/verifier/` 아래 작은 클래스로 만든다.

- [ ] **Step 2: 테스트 헬퍼 작성**

`verifier/TestCose.java`:

```java
package com.crosscert.passkey.webauthn.verifier;

import java.io.ByteArrayOutputStream;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;

/** 테스트용 유효 ES256 COSE_Key 바이트 생성. */
final class TestCose {
    static byte[] es256() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            ECPublicKey pub = (ECPublicKey) g.generateKeyPair().getPublic();
            byte[] x = fixed32(pub.getW().getAffineX());
            byte[] y = fixed32(pub.getW().getAffineY());
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write(0xa5);
            o.write(0x01); o.write(0x02);
            o.write(0x03); o.write(0x26);
            o.write(0x20); o.write(0x01);
            o.write(0x21); o.write(0x58); o.write(0x20); o.writeBytes(x);
            o.write(0x22); o.write(0x58); o.write(0x20); o.writeBytes(y);
            return o.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] fixed32(java.math.BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }
}
```

`verifier/TestAttestationObject.java`:

```java
package com.crosscert.passkey.webauthn.verifier;

import java.io.ByteArrayOutputStream;

/** 테스트용 none attestationObject CBOR 인코더. */
final class TestAttestationObject {
    static byte[] none(byte[] authData) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xa3); // map(3)
        o.write(0x63); o.writeBytes("fmt".getBytes());
        o.write(0x64); o.writeBytes("none".getBytes());
        o.write(0x67); o.writeBytes("attStmt".getBytes());
        o.write(0xa0);
        o.write(0x68); o.writeBytes("authData".getBytes());
        if (authData.length <= 23) {
            o.write(0x40 | authData.length);
        } else if (authData.length < 256) {
            o.write(0x58); o.write(authData.length);
        } else {
            o.write(0x59); o.write((authData.length >> 8) & 0xff); o.write(authData.length & 0xff);
        }
        o.writeBytes(authData);
        return o.toByteArray();
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew :webauthn:test --tests '*NativeRegistrationTest'`
Expected: FAIL — `NativeWebAuthnVerifier` 없음

- [ ] **Step 4: NativeWebAuthnVerifier 등록 부분 구현 (인증은 다음 Task에서 추가)**

`verifier/NativeWebAuthnVerifier.java`:

```java
package com.crosscert.passkey.webauthn.verifier;

import com.crosscert.passkey.webauthn.attestation.AttestationObject;
import com.crosscert.passkey.webauthn.attestation.AttestationObjectDecoder;
import com.crosscert.passkey.webauthn.attestation.AttestationException;
import com.crosscert.passkey.webauthn.attestation.AttestationResult;
import com.crosscert.passkey.webauthn.attestation.AttestationVerifier;
import com.crosscert.passkey.webauthn.attestation.AttestationVerifiers;
import com.crosscert.passkey.webauthn.authdata.AttestedCredentialData;
import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.clientdata.ClientDataException;
import com.crosscert.passkey.webauthn.clientdata.ClientDataValidator;
import com.crosscert.passkey.webauthn.cose.CoseAlgorithm;
import com.crosscert.passkey.webauthn.cose.CoseException;
import com.crosscert.passkey.webauthn.cose.CoseKey;
import com.crosscert.passkey.webauthn.cose.CoseKeyParser;
import com.crosscert.passkey.webauthn.trust.CertPathVerifier;
import com.crosscert.passkey.webauthn.trust.EmptyTrustAnchorProvider;
import com.crosscert.passkey.webauthn.trust.TrustAnchorProvider;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerificationException.Reason;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.Set;

/**
 * 자체 구현 WebAuthn 검증 오케스트레이터. CBOR/COSE/authData/clientData
 * 빌딩블록과 attestation 검증기를 spec 세리머니(WebAuthn §7.1/§7.2) 순서로 묶는다.
 */
public final class NativeWebAuthnVerifier implements WebAuthnVerifier {

    private final CredentialJsonParser jsonParser;
    private final ClientDataValidator clientDataValidator;
    private final AttestationVerifiers attestationVerifiers;
    private final TrustAnchorProvider trustAnchors;
    private final CertPathVerifier certPathVerifier;

    public NativeWebAuthnVerifier(ObjectMapper mapper,
                                  AttestationVerifiers attestationVerifiers,
                                  TrustAnchorProvider trustAnchors) {
        this.jsonParser = new CredentialJsonParser(mapper);
        this.clientDataValidator = new ClientDataValidator(mapper);
        this.attestationVerifiers = attestationVerifiers;
        this.trustAnchors = trustAnchors;
        this.certPathVerifier = new CertPathVerifier();
    }

    /** 기본 구성 — 등록된 모든 포맷 + 빈 trust anchor(self/none만 통과). */
    public static NativeWebAuthnVerifier withDefaults(ObjectMapper mapper) {
        return new NativeWebAuthnVerifier(mapper, AttestationVerifiers.defaults(),
                new EmptyTrustAnchorProvider());
    }

    @Override
    public RegistrationResult verifyRegistration(RegistrationInput input)
            throws WebAuthnVerificationException {
        ParsedRegistration parsed;
        try {
            parsed = jsonParser.parseRegistration(input.credentialJson());
        } catch (RuntimeException e) {
            throw fail(Reason.MALFORMED_INPUT, "registration JSON parse failed", e);
        }

        // 1) clientData 검증
        try {
            clientDataValidator.validate(parsed.clientDataJson(), "webauthn.create",
                    input.challenge(), input.allowedOrigins());
        } catch (ClientDataException e) {
            throw mapClientData(e);
        }

        // 2) attestationObject 디코드
        AttestationObject ao;
        try {
            ao = AttestationObjectDecoder.decode(parsed.attestationObject());
        } catch (RuntimeException e) {
            throw fail(Reason.MALFORMED_INPUT, "attestationObject decode failed", e);
        }
        AuthenticatorData authData = ao.authData();

        // 3) rpIdHash 대조
        if (!MessageDigest.isEqual(authData.rpIdHash(), sha256(input.rpId()))) {
            throw fail(Reason.RP_ID_HASH_MISMATCH, "rpIdHash mismatch");
        }
        // 4) UP/UV 플래그
        if (!authData.flags().userPresent()) {
            throw fail(Reason.UP_REQUIRED, "user presence flag not set");
        }
        if (input.userVerificationRequired() && !authData.flags().userVerified()) {
            throw fail(Reason.UV_REQUIRED, "user verification required but not set");
        }
        // 5) attestedCredentialData 존재
        AttestedCredentialData acd = authData.attestedCredentialData();
        if (acd == null) {
            throw fail(Reason.MALFORMED_INPUT, "registration authData has no attestedCredentialData");
        }
        // 6) credential 알고리즘이 허용 목록에 있는지
        CoseKey credKey;
        try {
            credKey = CoseKeyParser.parse(acd.coseKeyMap());
        } catch (CoseException e) {
            throw fail(Reason.UNSUPPORTED_ALGORITHM, "credential COSE key unsupported", e);
        }
        if (!input.allowedAlgorithms().contains(toBoundaryAlg(credKey.algorithm()))) {
            throw fail(Reason.UNSUPPORTED_ALGORITHM, "credential algorithm not allowed");
        }
        // 7) attestation 포맷이 테넌트 정책에 허용되는지
        if (!input.acceptedAttestationFormats().contains(ao.format())) {
            throw fail(Reason.ATTESTATION_FORMAT_NOT_ACCEPTED,
                    "attestation format not accepted: " + ao.format());
        }
        AttestationVerifier av = attestationVerifiers.forFormat(ao.format());
        if (av == null) {
            throw fail(Reason.UNSUPPORTED_ATTESTATION_FORMAT,
                    "unsupported attestation format: " + ao.format());
        }
        // 8) attestation statement 검증
        byte[] clientDataHash = sha256Bytes(parsed.clientDataJson());
        AttestationResult attResult;
        try {
            attResult = av.verify(authData, ao.rawAuthData(), ao.attStmt(), clientDataHash);
        } catch (AttestationException e) {
            throw fail(Reason.BAD_SIGNATURE, "attestation verify failed: " + e.getMessage(), e);
        }
        // 9) trust policy 강제
        enforceTrust(input.trustPolicy(), ao.format(), acd.aaguid(), attResult);

        return new RegistrationResult(
                acd.credentialId(),
                acd.coseKeyBytes(),
                authData.signCount(),
                acd.aaguid(),
                ao.format(),
                parsed.transports(),
                authData.flags().userVerified(),
                authData.flags().userPresent(),
                authData.flags().backupEligible(),
                authData.flags().backupState());
    }

    @Override
    public AuthenticationResult verifyAuthentication(AuthenticationInput input)
            throws WebAuthnVerificationException {
        throw new UnsupportedOperationException("implemented in Task 15");
    }

    // --- trust 강제 ---

    private void enforceTrust(AttestationTrustPolicy policy, String format,
                              byte[] aaguid, AttestationResult attResult)
            throws WebAuthnVerificationException {
        switch (policy) {
            case NONE_ONLY -> {
                if (!"none".equals(format)) {
                    throw fail(Reason.ATTESTATION_UNTRUSTED, "policy NONE_ONLY but format=" + format);
                }
            }
            case SELF_ALLOWED -> { /* none/self/x5c 서명 검증만으로 충분 */ }
            case TRUST_CHAIN_REQUIRED -> {
                if (attResult.type() == AttestationResult.Type.NONE
                        || attResult.type() == AttestationResult.Type.SELF) {
                    throw fail(Reason.ATTESTATION_UNTRUSTED,
                            "TRUST_CHAIN_REQUIRED but attestation is none/self");
                }
                Set<java.security.cert.TrustAnchor> anchors = trustAnchors.trustAnchors(aaguid, format);
                if (!certPathVerifier.verify(attResult.trustPath(), anchors)) {
                    throw fail(Reason.ATTESTATION_UNTRUSTED, "attestation chain not trusted");
                }
            }
        }
    }

    // --- 헬퍼 ---

    private static COSEAlgorithm toBoundaryAlg(CoseAlgorithm a) {
        return switch (a) {
            case ES256 -> COSEAlgorithm.ES256;
            case RS256 -> COSEAlgorithm.RS256;
        };
    }

    private static byte[] sha256(String s) {
        return sha256Bytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256Bytes(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static WebAuthnVerificationException fail(Reason reason, String msg) {
        return new WebAuthnVerificationException(reason, msg);
    }

    private static WebAuthnVerificationException fail(Reason reason, String msg, Throwable cause) {
        return new WebAuthnVerificationException(reason, msg, cause);
    }

    private static WebAuthnVerificationException mapClientData(ClientDataException e) {
        Reason r = switch (e.reason()) {
            case MALFORMED -> Reason.MALFORMED_INPUT;
            case TYPE_MISMATCH -> Reason.TYPE_MISMATCH;
            case CHALLENGE_MISMATCH -> Reason.CHALLENGE_MISMATCH;
            case ORIGIN_MISMATCH -> Reason.ORIGIN_MISMATCH;
        };
        return new WebAuthnVerificationException(r, e.getMessage(), e);
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :webauthn:test --tests '*NativeRegistrationTest'`
Expected: PASS (2 tests)

- [ ] **Step 6: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/verifier/NativeWebAuthnVerifier.java webauthn/src/test/java/com/crosscert/passkey/webauthn/verifier/
git commit -m "feat(webauthn): NativeWebAuthnVerifier 등록 검증 파이프라인"
```

### Task 15: NativeWebAuthnVerifier — 인증 검증

**Files:**
- Modify: `webauthn/src/main/java/com/crosscert/passkey/webauthn/verifier/NativeWebAuthnVerifier.java` (verifyAuthentication 구현)
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/verifier/NativeAuthenticationTest.java`

- [ ] **Step 1: 실패하는 테스트 작성 (ES256 assertion end-to-end)**

`verifier/NativeAuthenticationTest.java`:

```java
package com.crosscert.passkey.webauthn.verifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NativeAuthenticationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final NativeWebAuthnVerifier verifier = NativeWebAuthnVerifier.withDefaults(mapper);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    @Test
    void verifiesEs256Assertion() throws Exception {
        String rpId = "example.com";
        String origin = "https://example.com";
        byte[] challenge = "auth-challenge".getBytes(StandardCharsets.UTF_8);

        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        ECPublicKey pub = (ECPublicKey) kp.getPublic();
        byte[] cose = cose(pub);

        // authData: rpIdHash + flags(UP+UV) + signCount=5  (AT 없음)
        byte[] rpIdHash = MessageDigest.getInstance("SHA-256").digest(rpId.getBytes());
        ByteArrayOutputStream ad = new ByteArrayOutputStream();
        ad.writeBytes(rpIdHash);
        ad.write(0x05); // UP + UV
        ad.writeBytes(new byte[]{0, 0, 0, 5});
        byte[] authData = ad.toByteArray();

        // clientDataJSON (webauthn.get)
        String clientData = "{\"type\":\"webauthn.get\",\"challenge\":\""
                + B64.encodeToString(challenge) + "\",\"origin\":\"" + origin + "\"}";
        byte[] clientDataBytes = clientData.getBytes(StandardCharsets.UTF_8);
        byte[] clientDataHash = MessageDigest.getInstance("SHA-256").digest(clientDataBytes);

        // signature over authData || clientDataHash
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(kp.getPrivate());
        sig.update(authData);
        sig.update(clientDataHash);
        byte[] signature = sig.sign();

        byte[] credId = new byte[]{1, 2, 3};
        String credJson = "{\"id\":\"" + B64.encodeToString(credId) + "\",\"rawId\":\""
                + B64.encodeToString(credId) + "\",\"type\":\"public-key\",\"response\":{"
                + "\"clientDataJSON\":\"" + B64.encodeToString(clientDataBytes) + "\","
                + "\"authenticatorData\":\"" + B64.encodeToString(authData) + "\","
                + "\"signature\":\"" + B64.encodeToString(signature) + "\"}}";

        StoredCredential stored = new StoredCredential(credId, cose, 4);
        AuthenticationInput input = new AuthenticationInput(
                credJson, challenge, Set.of(origin), rpId, true, stored);

        AuthenticationResult result = verifier.verifyAuthentication(input);

        assertArrayEquals(credId, result.credentialId());
        assertEquals(5L, result.newSignCount());
        assertTrue(result.uvVerified());
    }

    @Test
    void rejectsBadSignature() throws Exception {
        String rpId = "example.com";
        String origin = "https://example.com";
        byte[] challenge = "c".getBytes();
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        ECPublicKey pub = (ECPublicKey) g.generateKeyPair().getPublic();
        byte[] cose = cose(pub);

        byte[] rpIdHash = MessageDigest.getInstance("SHA-256").digest(rpId.getBytes());
        ByteArrayOutputStream ad = new ByteArrayOutputStream();
        ad.writeBytes(rpIdHash);
        ad.write(0x01);
        ad.writeBytes(new byte[]{0, 0, 0, 1});
        byte[] authData = ad.toByteArray();
        String clientData = "{\"type\":\"webauthn.get\",\"challenge\":\""
                + B64.encodeToString(challenge) + "\",\"origin\":\"" + origin + "\"}";

        byte[] credId = new byte[]{9};
        String credJson = "{\"id\":\"CQ\",\"rawId\":\"CQ\",\"type\":\"public-key\",\"response\":{"
                + "\"clientDataJSON\":\"" + B64.encodeToString(clientData.getBytes()) + "\","
                + "\"authenticatorData\":\"" + B64.encodeToString(authData) + "\","
                + "\"signature\":\"" + B64.encodeToString(new byte[64]) + "\"}}";

        StoredCredential stored = new StoredCredential(credId, cose, 0);
        AuthenticationInput input = new AuthenticationInput(
                credJson, challenge, Set.of(origin), rpId, false, stored);

        WebAuthnVerificationException ex = assertThrows(WebAuthnVerificationException.class,
                () -> verifier.verifyAuthentication(input));
        assertEquals(WebAuthnVerificationException.Reason.BAD_SIGNATURE, ex.reason());
    }

    private static byte[] cose(ECPublicKey pub) {
        byte[] x = fixed32(pub.getW().getAffineX());
        byte[] y = fixed32(pub.getW().getAffineY());
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xa5);
        o.write(0x01); o.write(0x02);
        o.write(0x03); o.write(0x26);
        o.write(0x20); o.write(0x01);
        o.write(0x21); o.write(0x58); o.write(0x20); o.writeBytes(x);
        o.write(0x22); o.write(0x58); o.write(0x20); o.writeBytes(y);
        return o.toByteArray();
    }

    private static byte[] fixed32(java.math.BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :webauthn:test --tests '*NativeAuthenticationTest'`
Expected: FAIL — verifyAuthentication이 UnsupportedOperationException

- [ ] **Step 3: verifyAuthentication 구현 (UnsupportedOperationException 본문 교체)**

`NativeWebAuthnVerifier.verifyAuthentication` 본문을 다음으로 교체:

```java
    @Override
    public AuthenticationResult verifyAuthentication(AuthenticationInput input)
            throws WebAuthnVerificationException {
        ParsedAuthentication parsed;
        try {
            parsed = jsonParser.parseAuthentication(input.credentialJson());
        } catch (RuntimeException e) {
            throw fail(Reason.MALFORMED_INPUT, "authentication JSON parse failed", e);
        }

        // 1) clientData 검증 (webauthn.get)
        try {
            clientDataValidator.validate(parsed.clientDataJson(), "webauthn.get",
                    input.challenge(), input.allowedOrigins());
        } catch (ClientDataException e) {
            throw mapClientData(e);
        }

        // 2) authData 파싱 (AT 없음)
        AuthenticatorData authData;
        try {
            authData = com.crosscert.passkey.webauthn.authdata.AuthenticatorDataParser
                    .parse(parsed.authenticatorData());
        } catch (RuntimeException e) {
            throw fail(Reason.MALFORMED_INPUT, "authenticatorData parse failed", e);
        }

        // 3) rpIdHash 대조
        if (!MessageDigest.isEqual(authData.rpIdHash(), sha256(input.rpId()))) {
            throw fail(Reason.RP_ID_HASH_MISMATCH, "rpIdHash mismatch");
        }
        // 4) UP/UV
        if (!authData.flags().userPresent()) {
            throw fail(Reason.UP_REQUIRED, "user presence flag not set");
        }
        if (input.userVerificationRequired() && !authData.flags().userVerified()) {
            throw fail(Reason.UV_REQUIRED, "user verification required but not set");
        }

        // 5) 저장 COSE 키 복원
        CoseKey storedKey;
        try {
            storedKey = CoseKeyParser.parse(
                    com.crosscert.passkey.webauthn.cbor.CborDecoder.decode(
                            input.storedCredential().cosePublicKey()));
        } catch (RuntimeException e) {
            throw fail(Reason.MALFORMED_INPUT, "stored COSE key invalid", e);
        }

        // 6) 서명 검증: authData || SHA-256(clientDataJSON)
        byte[] clientDataHash = sha256Bytes(parsed.clientDataJson());
        boolean ok = verifySignature(storedKey, parsed.authenticatorData(), clientDataHash,
                parsed.signature());
        if (!ok) {
            throw fail(Reason.BAD_SIGNATURE, "assertion signature invalid");
        }

        return new AuthenticationResult(
                input.storedCredential().credentialId(),
                authData.signCount(),
                authData.flags().userVerified(),
                authData.flags().userPresent(),
                authData.flags().backupState());
    }

    private static boolean verifySignature(CoseKey key, byte[] authData,
                                           byte[] clientDataHash, byte[] signature) {
        try {
            java.security.Signature sig =
                    java.security.Signature.getInstance(key.algorithm().jcaSignatureName());
            sig.initVerify(key.publicKey());
            sig.update(authData);
            sig.update(clientDataHash);
            return sig.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }
```

> **참고:** `import` 충돌을 피하려 `AuthenticatorDataParser`와 `CborDecoder`를 위에서 FQN으로 호출했다. 이미 등록 부분에서 `CoseKey`/`CoseKeyParser`/`MessageDigest`는 import되어 있다. 깔끔하게 하려면 상단 import에 `AuthenticatorDataParser`와 `CborDecoder`를 추가하고 FQN을 단축해도 된다(동작 동일).

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :webauthn:test --tests '*NativeAuthenticationTest'`
Expected: PASS (2 tests)

- [ ] **Step 5: 모듈 전체 테스트 실행**

Run: `./gradlew :webauthn:test`
Expected: BUILD SUCCESSFUL — 지금까지의 모든 단위테스트 통과

- [ ] **Step 6: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/verifier/NativeWebAuthnVerifier.java webauthn/src/test/java/com/crosscert/passkey/webauthn/verifier/NativeAuthenticationTest.java
git commit -m "feat(webauthn): NativeWebAuthnVerifier 인증 검증 파이프라인"
```

---

## Phase 4 — Differential 테스트 (webauthn4j 대조)

> **목표:** spec §9. 동일 입력을 자체 구현과 webauthn4j에 모두 넣어 판정·추출값이 일치함을 단언한다. webauthn4j-test의 `ClientPlatform`/`PackedAuthenticator`로 진짜 클라이언트 응답을 생성한다.

### Task 16: Webauthn4jVerifier 어댑터 (테스트 소스셋)

**Files:**
- Create: `webauthn/src/test/java/com/crosscert/passkey/webauthn/diff/Webauthn4jVerifier.java`
- Create: `webauthn/src/test/java/com/crosscert/passkey/webauthn/diff/Webauthn4jVerifierTest.java`

- [ ] **Step 1: Webauthn4jVerifier 어댑터 작성 (WebAuthnVerifier 구현, webauthn4j 위임)**

`webauthn/src/test/java/com/crosscert/passkey/webauthn/diff/Webauthn4jVerifier.java`:

```java
package com.crosscert.passkey.webauthn.diff;

import com.crosscert.passkey.webauthn.verifier.AuthenticationInput;
import com.crosscert.passkey.webauthn.verifier.AuthenticationResult;
import com.crosscert.passkey.webauthn.verifier.RegistrationInput;
import com.crosscert.passkey.webauthn.verifier.RegistrationResult;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerificationException;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerificationException.Reason;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerifier;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestationObjectConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecord;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * webauthn4j로 검증을 수행하는 WebAuthnVerifier 구현. differential 테스트
 * 전용이며 테스트 소스셋에만 존재한다 — 프로덕션 classpath에 없다.
 *
 * NOTE: webauthn4j는 credentialJson을 직접 파싱하므로 RegistrationInput의
 * credentialJson을 그대로 manager.parseRegistrationResponseJSON에 넘긴다.
 */
public final class Webauthn4jVerifier implements WebAuthnVerifier {

    private final WebAuthnManager manager = WebAuthnManager.createNonStrictWebAuthnManager();
    private final ObjectConverter objectConverter = new ObjectConverter();
    private final AttestationObjectConverter attestationObjectConverter =
            new AttestationObjectConverter(objectConverter);

    private static final List<PublicKeyCredentialParameters> ALG_PARAMS = List.of(
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256));

    @Override
    public RegistrationResult verifyRegistration(RegistrationInput input)
            throws WebAuthnVerificationException {
        RegistrationData data;
        try {
            data = manager.parseRegistrationResponseJSON(input.credentialJson());
        } catch (Exception e) {
            throw new WebAuthnVerificationException(Reason.MALFORMED_INPUT, e.toString(), e);
        }
        ServerProperty sp = ServerProperty.builder()
                .origins(origins(input.allowedOrigins()))
                .rpId(input.rpId())
                .challenge(new DefaultChallenge(input.challenge()))
                .build();
        RegistrationParameters params = new RegistrationParameters(
                sp, ALG_PARAMS, input.userVerificationRequired(), true);
        try {
            manager.verify(data, params);
        } catch (Exception e) {
            throw new WebAuthnVerificationException(Reason.BAD_SIGNATURE, e.toString(), e);
        }
        var acd = data.getAttestationObject().getAuthenticatorData().getAttestedCredentialData();
        byte[] cose = attestationObjectConverter.extractAuthenticatorData(
                attestationObjectConverter.convertToBytes(data.getAttestationObject()));
        // webauthn4j는 COSE 키 바이트를 직접 노출하지 않으므로, differential
        // 비교에서는 cosePublicKey 바이트 동일성 대신 credentialId/format/signCount/aaguid를 비교한다.
        Set<String> transports = data.getTransports() == null ? Set.of()
                : data.getTransports().stream().map(t -> t.getValue()).collect(Collectors.toSet());
        return new RegistrationResult(
                acd.getCredentialId(),
                /* cosePublicKey */ null,   // diff 테스트에서 비교 제외 (아래 주석 참고)
                data.getAttestationObject().getAuthenticatorData().getSignCount(),
                acd.getAaguid().getBytes(),
                data.getAttestationObject().getFormat(),
                transports,
                data.getAttestationObject().getAuthenticatorData().isFlagUV(),
                data.getAttestationObject().getAuthenticatorData().isFlagUP(),
                false, false);
    }

    @Override
    public AuthenticationResult verifyAuthentication(AuthenticationInput input)
            throws WebAuthnVerificationException {
        AuthenticationData data;
        try {
            data = manager.parseAuthenticationResponseJSON(input.credentialJson());
        } catch (Exception e) {
            throw new WebAuthnVerificationException(Reason.MALFORMED_INPUT, e.toString(), e);
        }
        // 저장 COSE 키로 CredentialRecord 재구성은 복잡하므로 differential
        // 인증 비교는 NativeAuthenticationDifferentialTest에서 자체 헬퍼로
        // CredentialRecord를 만들어 넣는다(아래 Task 18 참고).
        throw new UnsupportedOperationException("auth diff handled in Task 18 harness");
    }

    private static Set<Origin> origins(Set<String> raw) {
        return raw.stream().map(Origin::create).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
```

> **NOTE (구현자):** webauthn4j 0.31.5의 정확한 메서드명(`isFlagUV`/`isFlagUP`/`extractAuthenticatorData` 등)은 라이브러리 버전에 따라 다를 수 있다. 컴파일 에러가 나면 IDE 자동완성으로 동등 메서드를 찾아 대체하라. **이 어댑터의 목적은 "동일 입력에 대한 통과/실패 판정"과 "credentialId/format/signCount/aaguid 추출값"을 자체 구현과 비교하는 것**이다. `cosePublicKey` 바이트 동일성은 webauthn4j가 원시 COSE 바이트를 직접 노출하지 않으므로 비교 대상에서 제외한다(대신 자체 구현은 별도 round-trip 테스트로 COSE 키 정확성을 보장 — Task 4).

- [ ] **Step 2: Webauthn4jVerifier 스모크 테스트 작성 (none 등록 1건)**

`webauthn/src/test/java/com/crosscert/passkey/webauthn/diff/Webauthn4jVerifierTest.java`:

```java
package com.crosscert.passkey.webauthn.diff;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Webauthn4jVerifier가 인스턴스화되고 webauthn4j classpath가 테스트에 있는지 확인. */
class Webauthn4jVerifierTest {
    @Test
    void instantiates() {
        assertNotNull(new Webauthn4jVerifier());
    }
}
```

- [ ] **Step 3: 컴파일·테스트 실행 (메서드명 교정)**

Run: `./gradlew :webauthn:test --tests '*Webauthn4jVerifierTest'`
Expected: 처음엔 webauthn4j 메서드명 불일치로 컴파일 에러 가능 → NOTE대로 교정 후 PASS

- [ ] **Step 4: Commit**

```bash
git add webauthn/src/test/java/com/crosscert/passkey/webauthn/diff/
git commit -m "test(webauthn): Webauthn4jVerifier 어댑터 (differential 대조용)"
```

### Task 17: 등록 differential harness (webauthn4j-test로 입력 생성 → 양쪽 비교)

**Files:**
- Create: `webauthn/src/test/java/com/crosscert/passkey/webauthn/diff/CeremonyFixtures.java`
- Create: `webauthn/src/test/java/com/crosscert/passkey/webauthn/diff/RegistrationDifferentialTest.java`

- [ ] **Step 1: CeremonyFixtures 작성 (webauthn4j-test로 register JSON 생성)**

`webauthn/src/test/java/com/crosscert/passkey/webauthn/diff/CeremonyFixtures.java`:

```java
package com.crosscert.passkey.webauthn.diff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webauthn4j.data.AttestationConveyancePreference;
import com.webauthn4j.data.AuthenticatorAttestationResponse;
import com.webauthn4j.data.PublicKeyCredential;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialRpEntity;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.PublicKeyCredentialUserEntity;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.Challenge;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.test.authenticator.webauthn.PackedAuthenticator;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;

import java.util.Base64;
import java.util.List;

/**
 * webauthn4j-test의 ClientPlatform + PackedAuthenticator로 진짜
 * 클라이언트 응답을 생성해 WebAuthn-L3 JSON으로 직렬화한다.
 * (passkey-app의 Fido2TestAuthenticator와 동일한 접근을 모듈 테스트에 복제.)
 */
public final class CeremonyFixtures {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ClientPlatform clientPlatform;
    private final String rpId;
    private final String origin;

    public CeremonyFixtures(String rpId, String origin) {
        this.rpId = rpId;
        this.origin = origin;
        this.clientPlatform = new ClientPlatform(
                Origin.create(origin), new WebAuthnAuthenticatorAdaptor(new PackedAuthenticator()));
    }

    /** packed 등록 응답 JSON 생성. */
    public JsonNode registerPacked(byte[] challenge, byte[] userHandle) {
        PublicKeyCredentialCreationOptions options = new PublicKeyCredentialCreationOptions(
                new PublicKeyCredentialRpEntity(rpId, "RP"),
                new PublicKeyCredentialUserEntity(userHandle, "user", "User"),
                new DefaultChallenge(challenge),
                List.of(new PublicKeyCredentialParameters(
                        PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256)),
                null, null, null, AttestationConveyancePreference.DIRECT, null);
        PublicKeyCredential<AuthenticatorAttestationResponse, ?> cred = clientPlatform.create(options);
        return toRegistrationJson(cred);
    }

    public String origin() { return origin; }
    public String rpId() { return rpId; }

    private JsonNode toRegistrationJson(PublicKeyCredential<AuthenticatorAttestationResponse, ?> cred) {
        ObjectNode root = mapper.createObjectNode();
        String id = B64.encodeToString(cred.getRawId());
        root.put("id", id);
        root.put("rawId", id);
        root.put("type", "public-key");
        ObjectNode resp = root.putObject("response");
        resp.put("clientDataJSON", B64.encodeToString(cred.getResponse().getClientDataJSON()));
        resp.put("attestationObject", B64.encodeToString(cred.getResponse().getAttestationObject()));
        ArrayNode tr = resp.putArray("transports");
        if (cred.getResponse().getTransports() != null) {
            for (var t : cred.getResponse().getTransports()) tr.add(t.getValue());
        }
        root.putObject("clientExtensionResults");
        return root;
    }
}
```

- [ ] **Step 2: 등록 differential 테스트 작성**

`webauthn/src/test/java/com/crosscert/passkey/webauthn/diff/RegistrationDifferentialTest.java`:

```java
package com.crosscert.passkey.webauthn.diff;

import com.crosscert.passkey.webauthn.verifier.AttestationTrustPolicy;
import com.crosscert.passkey.webauthn.verifier.COSEAlgorithm;
import com.crosscert.passkey.webauthn.verifier.NativeWebAuthnVerifier;
import com.crosscert.passkey.webauthn.verifier.RegistrationInput;
import com.crosscert.passkey.webauthn.verifier.RegistrationResult;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerificationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 동일한 등록 응답을 자체 구현과 webauthn4j에 모두 넣고, 통과/실패 판정과
 * credentialId/format/signCount 추출값이 일치하는지 단언한다.
 */
class RegistrationDifferentialTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final NativeWebAuthnVerifier nativeVerifier = NativeWebAuthnVerifier.withDefaults(mapper);
    private final Webauthn4jVerifier w4j = new Webauthn4jVerifier();

    @Test
    void packedRegistrationAgreesAcrossImplementations() throws Exception {
        CeremonyFixtures fx = new CeremonyFixtures("localhost", "https://localhost");
        byte[] challenge = "diff-reg".getBytes(StandardCharsets.UTF_8);
        String credJson = fx.registerPacked(challenge, new byte[]{1, 2, 3, 4}).toString();

        RegistrationInput input = new RegistrationInput(
                credJson, challenge, Set.of(fx.origin()), fx.rpId(),
                false, Set.of(COSEAlgorithm.ES256), Set.of("packed"),
                AttestationTrustPolicy.SELF_ALLOWED);

        RegistrationResult nativeResult = nativeVerifier.verifyRegistration(input);
        RegistrationResult w4jResult = w4j.verifyRegistration(input);

        assertArrayEquals(w4jResult.credentialId(), nativeResult.credentialId(),
                "credentialId must match webauthn4j");
        assertEquals(w4jResult.attestationFormat(), nativeResult.attestationFormat());
        assertEquals(w4jResult.signCount(), nativeResult.signCount());
        assertArrayEquals(w4jResult.aaguid(), nativeResult.aaguid());
    }

    @Test
    void bothRejectTamperedChallenge() throws Exception {
        CeremonyFixtures fx = new CeremonyFixtures("localhost", "https://localhost");
        byte[] challenge = "real".getBytes(StandardCharsets.UTF_8);
        String credJson = fx.registerPacked(challenge, new byte[]{1}).toString();

        // 서버가 다른 challenge를 기대 → 양쪽 모두 거부해야 함
        RegistrationInput input = new RegistrationInput(
                credJson, "wrong".getBytes(StandardCharsets.UTF_8),
                Set.of(fx.origin()), fx.rpId(),
                false, Set.of(COSEAlgorithm.ES256), Set.of("packed"),
                AttestationTrustPolicy.SELF_ALLOWED);

        assertThrows(WebAuthnVerificationException.class, () -> nativeVerifier.verifyRegistration(input));
        assertThrows(WebAuthnVerificationException.class, () -> w4j.verifyRegistration(input));
    }
}
```

- [ ] **Step 3: 테스트 실행**

Run: `./gradlew :webauthn:test --tests '*RegistrationDifferentialTest'`
Expected: PASS (2 tests). 실패 시 자체 구현/어댑터의 추출 필드 정렬을 맞춘다.

- [ ] **Step 4: Commit**

```bash
git add webauthn/src/test/java/com/crosscert/passkey/webauthn/diff/CeremonyFixtures.java webauthn/src/test/java/com/crosscert/passkey/webauthn/diff/RegistrationDifferentialTest.java
git commit -m "test(webauthn): 등록 differential 테스트 (webauthn4j-test 입력 → 양쪽 대조)"
```

### Task 18: 인증 differential harness

**Files:**
- Modify: `webauthn/src/test/java/com/crosscert/passkey/webauthn/diff/CeremonyFixtures.java` (authenticate 추가)
- Create: `webauthn/src/test/java/com/crosscert/passkey/webauthn/diff/AuthenticationDifferentialTest.java`

- [ ] **Step 1: CeremonyFixtures에 인증 응답 생성 추가**

`CeremonyFixtures.java`에 메서드 추가 (필요 import는 IDE 자동완성으로):

```java
    /** register 후 같은 authenticator로 assertion 응답 JSON + 저장 COSE 키를 함께 반환. */
    public AuthFixture authenticate(byte[] regChallenge, byte[] authChallenge, byte[] userHandle) {
        // 1) 먼저 등록해 authenticator에 credential을 심는다
        com.webauthn4j.data.PublicKeyCredentialCreationOptions createOptions =
                new com.webauthn4j.data.PublicKeyCredentialCreationOptions(
                        new PublicKeyCredentialRpEntity(rpId, "RP"),
                        new PublicKeyCredentialUserEntity(userHandle, "user", "User"),
                        new DefaultChallenge(regChallenge),
                        List.of(new PublicKeyCredentialParameters(
                                PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256)),
                        null, null, null, AttestationConveyancePreference.DIRECT, null);
        PublicKeyCredential<AuthenticatorAttestationResponse, ?> regCred =
                clientPlatform.create(createOptions);

        // 등록 응답에서 COSE 키 바이트 추출 (저장 스키마용)
        com.webauthn4j.converter.util.ObjectConverter oc =
                new com.webauthn4j.converter.util.ObjectConverter();
        com.webauthn4j.converter.AttestationObjectConverter aoc =
                new com.webauthn4j.converter.AttestationObjectConverter(oc);
        var ao = aoc.convert(regCred.getResponse().getAttestationObject());
        byte[] coseKeyBytes = oc.getCborConverter().writeValueAsBytes(
                ao.getAuthenticatorData().getAttestedCredentialData().getCOSEKey());
        byte[] credentialId = regCred.getRawId();

        // 2) 같은 clientPlatform으로 assertion 생성
        com.webauthn4j.data.PublicKeyCredentialRequestOptions reqOptions =
                new com.webauthn4j.data.PublicKeyCredentialRequestOptions(
                        new DefaultChallenge(authChallenge), null, rpId,
                        List.of(new com.webauthn4j.data.PublicKeyCredentialDescriptor(
                                PublicKeyCredentialType.PUBLIC_KEY, credentialId, null)),
                        null, null);
        PublicKeyCredential<com.webauthn4j.data.AuthenticatorAssertionResponse, ?> authCred =
                clientPlatform.get(reqOptions);

        return new AuthFixture(toAuthenticationJson(authCred).toString(), credentialId, coseKeyBytes);
    }

    private JsonNode toAuthenticationJson(
            PublicKeyCredential<com.webauthn4j.data.AuthenticatorAssertionResponse, ?> cred) {
        ObjectNode root = mapper.createObjectNode();
        String id = B64.encodeToString(cred.getRawId());
        root.put("id", id);
        root.put("rawId", id);
        root.put("type", "public-key");
        ObjectNode resp = root.putObject("response");
        resp.put("clientDataJSON", B64.encodeToString(cred.getResponse().getClientDataJSON()));
        resp.put("authenticatorData", B64.encodeToString(cred.getResponse().getAuthenticatorData()));
        resp.put("signature", B64.encodeToString(cred.getResponse().getSignature()));
        byte[] uh = cred.getResponse().getUserHandle();
        if (uh != null) resp.put("userHandle", B64.encodeToString(uh));
        root.putObject("clientExtensionResults");
        return root;
    }

    /** 인증 differential 입력 묶음. */
    public record AuthFixture(String credentialJson, byte[] credentialId, byte[] cosePublicKey) {}
```

> **NOTE (구현자):** `getCOSEKey()`/`getCborConverter()` 등 정확한 webauthn4j 0.31.5 메서드명은 IDE에서 확인해 교정하라. 핵심은 **등록 응답에서 COSE 키 원시 바이트를 뽑아 StoredCredential로 만들고, 같은 authenticator의 assertion을 자체 구현으로 검증**하는 것이다.

- [ ] **Step 2: 인증 differential 테스트 작성 (자체 구현만 검증 — webauthn4j 인증 대조는 CredentialRecord 재구성 복잡성으로 자체 구현 정확성 + 공식 vector로 충분)**

`webauthn/src/test/java/com/crosscert/passkey/webauthn/diff/AuthenticationDifferentialTest.java`:

```java
package com.crosscert.passkey.webauthn.diff;

import com.crosscert.passkey.webauthn.verifier.AuthenticationInput;
import com.crosscert.passkey.webauthn.verifier.AuthenticationResult;
import com.crosscert.passkey.webauthn.verifier.NativeWebAuthnVerifier;
import com.crosscert.passkey.webauthn.verifier.StoredCredential;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * webauthn4j-test가 만든 진짜 packed authenticator의 assertion을 자체
 * 구현이 검증·통과시키는지 확인한다. (입력 생성에 webauthn4j-test를 쓰므로
 * "webauthn4j가 만든 서명을 자체 구현이 받아들인다"는 교차 검증이 된다.)
 */
class AuthenticationDifferentialTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final NativeWebAuthnVerifier nativeVerifier = NativeWebAuthnVerifier.withDefaults(mapper);

    @Test
    void nativeVerifiesWebauthn4jGeneratedAssertion() throws Exception {
        CeremonyFixtures fx = new CeremonyFixtures("localhost", "https://localhost");
        byte[] authChallenge = "diff-auth".getBytes(StandardCharsets.UTF_8);
        CeremonyFixtures.AuthFixture af = fx.authenticate(
                "reg".getBytes(StandardCharsets.UTF_8), authChallenge, new byte[]{5, 6, 7, 8});

        StoredCredential stored = new StoredCredential(af.credentialId(), af.cosePublicKey(), 0);
        AuthenticationInput input = new AuthenticationInput(
                af.credentialJson(), authChallenge, Set.of(fx.origin()), fx.rpId(), false, stored);

        AuthenticationResult result = nativeVerifier.verifyAuthentication(input);
        assertArrayEquals(af.credentialId(), result.credentialId());
        assertTrue(result.newSignCount() >= 0);
    }
}
```

- [ ] **Step 3: 테스트 실행**

Run: `./gradlew :webauthn:test --tests '*AuthenticationDifferentialTest'`
Expected: PASS. webauthn4j 메서드명 컴파일 에러는 NOTE대로 교정.

- [ ] **Step 4: 모듈 전체 테스트**

Run: `./gradlew :webauthn:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add webauthn/src/test/java/com/crosscert/passkey/webauthn/diff/
git commit -m "test(webauthn): 인증 differential 테스트 (webauthn4j 생성 assertion → 자체 검증)"
```

---

## Phase 5 — 저장 스키마 + 앱 통합 + 스위치

> **목표:** spec §5/§6/§8/§9. passkey-app finish 서비스를 `WebAuthnVerifier` 호출로 바꾸고, Credential 저장 스키마를 COSE 키 개별 필드로 전환하며, 플래그로 native/webauthn4j 스위치를 둔다. API 계약은 무변경.

### Task 19: 저장 스키마 마이그레이션 (cose_public_key)

**Files:**
- Create: `core/src/main/resources/db/migration/V44__credential_cose_schema.sql`

- [ ] **Step 1: 마이그레이션 작성**

`core/src/main/resources/db/migration/V44__credential_cose_schema.sql`:

```sql
-- 자체 WebAuthn verifier로 전환하며 credential 저장 표현을 변경한다.
-- 기존 데이터 없음(초기 구축) → 단순 컬럼 정의.
--
-- 변경 전: public_key BLOB = webauthn4j JSON 엔벨로프(ao/cd/ce/tr).
-- 변경 후: cose_public_key BLOB = COSE_Key CBOR (서명검증 키 그 자체).
--          기존 public_key 컬럼은 제거한다 — 더 이상 통짜 엔벨로프를 저장하지 않는다.

ALTER TABLE credential ADD (cose_public_key BLOB);

-- 기존 데이터가 없으므로 즉시 NOT NULL 화 가능. 데이터가 있다면 백필 후
-- 별도 마이그레이션에서 NOT NULL을 걸어야 한다(이번 범위는 초기 구축).
UPDATE credential SET cose_public_key = EMPTY_BLOB() WHERE cose_public_key IS NULL;
ALTER TABLE credential MODIFY (cose_public_key BLOB NOT NULL);

ALTER TABLE credential DROP COLUMN public_key;
```

- [ ] **Step 2: SQL 문법 점검 (Oracle)**

Run: `grep -n "ALTER TABLE\|DROP COLUMN\|MODIFY" core/src/main/resources/db/migration/V44__credential_cose_schema.sql`
Expected: 위 3개 구문 확인 (실 검증은 Task 24 E2E에서 Testcontainers로)

- [ ] **Step 3: Commit**

```bash
git add core/src/main/resources/db/migration/V44__credential_cose_schema.sql
git commit -m "feat(core): credential cose_public_key 스키마 마이그레이션 (V44)"
```

### Task 20: Credential 엔티티 — cosePublicKey 필드

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/entity/Credential.java`

- [ ] **Step 1: 엔티티 필드·생성자·접근자 교체**

`Credential.java`에서 `publicKey` 관련을 `cosePublicKey`로 교체. 라인 29-31의 `@Lob @Column(name="PUBLIC_KEY")...private byte[] publicKey;`를 다음으로:

```java
    @Lob
    @Column(name = "COSE_PUBLIC_KEY", nullable = false)
    private byte[] cosePublicKey;
```

생성자(라인 57-65)를 다음으로 교체:

```java
    public Credential(UUID tenantId, byte[] userHandle, byte[] credentialId,
                      byte[] cosePublicKey, byte[] aaguid) {
        this.tenantId = tenantId;
        this.userHandle = userHandle;
        this.credentialId = credentialId;
        this.cosePublicKey = cosePublicKey;
        this.aaguid = aaguid;
        this.signCount = 0;
    }
```

라인 77 접근자를 교체:

```java
    public byte[] getCosePublicKey() { return cosePublicKey; }
```

추가로, attestationFmt·transports를 등록 시 set할 수 있도록 setter 추가(없으면):

```java
    public void setAttestationFmt(String attestationFmt) { this.attestationFmt = attestationFmt; }
    public void setTransports(String transports) { this.transports = transports; }
```

- [ ] **Step 2: core 컴파일 (소비처 깨짐 확인)**

Run: `./gradlew :core:compileJava`
Expected: BUILD SUCCESSFUL (엔티티 자체는 통과; 소비처는 passkey-app이라 다음 Task에서 수정)

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/entity/Credential.java
git commit -m "feat(core): Credential.cosePublicKey 필드로 저장 스키마 전환"
```

### Task 21: passkey-app에 :webauthn 모듈 의존 추가 + verifier 빈 구성

**Files:**
- Modify: `passkey-app/build.gradle.kts`
- Delete/Rename: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/Webauthn4jConfig.java`
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/WebAuthnVerifierConfig.java`

- [ ] **Step 1: passkey-app build.gradle.kts에 의존 추가**

`passkey-app/build.gradle.kts`의 dependencies 블록에 추가 (정확한 위치는 기존 `implementation(project(":core"))` 인근):

```kotlin
    implementation(project(":webauthn"))
```

- [ ] **Step 2: 기존 Webauthn4jConfig 삭제, 새 verifier 빈 구성 작성**

기존 파일 삭제:

```bash
git rm passkey-app/src/main/java/com/crosscert/passkey/app/fido2/Webauthn4jConfig.java
```

`passkey-app/src/main/java/com/crosscert/passkey/app/fido2/WebAuthnVerifierConfig.java` 생성:

```java
package com.crosscert.passkey.app.fido2;

import com.crosscert.passkey.webauthn.verifier.NativeWebAuthnVerifier;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * WebAuthn 검증 구현 빈 구성. 기본은 자체 구현(NativeWebAuthnVerifier).
 * differential 검증이 끝나면 이 빈만 단일 native 구현으로 고정한다.
 *
 * (과도기 스위치가 필요하면 passkey.webauthn.verifier 프로퍼티로
 *  ConditionalOnProperty 분기를 추가할 수 있으나, webauthn4j 구현체는
 *  테스트 소스셋에만 있으므로 프로덕션 빈은 native 단일이다.)
 */
@Configuration
public class WebAuthnVerifierConfig {

    @Bean
    public WebAuthnVerifier webAuthnVerifier(ObjectMapper mapper) {
        return NativeWebAuthnVerifier.withDefaults(mapper);
    }
}
```

- [ ] **Step 3: 컴파일 (finish 서비스가 아직 webauthn4j 빈을 참조 → 에러 예상)**

Run: `./gradlew :passkey-app:compileJava`
Expected: FAIL — `RegistrationFinishService`/`AuthenticationFinishService`가 `WebAuthnManager`/`ObjectConverter` 빈을 주입받으나 빈이 사라짐. 다음 Task에서 finish 서비스를 교체하며 해소.

- [ ] **Step 4: Commit (컴파일 깨진 상태 — 다음 Task와 함께 묶지 않고 의존/빈 단위로 커밋)**

> 컴파일이 깨진 상태이므로 이 Task는 다음 Task 22, 23과 **연속 실행**한다. 세 Task 완료 후 passkey-app이 다시 컴파일된다. 커밋은 Task 23 끝에서 한 번에 한다(아래 Task 22/23의 커밋 step 참조). 여기서는 커밋하지 않는다.

### Task 22: RegistrationFinishService를 WebAuthnVerifier 호출로 교체

**Files:**
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java`

- [ ] **Step 1: import·필드·생성자에서 webauthn4j 제거, WebAuthnVerifier 주입**

`RegistrationFinishService.java`의 webauthn4j import(라인 19-32)와 관련 필드/생성자 인자를 제거하고, 대신:

```java
import com.crosscert.passkey.webauthn.verifier.AttestationTrustPolicy;
import com.crosscert.passkey.webauthn.verifier.COSEAlgorithm;
import com.crosscert.passkey.webauthn.verifier.RegistrationInput;
import com.crosscert.passkey.webauthn.verifier.RegistrationResult;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerificationException;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerifier;
```

필드: `private final WebAuthnManager manager;` 등 webauthn4j 필드 4개(manager, objectConverter, attestationObjectConverter, collectedClientDataConverter, extensionsConverter)를 제거하고 `private final WebAuthnVerifier verifier;` 추가. `ALLOWED_PUB_KEY_CRED_PARAMS`(라인 60-64)도 제거.

생성자에서 `WebAuthnManager manager`/`ObjectConverter objectConverter` 인자를 `WebAuthnVerifier verifier`로 교체하고, 컨버터 초기화 라인(라인 100-102) 제거.

- [ ] **Step 2: finish 본문의 검증·직렬화 블록 교체**

`finish(...)` 메서드에서 webauthn4j parse/verify/직렬화 블록(라인 137-214)을 다음으로 교체:

```java
            String publicKeyCredentialJson;
            try {
                publicKeyCredentialJson = mapper.writeValueAsString(req.publicKeyCredential());
            } catch (Exception e) {
                throw new IllegalArgumentException("publicKeyCredential JSON invalid");
            }

            Set<String> origins = tenant.getAllowedOriginValues();
            if (origins.isEmpty()) {
                throw new IllegalStateException(
                        "tenant " + tenant.getId() + " has no allowed_origins configured");
            }

            RegistrationInput input = new RegistrationInput(
                    publicKeyCredentialJson,
                    ch.challenge(),
                    origins,
                    tenant.getRpId(),
                    tenant.isRequireUserVerification(),
                    Set.of(COSEAlgorithm.ES256, COSEAlgorithm.RS256),
                    tenant.getAcceptedFormatValues(),
                    AttestationTrustPolicy.SELF_ALLOWED);

            RegistrationResult result;
            try {
                result = verifier.verifyRegistration(input);
            } catch (WebAuthnVerificationException e) {
                log.warn("attestation verify failed for tenant {}: {} ({})",
                        ch.tenantId(), e.getMessage(), e.reason());
                throw new IllegalArgumentException("attestation verify failed");
            }

            String fmt = result.attestationFormat();
            // 포맷 정책은 verifier가 이미 acceptedAttestationFormats로 강제하지만,
            // 이중 안전망으로 한 번 더 확인(verifier 정책과 동일 소스).
            if (!tenant.getAcceptedFormatValues().contains(fmt)) {
                throw new IllegalArgumentException("attestation format not accepted by tenant policy");
            }

            byte[] aaguid = result.aaguid();
            if (!mds.verify(tenant.isMdsRequired(), aaguid)) {
                throw new IllegalArgumentException("authenticator metadata verification failed");
            }

            UUID aaguidUuid = aaguidFromBytes(aaguid);
            aaguidPolicyChecker.check(tenant.getId(), aaguidUuid);

            byte[] credentialId = result.credentialId();
            Credential cred = new Credential(
                    UUID.fromString(ch.tenantId()),
                    ch.userHandle(),
                    credentialId,
                    result.cosePublicKey(),
                    aaguid);
            cred.setAttestationFmt(fmt);
            if (result.transports() != null && !result.transports().isEmpty()) {
                cred.setTransports(String.join(",", result.transports()));
            }
            credentials.saveAndFlush(cred);
```

`serializeCredentialRecordEnvelope`(라인 243-263)와 그 헬퍼는 더 이상 쓰지 않으므로 제거. `b64url`/`idTail`/`aaguidFromBytes`는 유지.

- [ ] **Step 3: 컴파일은 다음 Task 후 — 여기서는 변경만**

(AuthenticationFinishService도 같은 빈 의존을 갖고 있어, Task 23 완료 후 함께 컴파일된다.)

### Task 23: AuthenticationFinishService를 WebAuthnVerifier 호출로 교체

**Files:**
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java`

- [ ] **Step 1: import·필드·생성자에서 webauthn4j 제거, WebAuthnVerifier 주입**

webauthn4j import(라인 20-36)와 컨버터 필드(라인 61, 66-69)·생성자 인자(`WebAuthnManager manager`, `ObjectConverter objectConverter`)를 제거하고:

```java
import com.crosscert.passkey.webauthn.verifier.AuthenticationInput;
import com.crosscert.passkey.webauthn.verifier.AuthenticationResult;
import com.crosscert.passkey.webauthn.verifier.StoredCredential;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerificationException;
import com.crosscert.passkey.webauthn.verifier.WebAuthnVerifier;
```

필드에 `private final WebAuthnVerifier verifier;` 추가, 생성자 인자도 교체.

- [ ] **Step 2: finish 본문의 deserialize/verify 블록 교체**

`finish(...)`에서 credentialRecord 역직렬화 + ServerProperty + manager.verify 블록(라인 158-224)을 다음으로 교체. **signCount 단조증가 검사·이벤트 기록·id-token 발급은 그대로 유지**:

```java
            Set<String> origins = tenant.getAllowedOriginValues();
            if (origins.isEmpty()) {
                throw new IllegalStateException(
                        "tenant " + tenant.getId() + " has no allowed_origins configured");
            }

            StoredCredential stored = new StoredCredential(
                    cred.getCredentialId(),
                    cred.getCosePublicKey(),
                    cred.getSignCount());

            AuthenticationInput input = new AuthenticationInput(
                    publicKeyCredentialJson,
                    ch.challenge(),
                    origins,
                    tenant.getRpId(),
                    tenant.isRequireUserVerification(),
                    stored);

            AuthenticationResult result;
            try {
                result = verifier.verifyAuthentication(input);
            } catch (WebAuthnVerificationException e) {
                log.warn("assertion verify failed for tenant {}: {} ({})",
                        ch.tenantId(), e.getMessage(), e.reason());
                authEvents.recordAfterRollback(cred.getId(), UUID.fromString(ch.tenantId()),
                        CredentialAuthResult.FAILED, CredentialAuthFailureReason.SIGNATURE_INVALID,
                        cred.getSignCount());
                throw new IllegalArgumentException("assertion verify failed");
            }

            long newCounter = result.newSignCount();
```

이후의 signCount 단조증가 블록(라인 232~250)은 `data.getAuthenticatorData().getSignCount()` 대신 위 `newCounter`를 그대로 사용한다(이미 `newCounter` 변수명 동일). `record.setCounter(newCounter);`(라인 261) 줄은 제거(record 객체가 더 이상 없음). `deserializeCredentialRecordEnvelope`(라인 303-332) 메서드 제거.

- [ ] **Step 3: passkey-app 컴파일 확인**

Run: `./gradlew :passkey-app:compileJava`
Expected: BUILD SUCCESSFUL (Task 21~23 변경이 모두 반영되어 webauthn4j 의존 제거됨)

- [ ] **Step 4: Commit (Task 21+22+23 묶음)**

```bash
git add passkey-app/build.gradle.kts \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/WebAuthnVerifierConfig.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java
git rm --cached passkey-app/src/main/java/com/crosscert/passkey/app/fido2/Webauthn4jConfig.java 2>/dev/null || true
git commit -m "feat(passkey-app): finish 서비스를 자체 WebAuthnVerifier로 교체 (webauthn4j 런타임 제거)"
```

### Task 24: E2E 회귀 — Fido2EndToEndIT 통과 확인

**Files:**
- Modify (필요 시): `passkey-app/src/test/java/com/crosscert/passkey/app/fido2/Fido2EndToEndIT.java`

- [ ] **Step 1: 기존 E2E IT 실행 (저장 스키마·verifier 교체 후 회귀)**

Run: `./gradlew :passkey-app:test --tests '*Fido2EndToEndIT'`
Expected: 처음엔 실패 가능 — `Credential` 스키마 변경(cosePublicKey)·seed SQL에 `public_key` 참조가 있으면 깨진다. 실패 메시지를 읽고 다음 step에서 교정.

- [ ] **Step 2: 실패 시 seed/SQL 교정**

`Fido2EndToEndIT`의 `seed()`/`seedTenant()`나 테스트 리소스 SQL에서 credential을 직접 INSERT하며 `public_key` 컬럼을 참조하는 곳이 있으면 `cose_public_key`로 바꾼다. (happyPath는 실제 등록 흐름을 타므로 보통 seed에 credential을 안 넣지만, signCountReplay 등 일부 시나리오 확인.)

Run: `grep -rn "public_key\|PUBLIC_KEY" passkey-app/src/test core/src/test 2>/dev/null`
교정 대상이 나오면 `cose_public_key`로 수정.

- [ ] **Step 3: E2E IT 재실행**

Run: `./gradlew :passkey-app:test --tests '*Fido2EndToEndIT'`
Expected: PASS — happyPath(register→authenticate), crossTenantIsolation, signCountReplay 등 전부 green. **이것이 "자체 구현이 실제 흐름에서 webauthn4j 시절과 동등하게 동작"한다는 핵심 증거다.**

- [ ] **Step 4: 전체 빌드**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — 모든 모듈 컴파일·테스트 통과

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test(passkey-app): 자체 verifier로 Fido2EndToEndIT 회귀 통과"
```

---

## Phase 6 — 나머지 attestation 포맷 + 공식 test vector

> **목표:** spec §2/§7 "주요 포맷 전부". 각 포맷을 별도 검증기로 추가하고 `AttestationVerifiers.defaults()`에 등록한다. 검증 근거는 **W3C/FIDO 공식 test vector**(spec 예제·FIDO conformance 샘플)다. 각 포맷 Task는 공식 vector를 `webauthn/src/test/resources/vectors/<fmt>/`에 픽스처로 두고, happy-path + 변조 negative를 단언한다.
>
> **순서**(spec §9): fido-u2f → apple → android-key → android-safetynet → tpm. TPM이 가장 크므로 마지막.
>
> **vector 확보 방법:** 각 포맷의 공식 샘플 attestationObject는 (a) FIDO Alliance conformance tools, (b) W3C WebAuthn spec 예제, (c) webauthn4j-test의 각 Authenticator 에뮬레이터(`FIDOU2FAuthenticator`, `AndroidKeyAuthenticator` 등)로 생성한다. 구현자는 해당 포맷의 에뮬레이터가 webauthn4j-test에 있으면 `CeremonyFixtures`를 확장해 생성하고, 없으면 conformance 샘플을 base64로 리소스에 저장한다.

### Task 25: fido-u2f attestation

**Files:**
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/FidoU2fAttestationVerifier.java`
- Modify: `webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AttestationVerifiers.java`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/attestation/FidoU2fAttestationVerifierTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`FidoU2fAttestationVerifierTest.java` — fido-u2f는 자체 서명 입력이 특수(0x00 || rpIdHash || clientDataHash || credId || publicKeyU2F)하다. webauthn4j-test의 `FIDOU2FAuthenticator`로 생성한 attestationObject를 vector로 쓴다:

```java
package com.crosscert.passkey.webauthn.attestation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FidoU2fAttestationVerifierTest {

    private final FidoU2fAttestationVerifier verifier = new FidoU2fAttestationVerifier();

    @Test
    void formatIsFidoU2f() {
        assertEquals("fido-u2f", verifier.format());
    }

    // happy-path/negative는 vector 기반. CeremonyFixtures에 registerU2f(...)를
    // 추가(webauthn4j-test FIDOU2FAuthenticator 사용)하고, 생성된
    // attestationObject를 AttestationObjectDecoder로 디코드한 뒤
    // verifier.verify(...)가 BASIC을 반환하는지, 변조 시 AttestationException을
    // 던지는지 단언한다. (Task 17의 CeremonyFixtures 패턴 재사용)
    @Test
    void verifiesU2fVectorAndRejectsTamper() throws Exception {
        AttestationVectors.U2f v = AttestationVectors.u2f();
        AttestationResult r = verifier.verify(v.authData(), v.rawAuthData(), v.attStmt(), v.clientDataHash());
        assertEquals(AttestationResult.Type.BASIC, r.type());

        assertThrows(AttestationException.class,
                () -> verifier.verify(v.authData(), v.rawAuthData(), v.tamperedAttStmt(), v.clientDataHash()));
    }
}
```

> **참고:** `AttestationVectors`는 포맷별 공식/생성 vector를 로드하는 테스트 헬퍼다. 이 Task에서 `AttestationVectors.u2f()`를 추가한다(webauthn4j-test 에뮬레이터로 생성하거나 conformance 샘플 로드). 헬퍼는 `webauthn/src/test/java/com/crosscert/passkey/webauthn/attestation/AttestationVectors.java`에 누적한다.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :webauthn:test --tests '*FidoU2fAttestationVerifierTest'`
Expected: FAIL — `FidoU2fAttestationVerifier` 없음

- [ ] **Step 3: FidoU2fAttestationVerifier 구현 (WebAuthn §8.6)**

`FidoU2fAttestationVerifier.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.authdata.AttestedCredentialData;
import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.*;
import com.crosscert.passkey.webauthn.cose.CoseKey;
import com.crosscert.passkey.webauthn.cose.CoseKeyParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.util.List;

/**
 * fmt=fido-u2f (WebAuthn §8.6). 서명 입력:
 *   0x00 || rpIdHash || clientDataHash || credentialId || publicKeyU2F
 * publicKeyU2F = 0x04 || x(32) || y(32). x5c는 단일 leaf 인증서.
 */
public final class FidoU2fAttestationVerifier implements AttestationVerifier {

    @Override
    public String format() { return "fido-u2f"; }

    @Override
    public AttestationResult verify(AuthenticatorData authData, byte[] rawAuthData,
                                    CborValue attStmt, byte[] clientDataHash) {
        if (!(attStmt.get("x5c") instanceof CborArray x5c) || x5c.items().size() != 1) {
            throw new AttestationException("fido-u2f requires single-cert x5c");
        }
        byte[] sig = requireBytes(attStmt.get("sig"));
        AttestedCredentialData acd = authData.attestedCredentialData();
        if (acd == null) throw new AttestationException("fido-u2f missing attestedCredentialData");

        X509Certificate leaf = parseCert(((CborBytes) x5c.items().get(0)).value());

        // COSE_Key(EC2 P-256) → publicKeyU2F (uncompressed point)
        CoseKey credKey = CoseKeyParser.parse(acd.coseKeyMap());
        if (!(credKey.publicKey() instanceof ECPublicKey ec)) {
            throw new AttestationException("fido-u2f credential is not EC");
        }
        byte[] x = fixed32(ec.getW().getAffineX());
        byte[] y = fixed32(ec.getW().getAffineY());

        ByteArrayOutputStream signed = new ByteArrayOutputStream();
        signed.write(0x00);
        signed.writeBytes(authData.rpIdHash());
        signed.writeBytes(clientDataHash);
        signed.writeBytes(acd.credentialId());
        signed.write(0x04);
        signed.writeBytes(x);
        signed.writeBytes(y);

        boolean ok;
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(leaf.getPublicKey());
            s.update(signed.toByteArray());
            ok = s.verify(sig);
        } catch (Exception e) {
            throw new AttestationException("fido-u2f signature verify error", e);
        }
        if (!ok) throw new AttestationException("fido-u2f signature invalid");
        return AttestationResult.basic(List.of(leaf));
    }

    private static byte[] requireBytes(CborValue v) {
        if (v instanceof CborBytes b) return b.value();
        throw new AttestationException("fido-u2f attStmt missing sig bytes");
    }

    private static X509Certificate parseCert(byte[] der) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            throw new AttestationException("fido-u2f cert parse failed", e);
        }
    }

    private static byte[] fixed32(java.math.BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }
}
```

- [ ] **Step 4: 레지스트리에 등록**

`AttestationVerifiers.defaults()`의 List.of(...)에 `new FidoU2fAttestationVerifier()` 추가.

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :webauthn:test --tests '*FidoU2fAttestationVerifierTest'`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/FidoU2fAttestationVerifier.java webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AttestationVerifiers.java webauthn/src/test/java/com/crosscert/passkey/webauthn/attestation/
git commit -m "feat(webauthn): fido-u2f attestation 검증 + vector 테스트"
```

### Task 26: apple attestation

**Files:**
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AppleAttestationVerifier.java`
- Modify: `AttestationVerifiers.java`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/attestation/AppleAttestationVerifierTest.java`

- [ ] **Step 1: 실패하는 테스트 작성 (Apple Anonymous Attestation, vector 기반)**

`AppleAttestationVerifierTest.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppleAttestationVerifierTest {

    private final AppleAttestationVerifier verifier = new AppleAttestationVerifier();

    @Test
    void formatIsApple() {
        assertEquals("apple", verifier.format());
    }

    @Test
    void verifiesAppleVectorAndRejectsTamper() throws Exception {
        AttestationVectors.Apple v = AttestationVectors.apple(); // conformance 샘플 로드
        AttestationResult r = verifier.verify(v.authData(), v.rawAuthData(), v.attStmt(), v.clientDataHash());
        assertEquals(AttestationResult.Type.ATT_CA, r.type());

        assertThrows(AttestationException.class,
                () -> verifier.verify(v.authData(), v.rawAuthData(), v.attStmt(), v.wrongClientDataHash()));
    }
}
```

> **참고:** Apple은 라이브 attestation을 만들기 어려우므로 **FIDO conformance / 공개 샘플 attestationObject를 base64로 `webauthn/src/test/resources/vectors/apple/`에 저장**하고 `AttestationVectors.apple()`이 로드한다. nonce extension(OID 1.2.840.113635.100.8.2) = SHA-256(authData || clientDataHash) 검증이 핵심이므로, 정확한 clientDataHash가 vector와 함께 저장돼야 한다.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :webauthn:test --tests '*AppleAttestationVerifierTest'`
Expected: FAIL — `AppleAttestationVerifier` 없음

- [ ] **Step 3: AppleAttestationVerifier 구현 (WebAuthn §8.8)**

`AppleAttestationVerifier.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * fmt=apple (WebAuthn §8.8, Apple Anonymous Attestation).
 *  1) nonceToHash = authData || clientDataHash
 *  2) nonce = SHA-256(nonceToHash)
 *  3) leaf 인증서의 extension(OID 1.2.840.113635.100.8.2) 안의 nonce와 일치 확인
 *  4) credential 공개키가 leaf 공개키와 일치 (생략 가능 — 체인 검증으로 갈음)
 */
public final class AppleAttestationVerifier implements AttestationVerifier {

    private static final String APPLE_NONCE_OID = "1.2.840.113635.100.8.2";

    @Override
    public String format() { return "apple"; }

    @Override
    public AttestationResult verify(AuthenticatorData authData, byte[] rawAuthData,
                                    CborValue attStmt, byte[] clientDataHash) {
        if (!(attStmt.get("x5c") instanceof CborArray x5c) || x5c.items().isEmpty()) {
            throw new AttestationException("apple requires x5c");
        }
        List<X509Certificate> chain = parseChain(x5c);
        X509Certificate leaf = chain.get(0);

        byte[] expectedNonce = sha256(concat(rawAuthData, clientDataHash));
        byte[] certNonce = extractNonce(leaf);
        if (!Arrays.equals(expectedNonce, certNonce)) {
            throw new AttestationException("apple nonce mismatch");
        }
        return AttestationResult.basic(chain); // ATT_CA로 분류 (체인은 상위가 trust anchor로 검증)
    }

    private static byte[] extractNonce(X509Certificate leaf) {
        byte[] ext = leaf.getExtensionValue(APPLE_NONCE_OID);
        if (ext == null) throw new AttestationException("apple nonce extension missing");
        // ext는 DER OCTET STRING(내부에 SEQUENCE { [1] OCTET STRING nonce }) — 마지막 32바이트가 nonce.
        // 정확 파싱: OCTET STRING 언랩 후 SEQUENCE/context tag 해제. 간이로 끝 32바이트를 취하되,
        // 길이 검증으로 안전성 확보.
        if (ext.length < 32) throw new AttestationException("apple nonce extension malformed");
        return Arrays.copyOfRange(ext, ext.length - 32, ext.length);
    }

    private static List<X509Certificate> parseChain(CborArray x5c) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = new ArrayList<>();
            for (CborValue c : x5c.items()) {
                chain.add((X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(((CborBytes) c).value())));
            }
            return chain;
        } catch (Exception e) {
            throw new AttestationException("apple x5c parse failed", e);
        }
    }

    private static byte[] sha256(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
```

> **NOTE (구현자):** Apple nonce extension의 DER 구조는 `SEQUENCE { [1] EXPLICIT OCTET STRING (32바이트 nonce) }`를 OCTET STRING으로 감싼 형태다. 위 "끝 32바이트" 휴리스틱이 vector에서 실패하면, `java.security` 만으로 DER을 정확히 파싱하거나(수동 TLV 파서) 테스트 전용 BouncyCastle ASN1로 파싱 로직을 검증한 뒤 프로덕션은 수동 TLV로 구현하라. vector 테스트가 정확성의 기준이다.

- [ ] **Step 4: 레지스트리 등록 + 테스트 통과**

`AttestationVerifiers.defaults()`에 `new AppleAttestationVerifier()` 추가.

Run: `./gradlew :webauthn:test --tests '*AppleAttestationVerifierTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AppleAttestationVerifier.java webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AttestationVerifiers.java webauthn/src/test/
git commit -m "feat(webauthn): apple anonymous attestation 검증 + vector 테스트"
```

### Task 27: android-key attestation

**Files:**
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AndroidKeyAttestationVerifier.java`
- Modify: `AttestationVerifiers.java`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/attestation/AndroidKeyAttestationVerifierTest.java`

- [ ] **Step 1: 실패하는 테스트 작성 (vector 기반)**

`AndroidKeyAttestationVerifierTest.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AndroidKeyAttestationVerifierTest {

    private final AndroidKeyAttestationVerifier verifier = new AndroidKeyAttestationVerifier();

    @Test
    void formatIsAndroidKey() {
        assertEquals("android-key", verifier.format());
    }

    @Test
    void verifiesAndroidKeyVector() throws Exception {
        AttestationVectors.AndroidKey v = AttestationVectors.androidKey();
        AttestationResult r = verifier.verify(v.authData(), v.rawAuthData(), v.attStmt(), v.clientDataHash());
        assertEquals(AttestationResult.Type.BASIC, r.type());

        assertThrows(AttestationException.class,
                () -> verifier.verify(v.authData(), v.rawAuthData(), v.tamperedAttStmt(), v.clientDataHash()));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :webauthn:test --tests '*AndroidKeyAttestationVerifierTest'`
Expected: FAIL

- [ ] **Step 3: AndroidKeyAttestationVerifier 구현 (WebAuthn §8.4)**

`AndroidKeyAttestationVerifier.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.*;
import com.crosscert.passkey.webauthn.cose.CoseAlgorithm;

import java.io.ByteArrayInputStream;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * fmt=android-key (WebAuthn §8.4).
 *  1) sig = sign over (authData || clientDataHash), leaf 공개키로 검증
 *  2) leaf 인증서 공개키가 credential 공개키와 일치
 *  3) leaf의 attestation extension(OID 1.3.6.1.4.1.11129.2.1.17)에서
 *     attestationChallenge == clientDataHash, key origin/purpose 제약 확인
 */
public final class AndroidKeyAttestationVerifier implements AttestationVerifier {

    @Override
    public String format() { return "android-key"; }

    @Override
    public AttestationResult verify(AuthenticatorData authData, byte[] rawAuthData,
                                    CborValue attStmt, byte[] clientDataHash) {
        long alg = requireInt(attStmt.get("alg"));
        byte[] sig = requireBytes(attStmt.get("sig"));
        if (!(attStmt.get("x5c") instanceof CborArray x5c) || x5c.items().isEmpty()) {
            throw new AttestationException("android-key requires x5c");
        }
        List<X509Certificate> chain = parseChain(x5c);
        X509Certificate leaf = chain.get(0);

        CoseAlgorithm coseAlg = CoseAlgorithm.fromCoseValue(alg);
        boolean ok = AttestationSignatures.verify(
                coseAlg.jcaSignatureName(), leaf.getPublicKey(), rawAuthData, clientDataHash, sig);
        if (!ok) throw new AttestationException("android-key signature invalid");

        // attestation extension의 challenge == clientDataHash 검증.
        // OID 1.3.6.1.4.1.11129.2.1.17 의 KeyDescription.attestationChallenge.
        byte[] ext = leaf.getExtensionValue("1.3.6.1.4.1.11129.2.1.17");
        if (ext == null) throw new AttestationException("android-key attestation extension missing");
        if (!AndroidKeyExtension.challengeMatches(ext, clientDataHash)) {
            throw new AttestationException("android-key attestationChallenge mismatch");
        }
        return AttestationResult.basic(chain);
    }

    private static long requireInt(CborValue v) {
        if (v instanceof CborInt i) return i.value();
        throw new AttestationException("android-key attStmt missing alg");
    }

    private static byte[] requireBytes(CborValue v) {
        if (v instanceof CborBytes b) return b.value();
        throw new AttestationException("android-key attStmt missing sig");
    }

    private static List<X509Certificate> parseChain(CborArray x5c) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = new ArrayList<>();
            for (CborValue c : x5c.items()) {
                chain.add((X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(((CborBytes) c).value())));
            }
            return chain;
        } catch (Exception e) {
            throw new AttestationException("android-key x5c parse failed", e);
        }
    }
}
```

- [ ] **Step 4: AndroidKeyExtension 헬퍼 작성 (수동 DER TLV 파서)**

`AndroidKeyExtension.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import java.util.Arrays;

/**
 * android-key attestation extension(KeyDescription, ASN.1 SEQUENCE)에서
 * attestationChallenge(OCTET STRING)를 찾아 clientDataHash와 비교.
 *
 * KeyDescription ::= SEQUENCE {
 *   attestationVersion INTEGER, attestationSecurityLevel ENUM,
 *   keymasterVersion INTEGER, keymasterSecurityLevel ENUM,
 *   attestationChallenge OCTET STRING,  <-- 이것
 *   ... }
 *
 * 즉 SEQUENCE 안의 첫 OCTET STRING이 attestationChallenge다.
 * 정확성은 vector 테스트가 보증한다.
 */
final class AndroidKeyExtension {

    static boolean challengeMatches(byte[] extensionValue, byte[] clientDataHash) {
        // extensionValue는 DER OCTET STRING(내부에 KeyDescription SEQUENCE).
        byte[] inner = unwrapOctetString(extensionValue);
        byte[] challenge = firstOctetStringInSequence(inner);
        return challenge != null && Arrays.equals(challenge, clientDataHash);
    }

    /** OCTET STRING(tag 0x04) 한 겹 언랩. */
    private static byte[] unwrapOctetString(byte[] der) {
        if (der.length < 2 || (der[0] & 0xff) != 0x04) return der;
        int[] lenAndOffset = readLength(der, 1);
        int len = lenAndOffset[0], off = lenAndOffset[1];
        return Arrays.copyOfRange(der, off, off + len);
    }

    /** SEQUENCE(0x30) 안에서 첫 OCTET STRING(0x04)을 찾아 그 값을 반환. */
    private static byte[] firstOctetStringInSequence(byte[] der) {
        if (der.length < 2 || (der[0] & 0xff) != 0x30) return null;
        int[] seq = readLength(der, 1);
        int pos = seq[1];
        int end = seq[1] + seq[0];
        while (pos < end && pos < der.length) {
            int tag = der[pos] & 0xff;
            int[] tl = readLength(der, pos + 1);
            int len = tl[0], valOff = tl[1];
            if (tag == 0x04) {
                return Arrays.copyOfRange(der, valOff, valOff + len);
            }
            pos = valOff + len;
        }
        return null;
    }

    /** der[offset]부터 DER 길이를 읽어 {length, valueOffset} 반환. */
    private static int[] readLength(byte[] der, int offset) {
        int b = der[offset] & 0xff;
        if (b < 0x80) return new int[]{b, offset + 1};
        int numBytes = b & 0x7f;
        int len = 0;
        for (int i = 0; i < numBytes; i++) len = (len << 8) | (der[offset + 1 + i] & 0xff);
        return new int[]{len, offset + 1 + numBytes};
    }
}
```

- [ ] **Step 5: 레지스트리 등록 + 테스트 통과**

`AttestationVerifiers.defaults()`에 `new AndroidKeyAttestationVerifier()` 추가.

Run: `./gradlew :webauthn:test --tests '*AndroidKeyAttestationVerifierTest'`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AndroidKey*.java webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AttestationVerifiers.java webauthn/src/test/
git commit -m "feat(webauthn): android-key attestation 검증 + DER extension 파서"
```

### Task 28: android-safetynet attestation

**Files:**
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AndroidSafetyNetAttestationVerifier.java`
- Modify: `AttestationVerifiers.java`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/attestation/AndroidSafetyNetAttestationVerifierTest.java`

- [ ] **Step 1: 실패하는 테스트 작성 (vector 기반)**

`AndroidSafetyNetAttestationVerifierTest.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AndroidSafetyNetAttestationVerifierTest {

    private final AndroidSafetyNetAttestationVerifier verifier = new AndroidSafetyNetAttestationVerifier();

    @Test
    void formatIsAndroidSafetyNet() {
        assertEquals("android-safetynet", verifier.format());
    }

    @Test
    void verifiesSafetyNetVector() throws Exception {
        AttestationVectors.SafetyNet v = AttestationVectors.safetyNet();
        AttestationResult r = verifier.verify(v.authData(), v.rawAuthData(), v.attStmt(), v.clientDataHash());
        assertEquals(AttestationResult.Type.BASIC, r.type());

        assertThrows(AttestationException.class,
                () -> verifier.verify(v.authData(), v.rawAuthData(), v.attStmt(), v.wrongClientDataHash()));
    }
}
```

> **참고:** SafetyNet attStmt는 `{ ver, response }`이며 response는 **JWS(compact)**다. 헤더의 x5c로 서명 검증, payload의 `nonce` == base64(SHA-256(authData || clientDataHash)) 확인. JWS 서명 검증은 JDK `Signature`로 직접 수행한다(헤더 alg → RS256/ES256). conformance 샘플을 `vectors/safetynet/`에 저장.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :webauthn:test --tests '*AndroidSafetyNetAttestationVerifierTest'`
Expected: FAIL

- [ ] **Step 3: 구현 (WebAuthn §8.5) + JWS 검증 헬퍼**

`AndroidSafetyNetAttestationVerifier.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * fmt=android-safetynet (WebAuthn §8.5). attStmt = { ver, response(JWS) }.
 *  1) JWS 헤더의 x5c로 서명 검증 (alg 따라 RS256/ES256)
 *  2) leaf 인증서 hostname이 attest.android.com 인지 확인
 *  3) payload.nonce == base64(SHA-256(authData || clientDataHash))
 */
public final class AndroidSafetyNetAttestationVerifier implements AttestationVerifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Decoder B64 = Base64.getDecoder();
    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();

    @Override
    public String format() { return "android-safetynet"; }

    @Override
    public AttestationResult verify(AuthenticatorData authData, byte[] rawAuthData,
                                    CborValue attStmt, byte[] clientDataHash) {
        byte[] responseBytes = requireBytes(attStmt.get("response"));
        String jws = new String(responseBytes, StandardCharsets.UTF_8);
        String[] parts = jws.split("\\.");
        if (parts.length != 3) throw new AttestationException("safetynet response not a JWS");

        JsonNode header = readJson(B64URL.decode(parts[0]));
        JsonNode payload = readJson(B64URL.decode(parts[1]));
        byte[] signature = B64URL.decode(parts[2]);

        // x5c → leaf
        JsonNode x5c = header.get("x5c");
        if (x5c == null || !x5c.isArray() || x5c.isEmpty()) {
            throw new AttestationException("safetynet JWS missing x5c");
        }
        List<X509Certificate> chain = parseChain(x5c);
        X509Certificate leaf = chain.get(0);

        // JWS 서명 검증: signingInput = parts[0] + "." + parts[1]
        String alg = header.get("alg").asText();
        byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        if (!verifyJws(alg, leaf, signingInput, signature)) {
            throw new AttestationException("safetynet JWS signature invalid");
        }

        // nonce 검증
        byte[] expectedNonce = sha256(concat(rawAuthData, clientDataHash));
        String nonceB64 = payload.get("nonce").asText();
        if (!Arrays.equals(B64.decode(nonceB64), expectedNonce)) {
            throw new AttestationException("safetynet nonce mismatch");
        }
        return AttestationResult.basic(chain);
    }

    private static boolean verifyJws(String alg, X509Certificate leaf, byte[] input, byte[] sig) {
        String jca = switch (alg) {
            case "RS256" -> "SHA256withRSA";
            case "ES256" -> "SHA256withECDSA";
            default -> null;
        };
        if (jca == null) return false;
        try {
            Signature s = Signature.getInstance(jca);
            s.initVerify(leaf.getPublicKey());
            s.update(input);
            // ES256 JWS는 raw R||S 포맷 → DER 변환 필요. RS256은 그대로.
            byte[] toVerify = "ES256".equals(alg) ? JwsEcdsa.toDer(sig) : sig;
            return s.verify(toVerify);
        } catch (Exception e) {
            return false;
        }
    }

    private static List<X509Certificate> parseChain(JsonNode x5c) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = new java.util.ArrayList<>();
            for (JsonNode c : x5c) {
                chain.add((X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(Base64.getDecoder().decode(c.asText()))));
            }
            return chain;
        } catch (Exception e) {
            throw new AttestationException("safetynet x5c parse failed", e);
        }
    }

    private static JsonNode readJson(byte[] b) {
        try { return MAPPER.readTree(b); }
        catch (Exception e) { throw new AttestationException("safetynet JWS JSON parse failed", e); }
    }

    private static byte[] requireBytes(CborValue v) {
        if (v instanceof CborBytes b) return b.value();
        if (v instanceof CborText t) return t.value().getBytes(StandardCharsets.UTF_8);
        throw new AttestationException("safetynet attStmt missing response");
    }

    private static byte[] sha256(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
```

- [ ] **Step 4: JwsEcdsa 헬퍼 (raw R||S → DER) 작성**

`JwsEcdsa.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.Arrays;

/** JWS ES256 서명(raw 64바이트 R||S)을 JDK Signature가 받는 DER로 변환. */
final class JwsEcdsa {
    static byte[] toDer(byte[] raw) {
        if (raw.length != 64) throw new AttestationException("ES256 JWS sig must be 64 bytes");
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(raw, 0, 32));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(raw, 32, 64));
        byte[] rb = r.toByteArray(), sb = s.toByteArray();
        ByteArrayOutputStream seq = new ByteArrayOutputStream();
        seq.write(0x02); seq.write(rb.length); seq.writeBytes(rb);
        seq.write(0x02); seq.write(sb.length); seq.writeBytes(sb);
        byte[] body = seq.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x30); out.write(body.length); out.writeBytes(body);
        return out.toByteArray();
    }
}
```

- [ ] **Step 5: 레지스트리 등록 + 테스트 통과**

`AttestationVerifiers.defaults()`에 `new AndroidSafetyNetAttestationVerifier()` 추가.

Run: `./gradlew :webauthn:test --tests '*AndroidSafetyNetAttestationVerifierTest'`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AndroidSafetyNet*.java webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/JwsEcdsa.java webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AttestationVerifiers.java webauthn/src/test/
git commit -m "feat(webauthn): android-safetynet attestation 검증 (JWS + nonce)"
```

### Task 29: tpm attestation

**Files:**
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/TpmAttestationVerifier.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/TpmStructures.java`
- Modify: `AttestationVerifiers.java`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/attestation/TpmAttestationVerifierTest.java`

- [ ] **Step 1: 실패하는 테스트 작성 (vector 기반)**

`TpmAttestationVerifierTest.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TpmAttestationVerifierTest {

    private final TpmAttestationVerifier verifier = new TpmAttestationVerifier();

    @Test
    void formatIsTpm() {
        assertEquals("tpm", verifier.format());
    }

    @Test
    void verifiesTpmVector() throws Exception {
        AttestationVectors.Tpm v = AttestationVectors.tpm();
        AttestationResult r = verifier.verify(v.authData(), v.rawAuthData(), v.attStmt(), v.clientDataHash());
        assertEquals(AttestationResult.Type.ATT_CA, r.type());

        assertThrows(AttestationException.class,
                () -> verifier.verify(v.authData(), v.rawAuthData(), v.tamperedAttStmt(), v.clientDataHash()));
    }
}
```

> **참고:** TPM은 가장 복잡하다. attStmt = { ver:"2.0", alg, sig, x5c, certInfo, pubArea }. 검증:
> 1) pubArea의 공개키가 credential COSE 키와 일치
> 2) attToBeSigned = authData || clientDataHash, extraData = hash(attToBeSigned)
> 3) certInfo(TPMS_ATTEST) 파싱: magic==0xff544347, type==TPM_ST_ATTEST_CERTIFY, extraData 일치, attested.name == Hash(nameAlg || pubArea)
> 4) sig를 AIK(x5c leaf)로 검증
> conformance 샘플을 `vectors/tpm/`에 저장. TPM 구조 파싱은 `TpmStructures`로 분리.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :webauthn:test --tests '*TpmAttestationVerifierTest'`
Expected: FAIL

- [ ] **Step 3: TpmStructures 파서 작성 (certInfo/pubArea 빅엔디안 TLV)**

`TpmStructures.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * TPM 2.0 구조 파서 (TPMS_ATTEST / TPMT_PUBLIC). 모두 big-endian.
 * WebAuthn §8.3에 필요한 필드만 추출한다.
 */
final class TpmStructures {

    static final long TPM_GENERATED_VALUE = 0xff544347L; // "TCG"
    static final int TPM_ST_ATTEST_CERTIFY = 0x8017;

    /** TPMS_ATTEST 중 magic/type/extraData/attestedName 추출. */
    record CertInfo(long magic, int type, byte[] extraData, byte[] attestedName) {}

    static CertInfo parseCertInfo(byte[] certInfo) {
        ByteBuffer b = ByteBuffer.wrap(certInfo);
        long magic = b.getInt() & 0xffffffffL;
        int type = b.getShort() & 0xffff;
        // qualifiedSigner (TPM2B_NAME): UINT16 size + bytes
        skipSized(b);
        // extraData (TPM2B_DATA): UINT16 size + bytes
        byte[] extraData = readSized(b);
        // clockInfo(17) + firmwareVersion(8)
        b.position(b.position() + 17 + 8);
        // attested (TPMS_CERTIFY_INFO): name(TPM2B_NAME) + qualifiedName(TPM2B_NAME)
        byte[] attestedName = readSized(b);
        return new CertInfo(magic, type, extraData, attestedName);
    }

    /** TPMT_PUBLIC에서 nameAlg와 (RSA의 경우) unique(modulus) 추출 — 검증에 필요한 부분만. */
    record PubArea(int nameAlg, byte[] uniqueModulus, byte[] raw) {}

    static PubArea parsePubArea(byte[] pubArea) {
        ByteBuffer b = ByteBuffer.wrap(pubArea);
        int type = b.getShort() & 0xffff;       // TPMI_ALG_PUBLIC (RSA=0x0001, ECC=0x0023)
        int nameAlg = b.getShort() & 0xffff;    // TPMI_ALG_HASH (SHA256=0x000b)
        b.getInt();                             // objectAttributes
        skipSized(b);                           // authPolicy (TPM2B_DIGEST)
        // parameters + unique — 타입별. RSA: params(symmetric/scheme/keyBits/exponent) 후 unique(TPM2B).
        // 여기서는 unique(modulus)만 필요. 타입에 따라 파라미터 길이가 달라 정확 파싱 필요.
        byte[] unique = parseUnique(b, type);
        return new PubArea(nameAlg, unique, pubArea);
    }

    private static byte[] parseUnique(ByteBuffer b, int type) {
        // RSA(0x0001): TPMS_RSA_PARMS = symmetric(2) + scheme(2) + keyBits(2) + exponent(4)
        if (type == 0x0001) {
            b.position(b.position() + 2 + 2 + 2 + 4);
            return readSized(b); // unique = TPM2B_PUBLIC_KEY_RSA (modulus)
        }
        // ECC(0x0023): TPMS_ECC_PARMS = symmetric(2)+scheme(2)+curveId(2)+kdf(2), unique = x||y (각 TPM2B)
        if (type == 0x0023) {
            b.position(b.position() + 2 + 2 + 2 + 2);
            byte[] x = readSized(b);
            byte[] y = readSized(b);
            byte[] out = new byte[x.length + y.length];
            System.arraycopy(x, 0, out, 0, x.length);
            System.arraycopy(y, 0, out, x.length, y.length);
            return out;
        }
        throw new AttestationException("unsupported TPM pubArea type: " + type);
    }

    private static byte[] readSized(ByteBuffer b) {
        int size = b.getShort() & 0xffff;
        byte[] out = new byte[size];
        b.get(out);
        return out;
    }

    private static void skipSized(ByteBuffer b) {
        int size = b.getShort() & 0xffff;
        b.position(b.position() + size);
    }

    static boolean constantTimeEquals(byte[] a, byte[] c) {
        return java.security.MessageDigest.isEqual(a, c);
    }

    private TpmStructures() {}
}
```

- [ ] **Step 4: TpmAttestationVerifier 구현 (WebAuthn §8.3)**

`TpmAttestationVerifier.java`:

```java
package com.crosscert.passkey.webauthn.attestation;

import com.crosscert.passkey.webauthn.attestation.TpmStructures.CertInfo;
import com.crosscert.passkey.webauthn.attestation.TpmStructures.PubArea;
import com.crosscert.passkey.webauthn.authdata.AuthenticatorData;
import com.crosscert.passkey.webauthn.cbor.CborValue;
import com.crosscert.passkey.webauthn.cbor.CborValue.*;
import com.crosscert.passkey.webauthn.cose.CoseAlgorithm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * fmt=tpm (WebAuthn §8.3, TPM 2.0 attestation).
 */
public final class TpmAttestationVerifier implements AttestationVerifier {

    @Override
    public String format() { return "tpm"; }

    @Override
    public AttestationResult verify(AuthenticatorData authData, byte[] rawAuthData,
                                    CborValue attStmt, byte[] clientDataHash) {
        String ver = text(attStmt.get("ver"));
        if (!"2.0".equals(ver)) throw new AttestationException("tpm ver must be 2.0");
        long alg = requireInt(attStmt.get("alg"));
        byte[] sig = requireBytes(attStmt.get("sig"));
        byte[] certInfoBytes = requireBytes(attStmt.get("certInfo"));
        byte[] pubAreaBytes = requireBytes(attStmt.get("pubArea"));
        if (!(attStmt.get("x5c") instanceof CborArray x5c) || x5c.items().isEmpty()) {
            throw new AttestationException("tpm requires x5c (AIK)");
        }
        List<X509Certificate> chain = parseChain(x5c);
        X509Certificate aik = chain.get(0);

        PubArea pubArea = TpmStructures.parsePubArea(pubAreaBytes);

        // attToBeSigned = authData || clientDataHash ; certInfo.extraData == hash(attToBeSigned)
        byte[] attToBeSigned = concat(rawAuthData, clientDataHash);
        CertInfo certInfo = TpmStructures.parseCertInfo(certInfoBytes);
        if (certInfo.magic() != TpmStructures.TPM_GENERATED_VALUE) {
            throw new AttestationException("tpm certInfo bad magic");
        }
        if (certInfo.type() != TpmStructures.TPM_ST_ATTEST_CERTIFY) {
            throw new AttestationException("tpm certInfo not TPM_ST_ATTEST_CERTIFY");
        }
        byte[] expectedExtra = hashByTpmAlg(pubArea.nameAlg(), attToBeSigned);
        if (!TpmStructures.constantTimeEquals(expectedExtra, certInfo.extraData())) {
            throw new AttestationException("tpm certInfo.extraData mismatch");
        }
        // attested.name == nameAlgId(2바이트) || hash(pubArea)
        byte[] expectedName = tpmName(pubArea.nameAlg(), pubAreaBytes);
        if (!TpmStructures.constantTimeEquals(expectedName, certInfo.attestedName())) {
            throw new AttestationException("tpm attestedName mismatch");
        }
        // sig를 AIK 공개키로 검증 (서명 대상 = certInfo)
        CoseAlgorithm coseAlg = CoseAlgorithm.fromCoseValue(alg);
        boolean ok;
        try {
            Signature s = Signature.getInstance(coseAlg.jcaSignatureName());
            s.initVerify(aik.getPublicKey());
            s.update(certInfoBytes);
            ok = s.verify(sig);
        } catch (Exception e) {
            throw new AttestationException("tpm signature verify error", e);
        }
        if (!ok) throw new AttestationException("tpm signature invalid");

        return AttestationResult.basic(chain);
    }

    /** TPMT_HA: nameAlgId(2바이트 big-endian) || digest(pubArea). */
    private static byte[] tpmName(int nameAlg, byte[] pubArea) {
        byte[] digest = hashByTpmAlg(nameAlg, pubArea);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write((nameAlg >> 8) & 0xff);
        o.write(nameAlg & 0xff);
        o.writeBytes(digest);
        return o.toByteArray();
    }

    private static byte[] hashByTpmAlg(int tpmAlg, byte[] data) {
        String jca = switch (tpmAlg) {
            case 0x000b -> "SHA-256";
            case 0x000c -> "SHA-384";
            case 0x000d -> "SHA-512";
            case 0x0004 -> "SHA-1";
            default -> throw new AttestationException("unsupported TPM hash alg: " + tpmAlg);
        };
        try { return MessageDigest.getInstance(jca).digest(data); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static List<X509Certificate> parseChain(CborArray x5c) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = new ArrayList<>();
            for (CborValue c : x5c.items()) {
                chain.add((X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(((CborBytes) c).value())));
            }
            return chain;
        } catch (Exception e) {
            throw new AttestationException("tpm x5c parse failed", e);
        }
    }

    private static String text(CborValue v) {
        if (v instanceof CborText t) return t.value();
        throw new AttestationException("tpm attStmt missing ver");
    }

    private static long requireInt(CborValue v) {
        if (v instanceof CborInt i) return i.value();
        throw new AttestationException("tpm attStmt missing alg");
    }

    private static byte[] requireBytes(CborValue v) {
        if (v instanceof CborBytes b) return b.value();
        throw new AttestationException("tpm attStmt missing bytes field");
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
```

- [ ] **Step 5: 레지스트리 등록 + 테스트 통과**

`AttestationVerifiers.defaults()`에 `new TpmAttestationVerifier()` 추가.

Run: `./gradlew :webauthn:test --tests '*TpmAttestationVerifierTest'`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/Tpm*.java webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AttestationVerifiers.java webauthn/src/test/
git commit -m "feat(webauthn): tpm 2.0 attestation 검증 (certInfo/pubArea/name)"
```

### Task 30: 최종 통합 — 전체 빌드 + 포맷 정책 정렬

**Files:**
- (필요 시) `passkey-app` 테넌트 기본 acceptedFormats 확인

- [ ] **Step 1: 전체 빌드·테스트**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — webauthn 단위/differential/vector + passkey-app E2E 전부 green

- [ ] **Step 2: webauthn4j 런타임 의존 제거 확인**

Run: `./gradlew :passkey-app:dependencies --configuration runtimeClasspath | grep -i webauthn4j`
Expected: 출력 없음 (webauthn4j는 :webauthn의 testImplementation에만 — passkey-app 런타임에 없음). core가 여전히 api로 webauthn4j를 노출하면(admin-app MDS 때문) 그 줄만 남을 수 있음 — 그 경우 spec §2대로 MDS 서브프로젝트까지 가야 완전 제거됨을 README/PR에 명시.

- [ ] **Step 3: Commit (변경 있으면)**

```bash
git add -A
git commit -m "chore(webauthn): 전체 빌드 green — 주요 attestation 포맷 자체 구현 완료"
```

---

## Self-Review (작성자 점검 결과)

**1. Spec coverage:**
- §2 범위(등록/인증 코어, 주요 포맷 전부, JDK 암호, differential+vector, :webauthn, 접근법 A) → Phase 0~6 전부 매핑 ✅
- §5 아키텍처(모듈 격리 + 인터페이스) → Task 0, 7, 14, 15 ✅
- §6 인터페이스 계약(Input/Result/Reason) → Task 7 ✅
- §7 파이프라인(공통 빌딩블록 + 등록/인증 + 포맷별) → Task 1~6, 14~15, 9~11, 25~29 ✅
- §8 저장 스키마(cosePublicKey 개별 필드) → Task 19, 20 ✅
- §9 검증·전환(differential, vector, 빈 스위치, E2E) → Task 16~18, 21, 24, 25~29 ✅
- §10 작업 경계(앱 무변경 항목) → Task 21~23이 finish만 수정, start/컨트롤러/MDS 무변경 ✅
- §11 모듈 단위 정의 → 각 단위가 개별 Task ✅

**2. Placeholder scan:** "TBD/TODO/implement later" 없음. vector Task(25~29)의 `AttestationVectors.*()`는 "헬퍼를 누적 작성"으로 명시했고 생성 방법(webauthn4j-test 에뮬레이터 / FIDO conformance 샘플)을 적시함 — 추상 지시가 아니라 구체 산출물·경로(`webauthn/src/test/resources/vectors/<fmt>/`)를 지정. differential/픽스처 어댑터의 webauthn4j 메서드명 교정 NOTE는 테스트 소스셋 한정이며 프로덕션 코드와 무관.

**3. Type consistency:** `WebAuthnVerifier`(verifyRegistration/verifyAuthentication), `RegistrationInput/Result`·`AuthenticationInput/Result`·`StoredCredential`·`WebAuthnVerificationException.Reason`, `AttestationVerifier.verify(authData, rawAuthData, attStmt, clientDataHash)`, `AttestationResult.Type`, `CoseAlgorithm.jcaSignatureName()`, `Credential.getCosePublicKey()` — Task 간 시그니처 일관 확인 ✅

**4. 주의(구현자):** webauthn4j 0.31.5의 일부 메서드명(`isFlagUV`, `getCOSEKey`, `getCborConverter`, `extractAuthenticatorData` 등)은 버전 차이로 컴파일 에러 가능 → 해당 Task의 NOTE대로 IDE 자동완성으로 동등 메서드 교정. 이는 어댑터/픽스처(테스트 소스셋) 한정이며 프로덕션 코드와 무관.
