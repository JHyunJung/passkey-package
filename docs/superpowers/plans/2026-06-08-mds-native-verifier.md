# MDS 자체 구현 (webauthn4j-metadata 제거) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** MDS3 BLOB 다운로드·JWS 검증·파싱을 webauthn4j-metadata 없이 자체 구현해 `core`의 `api(webauthn4j-metadata)`를 제거하고, 프로덕션 런타임에서 webauthn4j를 완전히 제거한다.

**Architecture:** 접근법 A(어댑터 + 모듈 격리). `:webauthn` 모듈에 `mds` 패키지를 추가해 네트워크 무의존 검증·파싱(`MetadataBlobVerifier` 인터페이스 + `NativeMetadataBlobVerifier`)을 둔다. HTTP 다운로드는 admin-app `MdsBlobClient`가 담당하고 `MetadataBlobVerifier`를 호출한다. JWS 서명·X.509 체인 검증은 JDK `java.security`로 자체 구현하며 기존 `CertPathVerifier`·`JwsEcdsa`를 재사용한다. 검증은 webauthn4j `MetadataBLOBFactory` differential + 자체 서명 BLOB 테스트로 한다.

**Tech Stack:** Java 17, JDK `java.security`(Signature/CertPathValidator), Jackson(payload JSON), `java.net.http.HttpClient`(admin-app 다운로드), JUnit 5, BouncyCastle(테스트 BLOB 서명), webauthn4j-metadata 0.31.5(differential 테스트만).

**Spec:** `docs/superpowers/specs/2026-06-08-mds-native-verifier-design.md`

---

## 파일 구조

**새 파일 — `:webauthn` mds 패키지** (`webauthn/src/main/java/com/crosscert/passkey/webauthn/mds/`):

| 파일 | 책임 |
|------|------|
| `MdsException.java` | 검증 실패 (Reason enum: MALFORMED_JWS/BAD_SIGNATURE/UNTRUSTED_CHAIN/MALFORMED_PAYLOAD) |
| `MdsBlob.java` | POJO record(no, nextUpdate, entries) |
| `MdsBlobEntry.java` | POJO record(aaguid nullable, statusReports) |
| `MdsStatusReport.java` | POJO record(status 원문 String, effectiveDate) |
| `MdsJws.java` | JWS compact 파싱 (header/payload64/signature/x5c/alg) |
| `MdsJwsVerifier.java` | JWS 서명검증(JDK Signature) + x5c 체인검증(CertPathVerifier) |
| `MdsBlobParser.java` | payload JSON → MdsBlob (Jackson) |
| `MetadataBlobVerifier.java` | 인터페이스: `MdsBlob verify(String rawJwt, Set<TrustAnchor>)` |
| `NativeMetadataBlobVerifier.java` | 자체 구현 오케스트레이션 |

**테스트 — `:webauthn`** (`webauthn/src/test/java/com/crosscert/passkey/webauthn/mds/`):
- 빌딩블록 단위테스트, `MdsTestBlob.java`(BouncyCastle 테스트 BLOB 빌더), `Webauthn4jMetadataBlobVerifier.java`(differential, testImplementation), differential 테스트.

**수정 — admin-app**:
| 파일 | 변경 |
|------|------|
| `admin-app/build.gradle.kts` | `implementation(project(":webauthn"))` 추가 |
| `admin-app/.../mds/MdsRootCertProvider.java` | `Set<TrustAnchor> anchors()` 추가 |
| `admin-app/.../mds/MdsBlobClient.java` | HTTP 다운로드 + MetadataBlobVerifier 호출, `FetchResult(rawJwt, MdsBlob)` 반환. webauthn4j 제거 |
| `admin-app/.../mds/MdsBlobStore.java` | `store(String rawJwt, MdsBlob blob)` 시그니처 자체 POJO로 |
| `admin-app/.../mds/MdsSchedulerService.java` | `MdsBlob`·`FetchResult` 소비, 원본 rawJwt 저장 |
| `admin-app/.../mds/MdsBlobClientTest.java` | 자체 인터페이스 mock으로 전환 |
| `admin-app/.../MdsSchedulerIT.java` | fake blob을 자체 `MdsBlob`로 |

**수정 — core**:
| 파일 | 변경 |
|------|------|
| `core/build.gradle.kts` | `api(webauthn4j-metadata)` 제거 |

**무변경**: passkey-app `MdsVerifier`·`MdsAaguidCache`(Redis CSV 소비), DB 스키마, Tenant mdsRequired, 스케줄러 leader election, `MdsSyncJob`.

---

## Phase 1 — `:webauthn` mds 패키지: POJO + 예외

### Task 1: MdsException + POJO 타입

**Files:**
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/mds/MdsException.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/mds/MdsBlob.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/mds/MdsBlobEntry.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/mds/MdsStatusReport.java`

- [ ] **Step 1: MdsException 작성**

`mds/MdsException.java`:

```java
package com.crosscert.passkey.webauthn.mds;

/** MDS3 BLOB 검증·파싱 실패. */
public final class MdsException extends Exception {

    public enum Reason { MALFORMED_JWS, BAD_SIGNATURE, UNTRUSTED_CHAIN, MALFORMED_PAYLOAD }

    private final Reason reason;

    public MdsException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public MdsException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
```

- [ ] **Step 2: POJO record 3개 작성**

`mds/MdsStatusReport.java`:

```java
package com.crosscert.passkey.webauthn.mds;

import java.time.LocalDate;

/**
 * MDS3 status report (FIDO MDS3 §3.1.3). status는 MDS3 원문 토큰을
 * 그대로 보존한다 — enum 강제 안 함(미지 값도 거부하지 않아 MDS3 진화에 견고).
 */
public record MdsStatusReport(String status, LocalDate effectiveDate) {}
```

`mds/MdsBlobEntry.java`:

```java
package com.crosscert.passkey.webauthn.mds;

import java.util.List;

/** MDS3 BLOB entry (§3.1.1). aaguid는 null 가능(legacy U2F → 소비처가 스킵). */
public record MdsBlobEntry(byte[] aaguid, List<MdsStatusReport> statusReports) {}
```

`mds/MdsBlob.java`:

```java
package com.crosscert.passkey.webauthn.mds;

import java.time.LocalDate;
import java.util.List;

/** MDS3 BLOB payload (§3.1.6). no=버전, nextUpdate=다음 갱신일, entries=authenticator 목록. */
public record MdsBlob(int no, LocalDate nextUpdate, List<MdsBlobEntry> entries) {}
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew :webauthn:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/mds/
git commit -m "feat(webauthn): MDS POJO 타입 + MdsException"
```

### Task 2: MdsJws — JWS compact 파싱

**Files:**
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/mds/MdsJws.java`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/mds/MdsJwsTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`mds/MdsJwsTest.java`:

```java
package com.crosscert.passkey.webauthn.mds;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class MdsJwsTest {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    @Test
    void parsesHeaderPayloadSignatureAndX5c() throws Exception {
        // header: {"alg":"RS256","x5c":["AQ"]}  (x5c entry는 base64 STANDARD DER, 여기선 더미 1바이트)
        String header = "{\"alg\":\"RS256\",\"x5c\":[\"" + Base64.getEncoder().encodeToString(new byte[]{1}) + "\"]}";
        String payload = "{\"no\":1}";
        String h64 = B64.encodeToString(header.getBytes(StandardCharsets.UTF_8));
        String p64 = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String sig = B64.encodeToString(new byte[]{9, 9});
        String jws = h64 + "." + p64 + "." + sig;

        MdsJws parsed = MdsJws.parse(jws);

        assertEquals("RS256", parsed.alg());
        assertEquals(1, parsed.x5c().size());
        assertArrayEquals(new byte[]{1}, parsed.x5c().get(0));
        assertArrayEquals(new byte[]{9, 9}, parsed.signature());
        // signingInput = h64 + "." + p64 (ASCII)
        assertArrayEquals((h64 + "." + p64).getBytes(StandardCharsets.US_ASCII), parsed.signingInput());
        assertArrayEquals(payload.getBytes(StandardCharsets.UTF_8), parsed.payloadBytes());
    }

    @Test
    void rejectsNotThreeParts() {
        assertThrows(MdsException.class, () -> MdsJws.parse("aaa.bbb"));
    }

    @Test
    void rejectsPaddedParts() {
        // '=' 패딩 포함 → compact JWS 위반
        assertThrows(MdsException.class, () -> MdsJws.parse("aa==.bb.cc"));
    }

    @Test
    void rejectsUnsupportedAlg() {
        String header = "{\"alg\":\"none\"}";
        String h64 = B64.encodeToString(header.getBytes(StandardCharsets.UTF_8));
        String jws = h64 + "." + B64.encodeToString("{}".getBytes()) + "." + B64.encodeToString(new byte[]{1});
        assertThrows(MdsException.class, () -> MdsJws.parse(jws));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :webauthn:test --tests '*MdsJwsTest'`
Expected: FAIL — `MdsJws` 없음

- [ ] **Step 3: MdsJws 구현**

`mds/MdsJws.java`:

```java
package com.crosscert.passkey.webauthn.mds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * JWS compact 직렬화(RFC 7515) 파싱: header.payload.signature.
 * MDS3 BLOB은 RS256/ES256으로 서명되며 header에 x5c(서명 인증서 체인)를 담는다.
 * 네트워크 무의존 — 순수 파싱.
 */
public final class MdsJws {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();
    private static final Base64.Decoder B64STD = Base64.getDecoder();

    private final String alg;
    private final List<byte[]> x5c;
    private final byte[] signingInput;
    private final byte[] payloadBytes;
    private final byte[] signature;

    private MdsJws(String alg, List<byte[]> x5c, byte[] signingInput,
                   byte[] payloadBytes, byte[] signature) {
        this.alg = alg;
        this.x5c = x5c;
        this.signingInput = signingInput;
        this.payloadBytes = payloadBytes;
        this.signature = signature;
    }

    public String alg() { return alg; }
    public List<byte[]> x5c() { return x5c; }
    public byte[] signingInput() { return signingInput.clone(); }
    public byte[] payloadBytes() { return payloadBytes.clone(); }
    public byte[] signature() { return signature.clone(); }

    public static MdsJws parse(String jws) throws MdsException {
        if (jws == null) throw new MdsException(MdsException.Reason.MALFORMED_JWS, "null JWS");
        String[] parts = jws.split("\\.", -1);
        if (parts.length != 3) {
            throw new MdsException(MdsException.Reason.MALFORMED_JWS, "JWS must have 3 parts");
        }
        for (String p : parts) {
            if (p.indexOf('=') >= 0) {
                throw new MdsException(MdsException.Reason.MALFORMED_JWS, "JWS part is base64url-padded");
            }
        }
        byte[] headerBytes, payloadBytes, signature;
        try {
            headerBytes = B64URL.decode(parts[0]);
            payloadBytes = B64URL.decode(parts[1]);
            signature = B64URL.decode(parts[2]);
        } catch (RuntimeException e) {
            throw new MdsException(MdsException.Reason.MALFORMED_JWS, "JWS part not base64url", e);
        }

        JsonNode header;
        try {
            header = MAPPER.readTree(headerBytes);
        } catch (Exception e) {
            throw new MdsException(MdsException.Reason.MALFORMED_JWS, "JWS header not JSON", e);
        }
        JsonNode algNode = header.get("alg");
        if (algNode == null || !algNode.isTextual()) {
            throw new MdsException(MdsException.Reason.MALFORMED_JWS, "JWS header missing alg");
        }
        String alg = algNode.asText();
        if (!"RS256".equals(alg) && !"ES256".equals(alg)) {
            throw new MdsException(MdsException.Reason.MALFORMED_JWS, "unsupported JWS alg: " + alg);
        }

        List<byte[]> x5c = new ArrayList<>();
        JsonNode x5cNode = header.get("x5c");
        if (x5cNode != null && x5cNode.isArray()) {
            for (JsonNode c : x5cNode) {
                if (!c.isTextual()) {
                    throw new MdsException(MdsException.Reason.MALFORMED_JWS, "x5c entry not text");
                }
                try {
                    x5c.add(B64STD.decode(c.asText()));
                } catch (RuntimeException e) {
                    throw new MdsException(MdsException.Reason.MALFORMED_JWS, "x5c entry not base64", e);
                }
            }
        }

        byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        return new MdsJws(alg, x5c, signingInput, payloadBytes, signature);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :webauthn:test --tests '*MdsJwsTest'`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/mds/MdsJws.java webauthn/src/test/java/com/crosscert/passkey/webauthn/mds/MdsJwsTest.java
git commit -m "feat(webauthn): MdsJws — JWS compact 파싱 (alg/x5c/signingInput)"
```

### Task 3: MdsJwsVerifier — 서명 + 체인 검증

**Files:**
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/mds/MdsJwsVerifier.java`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/mds/MdsJwsVerifierTest.java`

기존 자산 재사용:
- `com.crosscert.passkey.webauthn.trust.CertPathVerifier` — `boolean verify(List<X509Certificate> chain, Set<TrustAnchor> anchors)` (PKIX, leaf→root).
- `com.crosscert.passkey.webauthn.attestation.JwsEcdsa` — **package-private** `static byte[] toDer(byte[] raw)` (ES256 raw R‖S 64바이트 → DER). NOTE: JwsEcdsa는 `attestation` 패키지의 package-private 클래스다. mds 패키지에서 쓰려면 (a) JwsEcdsa를 public으로 바꾸거나, (b) mds에 동일 로직의 작은 helper를 둔다. 이 plan은 (a) JwsEcdsa를 `public`으로 승격하고 `toDer`도 `public static`으로 바꾼다(가장 DRY). Task 3 Step 0에서 수행.

- [ ] **Step 0: JwsEcdsa를 public으로 승격 (재사용 위해)**

`webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/JwsEcdsa.java`에서 클래스 선언과 `toDer`를 public으로:

```java
// final class JwsEcdsa  →  public final class JwsEcdsa
// static byte[] toDer(  →  public static byte[] toDer(
```

(기존 android-safetynet 사용처는 같은 패키지라 영향 없음. 컴파일만 확인: `./gradlew :webauthn:compileJava`.)

- [ ] **Step 1: 실패하는 테스트 작성 (자체 서명 BLOB)**

먼저 테스트 BLOB 빌더 헬퍼를 만든다. `mds/MdsTestBlob.java` (테스트 소스셋):

```java
package com.crosscert.passkey.webauthn.mds;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;

/**
 * 테스트용 MDS3 BLOB JWS 빌더. self-signed root CA로 leaf를 발급하고
 * leaf 개인키로 RS256 JWS를 서명한다. (네트워크·실 FIDO PKI 불필요.)
 */
public final class MdsTestBlob {

    public final X509Certificate root;     // trust anchor
    public final X509Certificate leaf;     // JWS 서명자
    public final String jws;               // header.payload.signature

    private MdsTestBlob(X509Certificate root, X509Certificate leaf, String jws) {
        this.root = root; this.leaf = leaf; this.jws = jws;
    }

    /** payloadJson을 RS256으로 서명한 BLOB JWS를 만든다. root가 leaf를 발급. */
    public static MdsTestBlob rs256(String payloadJson) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair rootKp = g.generateKeyPair();
        KeyPair leafKp = g.generateKeyPair();

        X509Certificate root = selfSigned(rootKp, "CN=mds-test-root", true);
        X509Certificate leaf = issued(rootKp, "CN=mds-test-root", leafKp, "CN=mds-signer", false);

        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = "{\"alg\":\"RS256\",\"x5c\":[\""
                + Base64.getEncoder().encodeToString(leaf.getEncoded()) + "\",\""
                + Base64.getEncoder().encodeToString(root.getEncoded()) + "\"]}";
        String h64 = b64.encodeToString(header.getBytes(StandardCharsets.UTF_8));
        String p64 = b64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(leafKp.getPrivate());
        s.update((h64 + "." + p64).getBytes(StandardCharsets.US_ASCII));
        String sig = b64.encodeToString(s.sign());
        return new MdsTestBlob(root, leaf, h64 + "." + p64 + "." + sig);
    }

    private static X509Certificate selfSigned(KeyPair kp, String dn, boolean ca) throws Exception {
        return build(kp, dn, kp, dn, ca);
    }
    private static X509Certificate issued(KeyPair issuerKp, String issuerDn,
                                          KeyPair subjectKp, String subjectDn, boolean ca) throws Exception {
        return build(issuerKp, issuerDn, subjectKp, subjectDn, ca);
    }
    private static X509Certificate build(KeyPair issuerKp, String issuerDn,
                                         KeyPair subjectKp, String subjectDn, boolean ca) throws Exception {
        var builder = new JcaX509v3CertificateBuilder(
                new X500Name(issuerDn), BigInteger.valueOf(ca ? 1 : 2),
                new Date(System.currentTimeMillis() - 86400_000L),
                new Date(System.currentTimeMillis() + 86400_000L),
                new X500Name(subjectDn), subjectKp.getPublic());
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints, true,
                new org.bouncycastle.asn1.x509.BasicConstraints(ca));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(issuerKp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private MdsTestBlob() { throw new AssertionError(); }
}
```

`mds/MdsJwsVerifierTest.java`:

```java
package com.crosscert.passkey.webauthn.mds;

import org.junit.jupiter.api.Test;

import java.security.cert.TrustAnchor;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MdsJwsVerifierTest {

    private final MdsJwsVerifier verifier = new MdsJwsVerifier();

    @Test
    void verifiesValidSignedBlobToTrustedRoot() throws Exception {
        MdsTestBlob blob = MdsTestBlob.rs256("{\"no\":7}");
        MdsJws jws = MdsJws.parse(blob.jws);
        Set<TrustAnchor> anchors = Set.of(new TrustAnchor(blob.root, null));
        // 통과 시 예외 없음
        assertDoesNotThrow(() -> verifier.verify(jws, anchors));
    }

    @Test
    void rejectsUntrustedRoot() throws Exception {
        MdsTestBlob blob = MdsTestBlob.rs256("{\"no\":7}");
        MdsTestBlob other = MdsTestBlob.rs256("{\"no\":7}");
        MdsJws jws = MdsJws.parse(blob.jws);
        Set<TrustAnchor> wrongAnchors = Set.of(new TrustAnchor(other.root, null));
        MdsException ex = assertThrows(MdsException.class, () -> verifier.verify(jws, wrongAnchors));
        assertEquals(MdsException.Reason.UNTRUSTED_CHAIN, ex.reason());
    }

    @Test
    void rejectsTamperedSignature() throws Exception {
        MdsTestBlob blob = MdsTestBlob.rs256("{\"no\":7}");
        // 서명 1바이트 flip: payload를 바꿔 signingInput 불일치 유발
        String tampered = blob.jws.substring(0, blob.jws.lastIndexOf('.'))
                .replaceFirst("\\.", ".X") + blob.jws.substring(blob.jws.lastIndexOf('.'));
        // 더 안전한 변조: 서명 부분 마지막 char 교체
        String[] parts = blob.jws.split("\\.");
        char last = parts[2].charAt(parts[2].length() - 1);
        parts[2] = parts[2].substring(0, parts[2].length() - 1) + (last == 'A' ? 'B' : 'A');
        MdsJws jws = MdsJws.parse(parts[0] + "." + parts[1] + "." + parts[2]);
        Set<TrustAnchor> anchors = Set.of(new TrustAnchor(blob.root, null));
        MdsException ex = assertThrows(MdsException.class, () -> verifier.verify(jws, anchors));
        assertEquals(MdsException.Reason.BAD_SIGNATURE, ex.reason());
    }

    @Test
    void rejectsEmptyX5c() throws Exception {
        // x5c 없는 header
        String header = "{\"alg\":\"RS256\"}";
        java.util.Base64.Encoder b64 = java.util.Base64.getUrlEncoder().withoutPadding();
        String h64 = b64.encodeToString(header.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String jwsStr = h64 + "." + b64.encodeToString("{}".getBytes()) + "." + b64.encodeToString(new byte[]{1});
        MdsJws jws = MdsJws.parse(jwsStr);
        MdsException ex = assertThrows(MdsException.class,
                () -> verifier.verify(jws, Set.of()));
        assertEquals(MdsException.Reason.MALFORMED_JWS, ex.reason());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :webauthn:test --tests '*MdsJwsVerifierTest'`
Expected: FAIL — `MdsJwsVerifier` 없음

- [ ] **Step 3: MdsJwsVerifier 구현**

`mds/MdsJwsVerifier.java`:

```java
package com.crosscert.passkey.webauthn.mds;

import com.crosscert.passkey.webauthn.attestation.JwsEcdsa;
import com.crosscert.passkey.webauthn.trust.CertPathVerifier;

import java.io.ByteArrayInputStream;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * MDS3 BLOB의 JWS 서명·X.509 체인 검증 (FIDO MDS3 §3.2).
 *  1) x5c[0]=leaf 공개키로 JWS 서명 검증 (signingInput = header64.payload64).
 *  2) x5c 체인을 trustAnchors(FIDO root)까지 PKIX 검증.
 */
public final class MdsJwsVerifier {

    private final CertPathVerifier certPathVerifier = new CertPathVerifier();

    public void verify(MdsJws jws, Set<TrustAnchor> trustAnchors) throws MdsException {
        if (jws.x5c().isEmpty()) {
            throw new MdsException(MdsException.Reason.MALFORMED_JWS, "JWS has no x5c");
        }
        List<X509Certificate> chain = parseChain(jws.x5c());
        X509Certificate leaf = chain.get(0);

        // 1) 서명 검증
        boolean ok = verifySignature(jws.alg(), leaf, jws.signingInput(), jws.signature());
        if (!ok) {
            throw new MdsException(MdsException.Reason.BAD_SIGNATURE, "JWS signature invalid");
        }

        // 2) 체인 검증 (PKIX, leaf → root)
        if (!certPathVerifier.verify(chain, trustAnchors)) {
            throw new MdsException(MdsException.Reason.UNTRUSTED_CHAIN, "x5c chain not trusted");
        }
    }

    private static boolean verifySignature(String alg, X509Certificate leaf,
                                           byte[] signingInput, byte[] signature) throws MdsException {
        String jca = switch (alg) {
            case "RS256" -> "SHA256withRSA";
            case "ES256" -> "SHA256withECDSA";
            default -> throw new MdsException(MdsException.Reason.MALFORMED_JWS, "unsupported alg: " + alg);
        };
        try {
            Signature s = Signature.getInstance(jca);
            s.initVerify(leaf.getPublicKey());
            s.update(signingInput);
            // ES256 JWS 서명은 raw R||S(64B) → JDK는 DER 요구.
            byte[] toVerify = "ES256".equals(alg) ? JwsEcdsa.toDer(signature) : signature;
            return s.verify(toVerify);
        } catch (MdsException e) {
            throw e;
        } catch (Exception e) {
            return false;
        }
    }

    private static List<X509Certificate> parseChain(List<byte[]> x5c) throws MdsException {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = new ArrayList<>();
            for (byte[] der : x5c) {
                chain.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der)));
            }
            return chain;
        } catch (Exception e) {
            throw new MdsException(MdsException.Reason.MALFORMED_JWS, "x5c cert parse failed", e);
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :webauthn:test --tests '*MdsJwsVerifierTest'`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/JwsEcdsa.java webauthn/src/main/java/com/crosscert/passkey/webauthn/mds/MdsJwsVerifier.java webauthn/src/test/java/com/crosscert/passkey/webauthn/mds/MdsJwsVerifierTest.java webauthn/src/test/java/com/crosscert/passkey/webauthn/mds/MdsTestBlob.java
git commit -m "feat(webauthn): MdsJwsVerifier — JWS 서명 + x5c 체인 검증 (CertPathVerifier 재사용)"
```

### Task 4: MdsBlobParser — payload JSON → MdsBlob

**Files:**
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/mds/MdsBlobParser.java`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/mds/MdsBlobParserTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`mds/MdsBlobParserTest.java`:

```java
package com.crosscert.passkey.webauthn.mds;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class MdsBlobParserTest {

    @Test
    void parsesNoNextUpdateAndEntries() throws Exception {
        String json = "{"
                + "\"no\":42,"
                + "\"nextUpdate\":\"2026-07-01\","
                + "\"entries\":["
                + "  {\"aaguid\":\"00112233-4455-6677-8899-aabbccddeeff\","
                + "   \"statusReports\":[{\"status\":\"FIDO_CERTIFIED_L1\",\"effectiveDate\":\"2020-01-01\"},"
                + "                      {\"status\":\"REVOKED\"}]},"
                + "  {\"statusReports\":[{\"status\":\"NOT_FIDO_CERTIFIED\"}]}"  // aaguid 없음(legacy)
                + "]}";

        MdsBlob blob = MdsBlobParser.parse(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(42, blob.no());
        assertEquals(LocalDate.of(2026, 7, 1), blob.nextUpdate());
        assertEquals(2, blob.entries().size());

        MdsBlobEntry e0 = blob.entries().get(0);
        assertArrayEquals(HexFormat.of().parseHex("00112233445566778899aabbccddeeff"), e0.aaguid());
        assertEquals(2, e0.statusReports().size());
        assertEquals("FIDO_CERTIFIED_L1", e0.statusReports().get(0).status());
        assertEquals(LocalDate.of(2020, 1, 1), e0.statusReports().get(0).effectiveDate());
        assertEquals("REVOKED", e0.statusReports().get(1).status());
        assertNull(e0.statusReports().get(1).effectiveDate());

        MdsBlobEntry e1 = blob.entries().get(1);
        assertNull(e1.aaguid()); // legacy U2F
        assertEquals("NOT_FIDO_CERTIFIED", e1.statusReports().get(0).status());
    }

    @Test
    void preservesUnknownStatusToken() throws Exception {
        String json = "{\"no\":1,\"nextUpdate\":\"2026-01-01\",\"entries\":["
                + "{\"aaguid\":\"00000000-0000-0000-0000-000000000001\","
                + "\"statusReports\":[{\"status\":\"SOME_FUTURE_STATUS_X\"}]}]}";
        MdsBlob blob = MdsBlobParser.parse(json.getBytes());
        assertEquals("SOME_FUTURE_STATUS_X", blob.entries().get(0).statusReports().get(0).status());
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(MdsException.class, () -> MdsBlobParser.parse("not-json".getBytes()));
    }

    @Test
    void rejectsMissingNo() {
        assertThrows(MdsException.class,
                () -> MdsBlobParser.parse("{\"nextUpdate\":\"2026-01-01\",\"entries\":[]}".getBytes()));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :webauthn:test --tests '*MdsBlobParserTest'`
Expected: FAIL — `MdsBlobParser` 없음

- [ ] **Step 3: MdsBlobParser 구현**

`mds/MdsBlobParser.java`:

```java
package com.crosscert.passkey.webauthn.mds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MDS3 BLOB payload JSON → MdsBlob (FIDO MDS3 §3.1.6).
 * 소비처가 쓰는 필드만 파싱: no, nextUpdate, entries[].aaguid, entries[].statusReports[].
 * status는 원문 문자열 보존(미지 토큰도 거부 안 함).
 */
public final class MdsBlobParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MdsBlobParser() {}

    public static MdsBlob parse(byte[] payloadJson) throws MdsException {
        JsonNode root;
        try {
            root = MAPPER.readTree(payloadJson);
        } catch (Exception e) {
            throw new MdsException(MdsException.Reason.MALFORMED_PAYLOAD, "payload not JSON", e);
        }
        JsonNode noNode = root.get("no");
        if (noNode == null || !noNode.isInt()) {
            throw new MdsException(MdsException.Reason.MALFORMED_PAYLOAD, "payload missing int 'no'");
        }
        int no = noNode.asInt();
        LocalDate nextUpdate = parseDate(root.get("nextUpdate"), "nextUpdate", true);

        List<MdsBlobEntry> entries = new ArrayList<>();
        JsonNode entriesNode = root.get("entries");
        if (entriesNode != null && entriesNode.isArray()) {
            for (JsonNode entryNode : entriesNode) {
                entries.add(parseEntry(entryNode));
            }
        }
        return new MdsBlob(no, nextUpdate, entries);
    }

    private static MdsBlobEntry parseEntry(JsonNode entryNode) throws MdsException {
        byte[] aaguid = null;
        JsonNode aaguidNode = entryNode.get("aaguid");
        if (aaguidNode != null && aaguidNode.isTextual() && !aaguidNode.asText().isBlank()) {
            aaguid = uuidToBytes(aaguidNode.asText());
        }
        List<MdsStatusReport> reports = new ArrayList<>();
        JsonNode srNode = entryNode.get("statusReports");
        if (srNode != null && srNode.isArray()) {
            for (JsonNode r : srNode) {
                JsonNode statusNode = r.get("status");
                if (statusNode == null || !statusNode.isTextual()) {
                    throw new MdsException(MdsException.Reason.MALFORMED_PAYLOAD,
                            "statusReport missing status");
                }
                LocalDate eff = parseDate(r.get("effectiveDate"), "effectiveDate", false);
                reports.add(new MdsStatusReport(statusNode.asText(), eff));
            }
        }
        return new MdsBlobEntry(aaguid, reports);
    }

    /** "00112233-4455-6677-8899-aabbccddeeff" → 16바이트. */
    private static byte[] uuidToBytes(String s) throws MdsException {
        try {
            UUID u = UUID.fromString(s);
            byte[] out = new byte[16];
            long hi = u.getMostSignificantBits(), lo = u.getLeastSignificantBits();
            for (int i = 0; i < 8; i++) out[i] = (byte) (hi >>> (8 * (7 - i)));
            for (int i = 0; i < 8; i++) out[8 + i] = (byte) (lo >>> (8 * (7 - i)));
            return out;
        } catch (IllegalArgumentException e) {
            throw new MdsException(MdsException.Reason.MALFORMED_PAYLOAD, "aaguid not a UUID: " + s, e);
        }
    }

    private static LocalDate parseDate(JsonNode node, String field, boolean required) throws MdsException {
        if (node == null || node.isNull() || !node.isTextual()) {
            if (required) {
                throw new MdsException(MdsException.Reason.MALFORMED_PAYLOAD, "payload missing " + field);
            }
            return null;
        }
        try {
            return LocalDate.parse(node.asText());
        } catch (DateTimeParseException e) {
            throw new MdsException(MdsException.Reason.MALFORMED_PAYLOAD, field + " not a date: " + node.asText(), e);
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :webauthn:test --tests '*MdsBlobParserTest'`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/mds/MdsBlobParser.java webauthn/src/test/java/com/crosscert/passkey/webauthn/mds/MdsBlobParserTest.java
git commit -m "feat(webauthn): MdsBlobParser — payload JSON → MdsBlob (status 원문 보존)"
```

### Task 5: MetadataBlobVerifier 인터페이스 + NativeMetadataBlobVerifier

**Files:**
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/mds/MetadataBlobVerifier.java`
- Create: `webauthn/src/main/java/com/crosscert/passkey/webauthn/mds/NativeMetadataBlobVerifier.java`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/mds/NativeMetadataBlobVerifierTest.java`

- [ ] **Step 1: 인터페이스 작성**

`mds/MetadataBlobVerifier.java`:

```java
package com.crosscert.passkey.webauthn.mds;

import java.security.cert.TrustAnchor;
import java.util.Set;

/**
 * MDS3 BLOB JWT를 검증·파싱하는 진입점. 네트워크 접근 없음 — rawJwt는
 * 호출자(admin-app)가 다운로드해 넘긴다. 구현체: NativeMetadataBlobVerifier(프로덕션),
 * Webauthn4jMetadataBlobVerifier(differential 테스트 전용).
 */
public interface MetadataBlobVerifier {
    MdsBlob verify(String rawJwt, Set<TrustAnchor> trustAnchors) throws MdsException;
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`mds/NativeMetadataBlobVerifierTest.java`:

```java
package com.crosscert.passkey.webauthn.mds;

import org.junit.jupiter.api.Test;

import java.security.cert.TrustAnchor;
import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NativeMetadataBlobVerifierTest {

    private final MetadataBlobVerifier verifier = new NativeMetadataBlobVerifier();

    @Test
    void verifiesAndParsesSignedBlob() throws Exception {
        String payload = "{\"no\":5,\"nextUpdate\":\"2026-12-31\",\"entries\":["
                + "{\"aaguid\":\"00112233-4455-6677-8899-aabbccddeeff\","
                + "\"statusReports\":[{\"status\":\"FIDO_CERTIFIED_L1\"}]}]}";
        MdsTestBlob tb = MdsTestBlob.rs256(payload);
        Set<TrustAnchor> anchors = Set.of(new TrustAnchor(tb.root, null));

        MdsBlob blob = verifier.verify(tb.jws, anchors);

        assertEquals(5, blob.no());
        assertEquals(LocalDate.of(2026, 12, 31), blob.nextUpdate());
        assertEquals(1, blob.entries().size());
        assertEquals("FIDO_CERTIFIED_L1", blob.entries().get(0).statusReports().get(0).status());
    }

    @Test
    void rejectsUntrustedThenNeverParses() throws Exception {
        MdsTestBlob tb = MdsTestBlob.rs256("{\"no\":1,\"nextUpdate\":\"2026-01-01\",\"entries\":[]}");
        MdsTestBlob other = MdsTestBlob.rs256("{\"no\":1,\"nextUpdate\":\"2026-01-01\",\"entries\":[]}");
        MdsException ex = assertThrows(MdsException.class,
                () -> verifier.verify(tb.jws, Set.of(new TrustAnchor(other.root, null))));
        assertEquals(MdsException.Reason.UNTRUSTED_CHAIN, ex.reason());
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew :webauthn:test --tests '*NativeMetadataBlobVerifierTest'`
Expected: FAIL — `NativeMetadataBlobVerifier` 없음

- [ ] **Step 4: NativeMetadataBlobVerifier 구현**

`mds/NativeMetadataBlobVerifier.java`:

```java
package com.crosscert.passkey.webauthn.mds;

import java.security.cert.TrustAnchor;
import java.util.Set;

/**
 * 자체 구현 MDS3 BLOB verifier: JWS 파싱 → 서명·체인 검증 → payload 파싱.
 * 네트워크 무의존 — rawJwt는 호출자가 다운로드한다.
 */
public final class NativeMetadataBlobVerifier implements MetadataBlobVerifier {

    private final MdsJwsVerifier jwsVerifier = new MdsJwsVerifier();

    @Override
    public MdsBlob verify(String rawJwt, Set<TrustAnchor> trustAnchors) throws MdsException {
        MdsJws jws = MdsJws.parse(rawJwt);
        jwsVerifier.verify(jws, trustAnchors);   // 서명·체인 실패 시 throw (파싱 전에 중단)
        return MdsBlobParser.parse(jws.payloadBytes());
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :webauthn:test --tests '*NativeMetadataBlobVerifierTest'`
Expected: PASS (2 tests)

- [ ] **Step 6: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/mds/MetadataBlobVerifier.java webauthn/src/main/java/com/crosscert/passkey/webauthn/mds/NativeMetadataBlobVerifier.java webauthn/src/test/java/com/crosscert/passkey/webauthn/mds/NativeMetadataBlobVerifierTest.java
git commit -m "feat(webauthn): MetadataBlobVerifier + NativeMetadataBlobVerifier (검증→파싱 오케스트레이션)"
```

---

## Phase 3 — Differential (webauthn4j-metadata 대조)

### Task 6: Webauthn4jMetadataBlobVerifier 어댑터 + differential 테스트

**Files:**
- Create: `webauthn/src/test/java/com/crosscert/passkey/webauthn/mds/Webauthn4jMetadataBlobVerifier.java`
- Create: `webauthn/src/test/java/com/crosscert/passkey/webauthn/mds/MdsDifferentialTest.java`

webauthn4j는 `:webauthn`의 testImplementation에 이미 있다(webauthn4j-core). webauthn4j-metadata도 testImplementation으로 추가한다.

- [ ] **Step 1: webauthn4j-metadata를 :webauthn testImplementation에 추가**

`webauthn/build.gradle.kts`의 dependencies에 추가:

```kotlin
    testImplementation(rootProject.libs.webauthn4j.metadata)
```

- [ ] **Step 2: Webauthn4jMetadataBlobVerifier 어댑터 작성**

`Webauthn4jMetadataBlobVerifier.java` — webauthn4j `MetadataBLOBFactory`로 동일 rawJwt를 파싱해 자체 `MdsBlob`로 변환. **목적: 같은 rawJwt에서 no/nextUpdate/entries수/AAGUID/status가 자체 구현과 일치하는지 대조.**

```java
package com.crosscert.passkey.webauthn.mds;

import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.metadata.data.MetadataBLOB;
import com.webauthn4j.metadata.data.MetadataBLOBFactory;

import java.security.cert.TrustAnchor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * webauthn4j-metadata로 동일 rawJwt를 파싱하는 differential 대조 구현.
 * 테스트 전용. webauthn4j MetadataBLOBFactory는 로컬 rawJwt를 파싱한다.
 *
 * NOTE: webauthn4j 0.31.5의 정확한 MetadataBLOBFactory 생성/메서드 시그니처는
 * IDE/소스로 확인해 교정하라. 핵심은 "같은 JWT를 webauthn4j로 파싱해
 * no/nextUpdate/entries/AAGUID/status를 자체 MdsBlob 형태로 추출"하는 것이다.
 * MetadataBLOBFactory가 서명검증을 강제하면 trustAnchors를 넘기고, 아니면
 * 파싱만 수행한다(서명검증 정확성은 NativeMetadataBlobVerifier 테스트가 담당).
 */
public final class Webauthn4jMetadataBlobVerifier {

    private final MetadataBLOBFactory factory = new MetadataBLOBFactory(new ObjectConverter());

    /** webauthn4j 파싱 결과를 자체 MdsBlob로 변환(필드 대조용). */
    public MdsBlob parse(String rawJwt) {
        MetadataBLOB blob = factory.parse(rawJwt);   // ← 0.31.5 정확한 메서드명 확인(parse/of 등)
        var payload = blob.getPayload();
        List<MdsBlobEntry> entries = new ArrayList<>();
        for (var e : payload.getEntries()) {
            byte[] aaguid = e.getAaguid() == null ? null : e.getAaguid().getBytes();
            List<MdsStatusReport> reports = new ArrayList<>();
            for (var sr : e.getStatusReports()) {
                String status = sr.getStatus() == null ? null : sr.getStatus().getValue();
                reports.add(new MdsStatusReport(status, sr.getEffectiveDate()));
            }
            entries.add(new MdsBlobEntry(aaguid, reports));
        }
        return new MdsBlob(payload.getNo(), payload.getNextUpdate(), entries);
    }
}
```

> **NOTE (구현자):** webauthn4j 0.31.5의 `MetadataBLOBFactory` API(생성자 인자, `parse`/`of` 메서드명, AAGUID.getBytes()/getValue() 등)를 실제 jar/소스로 확인해 교정하라. (`webauthn/src/test/java/.../diff/Webauthn4jVerifier.java`가 이미 webauthn4j 0.31.5 API를 쓰므로 그 패턴 참고.) 만약 `MetadataBLOBFactory.parse(String)`가 서명검증을 내부에서 하고 실 FIDO root를 요구해 테스트 BLOB(자체 root)을 거부하면, 서명검증을 끄는 방법을 찾거나(불가 시) differential을 **파싱 필드 대조만** 수행하도록 하고 그 한계를 테스트 주석에 명시하라. 자체 서명 검증 정확성은 Task 3·5가 이미 보장한다.

- [ ] **Step 3: differential 테스트 작성**

`MdsDifferentialTest.java`:

```java
package com.crosscert.passkey.webauthn.mds;

import org.junit.jupiter.api.Test;

import java.security.cert.TrustAnchor;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 동일 rawJwt를 자체 NativeMetadataBlobVerifier와 webauthn4j MetadataBLOBFactory에
 * 모두 넣어 파싱 결과(no/nextUpdate/entries/AAGUID/status)가 일치하는지 대조.
 */
class MdsDifferentialTest {

    private final NativeMetadataBlobVerifier nativeVerifier = new NativeMetadataBlobVerifier();
    private final Webauthn4jMetadataBlobVerifier w4j = new Webauthn4jMetadataBlobVerifier();

    @Test
    void parsingAgreesAcrossImplementations() throws Exception {
        String payload = "{\"no\":11,\"nextUpdate\":\"2026-09-15\",\"entries\":["
                + "{\"aaguid\":\"00112233-4455-6677-8899-aabbccddeeff\","
                + "\"statusReports\":[{\"status\":\"FIDO_CERTIFIED_L1\"},{\"status\":\"REVOKED\"}]}]}";
        MdsTestBlob tb = MdsTestBlob.rs256(payload);
        Set<TrustAnchor> anchors = Set.of(new TrustAnchor(tb.root, null));

        MdsBlob nativeBlob = nativeVerifier.verify(tb.jws, anchors);
        MdsBlob w4jBlob = w4j.parse(tb.jws);

        // 베이스라인 non-trivial 보장
        assertEquals(11, w4jBlob.no());
        assertEquals(1, w4jBlob.entries().size());

        assertEquals(w4jBlob.no(), nativeBlob.no());
        assertEquals(w4jBlob.nextUpdate(), nativeBlob.nextUpdate());
        assertEquals(w4jBlob.entries().size(), nativeBlob.entries().size());
        assertArrayEquals(w4jBlob.entries().get(0).aaguid(), nativeBlob.entries().get(0).aaguid());
        // status 목록 일치 (원문 문자열)
        var nativeStatuses = nativeBlob.entries().get(0).statusReports().stream().map(MdsStatusReport::status).toList();
        var w4jStatuses = w4jBlob.entries().get(0).statusReports().stream().map(MdsStatusReport::status).toList();
        assertEquals(w4jStatuses, nativeStatuses);
    }
}
```

> **NOTE (구현자):** webauthn4j `MetadataBLOBFactory.parse`가 자체 테스트 root로 서명된 BLOB의 서명을 거부하면(실 FIDO root 강제), 이 테스트는 webauthn4j가 만든 vector(또는 실제 FIDO BLOB 스냅샷, Task 7)로 양쪽 파싱을 대조하도록 조정하라. 핵심 불변식: "동일 입력 JWT → 동일 파싱 필드". 자체 구현이 webauthn4j와 파싱 결과가 어긋나면 그건 실 발견이니 보고(파서를 임의로 맞추지 말 것).

- [ ] **Step 4: 테스트 실행 + API 교정**

Run: `./gradlew :webauthn:test --tests '*MdsDifferentialTest'`
Expected: 처음엔 webauthn4j MetadataBLOBFactory API 불일치로 컴파일/런타임 오류 가능 → NOTE대로 실제 0.31.5 API로 교정. 서명검증 강제로 테스트 BLOB 거부 시 NOTE대로 폴백. 최종 PASS.

- [ ] **Step 5: 전체 webauthn 회귀**

Run: `./gradlew :webauthn:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add webauthn/build.gradle.kts webauthn/src/test/java/com/crosscert/passkey/webauthn/mds/Webauthn4jMetadataBlobVerifier.java webauthn/src/test/java/com/crosscert/passkey/webauthn/mds/MdsDifferentialTest.java
git commit -m "test(webauthn): MDS differential (webauthn4j MetadataBLOBFactory 파싱 대조)"
```

---

## Phase 4 — admin-app 통합 + webauthn4j-metadata 제거

> **목표:** admin-app MDS 코드를 자체 `MetadataBlobVerifier`로 전환하고, `core`의 `api(webauthn4j-metadata)`를 제거한다. Task 7~10은 **연속 실행** — Task 7이 인터페이스를 바꾸면 중간 상태로 admin-app이 컴파일 안 되고, Task 10까지 끝나야 다시 컴파일된다.

### Task 7: MdsRootCertProvider.anchors() + admin-app :webauthn 의존

**Files:**
- Modify: `admin-app/build.gradle.kts`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsRootCertProvider.java`

- [ ] **Step 1: admin-app build.gradle에 :webauthn 의존 추가**

`admin-app/build.gradle.kts`의 dependencies 블록, `implementation(project(":core"))` 아래에 추가:

```kotlin
    implementation(project(":webauthn"))
```

- [ ] **Step 2: MdsRootCertProvider에 anchors() 추가**

`MdsRootCertProvider.java`에 메서드 추가 (기존 `get()` 유지):

```java
    /** FIDO MDS3 root cert를 PKIX TrustAnchor 집합으로. MetadataBlobVerifier.verify에 주입. */
    public java.util.Set<java.security.cert.TrustAnchor> anchors() {
        return java.util.Set.of(new java.security.cert.TrustAnchor(root, null));
    }
```

- [ ] **Step 3: 컴파일 (admin-app은 아직 깨질 수 있음 — Task 10 후 복구)**

Run: `./gradlew :webauthn:compileJava`
Expected: BUILD SUCCESSFUL (webauthn 모듈은 영향 없음)

> Task 7~10 연속 실행이므로 여기서 커밋하지 않는다. Task 10 끝에서 묶어 커밋.

### Task 8: MdsBlobClient — HTTP 다운로드 + MetadataBlobVerifier

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsBlobClient.java`

- [ ] **Step 1: MdsBlobClient 재작성**

`MdsBlobClient.java` 전체를 다음으로 교체 (webauthn4j 제거, HttpClient 다운로드 + 자체 verifier):

```java
package com.crosscert.passkey.admin.mds;

import com.crosscert.passkey.webauthn.mds.MdsBlob;
import com.crosscert.passkey.webauthn.mds.MdsException;
import com.crosscert.passkey.webauthn.mds.MetadataBlobVerifier;
import com.crosscert.passkey.webauthn.mds.NativeMetadataBlobVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * MDS3 BLOB을 HTTPS로 다운로드하고 자체 {@link MetadataBlobVerifier}로
 * JWS 서명·X.509 체인을 검증한 뒤 파싱한다. webauthn4j 의존 없음.
 */
@Component
public class MdsBlobClient {

    private static final Logger log = LoggerFactory.getLogger(MdsBlobClient.class);

    private final HttpClient httpClient;
    private final MetadataBlobVerifier verifier;
    private final MdsRootCertProvider rootProvider;
    private final String endpoint;

    public MdsBlobClient(HttpClient httpClient,
                         MetadataBlobVerifier verifier,
                         MdsRootCertProvider rootProvider,
                         @Value("${passkey.mds.blob-endpoint:https://mds3.fidoalliance.org/}")
                         String endpoint) {
        this.httpClient = httpClient;
        this.verifier = verifier;
        this.rootProvider = rootProvider;
        this.endpoint = endpoint;
    }

    /** BLOB을 다운로드·검증·파싱한다. 원본 JWT와 파싱된 MdsBlob을 함께 반환. */
    public FetchResult fetch() {
        Instant started = Instant.now();
        String rawJwt;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("MDS fetch failed: HTTP " + resp.statusCode());
            }
            rawJwt = resp.body().trim();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            long durMs = Duration.between(started, Instant.now()).toMillis();
            log.error("mds blob fetch: transport error url={} durMs={} cause={}",
                    endpoint, durMs, e.toString(), e);
            throw new IllegalStateException("MDS fetch failed: " + e.getMessage(), e);
        }

        try {
            MdsBlob blob = verifier.verify(rawJwt, rootProvider.anchors());
            long durMs = Duration.between(started, Instant.now()).toMillis();
            int entries = blob.entries() == null ? 0 : blob.entries().size();
            log.info("mds blob fetch: url={} entries={} durMs={}", endpoint, entries, durMs);
            return new FetchResult(rawJwt, blob);
        } catch (MdsException e) {
            long durMs = Duration.between(started, Instant.now()).toMillis();
            if (e.reason() == MdsException.Reason.BAD_SIGNATURE
                    || e.reason() == MdsException.Reason.UNTRUSTED_CHAIN) {
                log.warn("mds blob fetch: signature/chain verify failed url={} durMs={} reason={} cause={}",
                        endpoint, durMs, e.reason(), e.toString());
            } else {
                log.error("mds blob fetch: parse error url={} durMs={} reason={} cause={}",
                        endpoint, durMs, e.reason(), e.toString(), e);
            }
            throw new IllegalStateException("MDS fetch failed: " + e.getMessage(), e);
        }
    }

    /** 다운로드한 원본 JWT + 검증·파싱된 BLOB. */
    public record FetchResult(String rawJwt, MdsBlob blob) {}

    @Configuration
    static class Wiring {
        @Bean
        public MetadataBlobVerifier metadataBlobVerifier() {
            return new NativeMetadataBlobVerifier();
        }

        @Bean
        public HttpClient mdsHttpClient() {
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        }
    }
}
```

> Task 7~10 연속 — 여기서 커밋하지 않는다.

### Task 9: MdsBlobStore + MdsSchedulerService — 자체 POJO 소비

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsBlobStore.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java`

- [ ] **Step 1: MdsBlobStore.store 시그니처 변경**

`MdsBlobStore.java`: import `com.webauthn4j.metadata.data.MetadataBLOB` → `com.crosscert.passkey.webauthn.mds.MdsBlob`. `store` 메서드 교체:

```java
    @Transactional
    public void store(String rawJwt, MdsBlob blob) {
        long version = blob.no();
        LocalDate nextUpdate = blob.nextUpdate();
        Instant now = clock.instant();
        int updated = jdbc.update(
                "UPDATE APP_OWNER.mds_blob_cache " +
                "SET version=?, next_update=?, fetched_at=?, blob_jwt=? " +
                "WHERE id=HEXTORAW('" + SINGLETON_HEX + "')",
                version,
                java.sql.Date.valueOf(nextUpdate),
                Timestamp.from(now),
                rawJwt);
        if (updated != 1) {
            throw new IncorrectUpdateSemanticsDataAccessException(
                    "mds_blob_cache sentinel row missing — V19 migration may not have run");
        }
    }
```

클래스 Javadoc의 "Phase 3 store passes an empty JSON" 문구를 "원본 BLOB JWT를 저장한다(감사·재검증용)"로 갱신.

- [ ] **Step 2: MdsSchedulerService 자체 POJO 소비**

`MdsSchedulerService.java`: import `com.webauthn4j.metadata.data.MetadataBLOB` 제거. `com.crosscert.passkey.admin.mds.MdsBlobClient.FetchResult` 사용.

`runOnce()` 내부 fetch~store~cache 블록을 다음으로 교체 (라인 90~133 영역):

```java
            MdsBlobClient.FetchResult fetched = client.fetch();
            com.crosscert.passkey.webauthn.mds.MdsBlob blob = fetched.blob();
            // 원본 BLOB JWT를 저장 — 감사·재검증 가능.
            store.store(fetched.rawJwt(), blob);

            // Invalidate AAGUID cache so passkey-app sees fresh data.
            Set<String> keys = redis.keys("mds:aaguid:*");
            int previousCount = keys == null ? 0 : keys.size();
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }

            // Populate per-AAGUID cache (mds:aaguid:<UUID> → CSV of status strings).
            // status는 MDS3 원문 문자열(MdsVerifier가 문자열 BLOCKING 목록으로 판정).
            int populated = 0;
            int skippedLegacy = 0;
            for (com.crosscert.passkey.webauthn.mds.MdsBlobEntry entry : blob.entries()) {
                if (entry.aaguid() == null) {
                    skippedLegacy++; // legacy U2F
                    continue;
                }
                String uuid = aaguidToUuid(entry.aaguid());
                String csv = entry.statusReports().stream()
                        .map(com.crosscert.passkey.webauthn.mds.MdsStatusReport::status)
                        .filter(s -> s != null && !s.isBlank())
                        .reduce((a, b) -> a + "," + b)
                        .orElse("");
                if (!csv.isBlank()) {
                    redis.opsForValue().set("mds:aaguid:" + uuid, csv,
                            java.time.Duration.ofHours(7));
                    populated++;
                }
            }
            log.info("mds sync: entries diff populated={} previousCacheSize={} skippedLegacy={}",
                    populated, previousCount, skippedLegacy);

            long version = blob.no();
```

그리고 16바이트 aaguid → UUID 문자열 헬퍼를 클래스에 추가 (Redis 키는 기존과 동일한 canonical UUID 문자열이어야 passkey-app MdsAaguidCache와 일치):

```java
    /** 16바이트 AAGUID → canonical UUID 문자열 (passkey-app MdsAaguidCache 키 형식과 일치). */
    private static String aaguidToUuid(byte[] aaguid) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(aaguid);
        long hi = bb.getLong();
        long lo = bb.getLong();
        return new java.util.UUID(hi, lo).toString();
    }
```

> **NOTE (구현자):** 기존 webauthn4j 경로는 `entry.getAaguid().getValue().toString()`로 UUID 문자열을 만들었다. `MdsAaguidCache.canonicalAaguid(byte[])`(passkey-app/core)가 같은 변환(빅엔디안 16바이트 → UUID)을 쓰는지 확인하고 정확히 일치시켜라 — Redis 키가 어긋나면 passkey-app이 캐시 미스로 fail-closed된다. core의 `MdsAaguidCache.canonicalAaguid` 구현을 읽어 동일 바이트 순서인지 검증.

> Task 7~10 연속 — 여기서 커밋하지 않는다.

### Task 10: core webauthn4j-metadata 제거 + admin-app 컴파일 복구 + 테스트 전환

**Files:**
- Modify: `core/build.gradle.kts`
- Modify: `admin-app/src/test/java/com/crosscert/passkey/admin/mds/MdsBlobClientTest.java`
- Modify: `admin-app/src/test/java/com/crosscert/passkey/admin/MdsSchedulerIT.java`

- [ ] **Step 1: core의 api(webauthn4j-metadata) 제거**

`core/build.gradle.kts`에서 `api(rootProject.libs.webauthn4j.metadata)` 줄 삭제. (`webauthn4j-core`는 유지 — core가 AAGUID 등에 쓸 수 있음. 단 confirm: core src/main에서 `com.webauthn4j.metadata` import가 0건이어야 함 → 조사 결과 admin-app만 썼으므로 안전.)

Run 확인:
```bash
grep -rn "com.webauthn4j.metadata" core/src/main 2>/dev/null && echo "REMAINING" || echo "0건 OK"
```

- [ ] **Step 2: MdsBlobClientTest 자체 인터페이스로 전환**

`MdsBlobClientTest.java` 전체 교체. webauthn4j mock 제거, `MdsBlobClient`가 이제 HttpClient+verifier 의존이므로 그에 맞게:

```java
package com.crosscert.passkey.admin.mds;

import com.crosscert.passkey.webauthn.mds.MdsBlob;
import com.crosscert.passkey.webauthn.mds.MetadataBlobVerifier;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MdsBlobClientTest {

    @Test
    void fetchDownloadsVerifiesAndReturnsBlob() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("the.raw.jwt");
        when(http.send(any(), any())).thenReturn(resp);

        MetadataBlobVerifier verifier = mock(MetadataBlobVerifier.class);
        MdsBlob blob = new MdsBlob(7, LocalDate.of(2026, 1, 1), List.of());
        when(verifier.verify(any(), any())).thenReturn(blob);

        MdsRootCertProvider rootProvider = mock(MdsRootCertProvider.class);
        when(rootProvider.anchors()).thenReturn(java.util.Set.of());

        MdsBlobClient client = new MdsBlobClient(http, verifier, rootProvider,
                "https://mds3.fidoalliance.org/");

        MdsBlobClient.FetchResult result = client.fetch();
        assertThat(result.rawJwt()).isEqualTo("the.raw.jwt");
        assertThat(result.blob().no()).isEqualTo(7);
    }

    @Test
    void fetchSurfacesHttpErrorAsIllegalState() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(503);
        when(http.send(any(), any())).thenReturn(resp);

        MdsBlobClient client = new MdsBlobClient(http,
                mock(MetadataBlobVerifier.class), mock(MdsRootCertProvider.class),
                "https://mds3.fidoalliance.org/");

        assertThatThrownBy(client::fetch)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MDS fetch failed");
    }

    @Test
    void fetchSurfacesVerifyFailureAsIllegalState() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn("bad.jwt");
        when(http.send(any(), any())).thenReturn(resp);

        MetadataBlobVerifier verifier = mock(MetadataBlobVerifier.class);
        when(verifier.verify(any(), any())).thenThrow(
                new com.crosscert.passkey.webauthn.mds.MdsException(
                        com.crosscert.passkey.webauthn.mds.MdsException.Reason.BAD_SIGNATURE, "bad sig"));
        MdsRootCertProvider rootProvider = mock(MdsRootCertProvider.class);
        when(rootProvider.anchors()).thenReturn(java.util.Set.of());

        MdsBlobClient client = new MdsBlobClient(http, verifier, rootProvider,
                "https://mds3.fidoalliance.org/");

        assertThatThrownBy(client::fetch)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MDS fetch failed");
    }
}
```

- [ ] **Step 3: MdsSchedulerIT fake blob을 자체 MdsBlob로 전환**

`MdsSchedulerIT.java`를 읽고, webauthn4j `MetadataBLOB`/`MetadataBLOBPayload`/`MetadataBLOBPayloadEntry`/`StatusReport`/`AuthenticatorStatus`로 fake blob을 만들던 헬퍼(`fakeBlob`/`entry`)를 자체 `MdsBlob`/`MdsBlobEntry`/`MdsStatusReport`로 교체. `MdsBlobClient`를 @MockBean으로 stub하면 `client.fetch()`가 `FetchResult(rawJwt, MdsBlob)`를 반환하도록:

```java
// 예 (실제 IT 구조에 맞춰 조정):
// when(mdsBlobClient.fetch()).thenReturn(
//     new MdsBlobClient.FetchResult("{}", new MdsBlob(version, nextUpdate, entries)));
// entries: List.of(new MdsBlobEntry(aaguidBytes, List.of(new MdsStatusReport("FIDO_CERTIFIED_L1", null))))
```

webauthn4j-metadata import를 전부 자체 mds 타입으로 교체. AAGUID는 16바이트 byte[]로 구성(기존 UUID → bytes). status는 원문 문자열.

> **NOTE (구현자):** MdsSchedulerIT 전체를 읽고(약 280줄) 기존 fakeBlob/entry 헬퍼 시그니처와 호출부를 자체 POJO로 정확히 옮겨라. `@MockBean MdsBlobClient`가 `FetchResult`를 반환하도록 stub을 바꾸는 게 핵심. Redis 키 검증(mds:aaguid:<uuid>)은 그대로 — aaguidToUuid가 같은 UUID 문자열을 만들면 통과.

- [ ] **Step 4: admin-app 컴파일 + 관련 테스트**

Run: `./gradlew :admin-app:compileJava :admin-app:compileTestJava`
Expected: BUILD SUCCESSFUL (Task 7~10 모두 반영되어 복구)

Run: `grep -rn "com.webauthn4j" admin-app/src/main 2>/dev/null && echo REMAINING || echo "admin src/main 0건 OK"`
Expected: admin src/main 0건 OK

- [ ] **Step 5: MdsSchedulerIT 실행 (Oracle Testcontainers — 무거움)**

Run: `./gradlew :admin-app:test --tests '*MdsSchedulerIT' --tests '*MdsBlobClientTest'`
Expected: PASS. Docker 없으면 NEEDS_CONTEXT로 보고하고 컴파일만 확인.

- [ ] **Step 6: Commit (Task 7~10 묶음)**

```bash
git add admin-app/build.gradle.kts core/build.gradle.kts \
        admin-app/src/main/java/com/crosscert/passkey/admin/mds/ \
        admin-app/src/test/java/com/crosscert/passkey/admin/mds/MdsBlobClientTest.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/MdsSchedulerIT.java
git commit -m "feat(admin): MDS를 자체 MetadataBlobVerifier로 전환 + core webauthn4j-metadata 제거"
```

### Task 11: 최종 통합 — webauthn4j 런타임 완전 제거 확인

- [ ] **Step 1: 전체 빌드**

Run: `./gradlew build -x test` (컴파일·패키징) 또는 `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: webauthn4j 런타임 의존 제거 확인**

```bash
./gradlew :admin-app:dependencies --configuration runtimeClasspath 2>/dev/null | grep -i webauthn4j || echo "admin-app 런타임 webauthn4j 0건"
./gradlew :passkey-app:dependencies --configuration runtimeClasspath 2>/dev/null | grep -i webauthn4j || echo "passkey-app 런타임 webauthn4j 0건"
```
Expected: 둘 다 0건. (core가 더 이상 webauthn4j-metadata를 api 노출 안 하고, webauthn4j-core도 core src/main에서 안 쓰면 제거 가능 — 단 core가 webauthn4j-core를 쓰는지 확인. 안 쓰면 그 줄도 제거하고 webauthn4j는 :webauthn/passkey-app의 testImplementation에만 남음.)

- [ ] **Step 3: src/main webauthn4j 전수 확인**

```bash
grep -rln "com.webauthn4j" core/src/main admin-app/src/main passkey-app/src/main rp-app/src/main webauthn/src/main 2>/dev/null || echo "전 모듈 src/main webauthn4j 0건 — 프로덕션 런타임 완전 제거"
```
Expected: 0건.

- [ ] **Step 4: Commit (변경 있으면)**

```bash
git add -A
git commit -m "chore: webauthn4j 프로덕션 런타임 완전 제거 확인 (테스트 에뮬레이터만 잔존)"
```

---

## Self-Review (작성자 점검)

**1. Spec coverage:**
- §2 범위(webauthn4j-metadata 제거, 실제 BLOB 다운로드, JDK 검증, :webauthn mds, blob_jwt 원본 저장, status 원문, differential+vector) → Task 1~11 매핑 ✅
- §4 아키텍처(MetadataBlobVerifier 경계, 다운로드/검증 분리) → Task 5, 8 ✅
- §5 계약·POJO(status 원문 보존) → Task 1 ✅
- §6 JWS 파이프라인(MdsJws/MdsJwsVerifier/MdsBlobParser, CertPathVerifier·JwsEcdsa 재사용) → Task 2~5 ✅
- §7 admin-app 통합(MdsBlobClient/Store/Scheduler/RootCertProvider) → Task 7~9 ✅
- §8 검증(differential, 자체서명 BLOB) → Task 3·6 ✅. 실제 FIDO BLOB vector는 Task 6 NOTE에서 폴백 경로로 다룸(자체서명+differential로 핵심 보장).
- §9 작업 경계(passkey-app 무변경, core api 제거) → Task 10 ✅

**2. Placeholder scan:** "TBD/TODO" 없음. webauthn4j MetadataBLOBFactory API 불확실성(Task 6)·MdsAaguidCache 키 일치(Task 9)·MdsSchedulerIT 전환(Task 10)은 구체 NOTE로 명시(추상 지시 아님 — 확인 대상·폴백·검증법 적시).

**3. Type consistency:** `MetadataBlobVerifier.verify(String, Set<TrustAnchor>) → MdsBlob`, `MdsBlob(int no, LocalDate nextUpdate, List<MdsBlobEntry>)`, `MdsBlobEntry(byte[] aaguid, List<MdsStatusReport>)`, `MdsStatusReport(String status, LocalDate)`, `MdsJws.parse/alg/x5c/signingInput/payloadBytes/signature`, `MdsException.Reason`, `MdsBlobClient.FetchResult(String rawJwt, MdsBlob blob)`, `MdsRootCertProvider.anchors()`, `MdsBlobStore.store(String, MdsBlob)` — Task 간 일관 확인 ✅

**4. 주의(구현자):** (a) webauthn4j 0.31.5 `MetadataBLOBFactory` 정확한 API는 실 jar로 교정(Task 6 NOTE). (b) `aaguidToUuid`가 core `MdsAaguidCache.canonicalAaguid`와 동일 바이트 순서여야 Redis 키 일치(Task 9 NOTE) — 어긋나면 passkey-app fail-closed. (c) MdsSchedulerIT가 MdsBlobClient를 @MockBean하는 구조면 FetchResult 반환으로 stub 교체.
