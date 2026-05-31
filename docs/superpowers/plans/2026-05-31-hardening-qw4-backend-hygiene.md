# QW-4 — 백엔드 순수 위생 (perf + quality) Implementation Plan

**REQUIRED SUB-SKILL: `superpowers:subagent-driven-development`** — this plan is executed task-by-task; each task is a self-contained unit (failing test → implement → green → invariant check → commit). Dispatch one subagent per task with the task's full text; do not batch unrelated tasks.

## Goal

Apply 11 audit findings (all `riskToBehaviorOrUI=none`) that are **pure-hygiene, output-byte-invariant** refactors across `core`, `passkey-app`, `admin-app`. Every change must provably preserve: the **audit hash-chain hex output**, the **signing-key wire format (kid/publicJwk/sealed bytes shape)**, the **problem+json wire responses (401/403/429 byte-for-byte)**, and time-dependent branch decisions. No behavior, response DTO, UI value, or persisted byte may change.

## Architecture

- **`core`**: `CryptoUtils.hex` perf swap (HexFormat); new `SigningKeyFactory` static factory for RSA keygen extraction; `Credential.recordAuthentication` signature simplification; `CredentialRepository` ORDER-BY/Sort de-duplication; `DevTenantHeaderFilter` TODO cleanup.
- **`passkey-app`**: `Webauthn4jConfig` gains an `ObjectConverter` bean + the three wrapper Converters reused as fields; `ProblemJson` helper in the `security` package; `Clock` injection into `ApiKeyAuthFilter`; `MeterRegistry` Counter caching (optional); `ApiKeyLookupService` touch-failure logging.
- **`admin-app`**: `KeyRotationService` delegates keygen to `SigningKeyFactory`; `TenantAdminService` gains `Clock` injection.

The single cross-module risk surface is the audit hash-chain (`CryptoUtils.hex`) and the signing-key format (`SigningKeyFactory`). Both are pinned by existing unit tests (`CryptoUtilsTest`, `SigningKeyProviderTest`, `KeyRotationServiceTest`) plus new assertions added here.

## Tech Stack

- Java 17 (toolchain 17), Spring Boot, Hibernate/Oracle, nimbus-jose-jwt, webauthn4j `0.31.5.RELEASE`, Micrometer, JUnit5 + Mockito + AssertJ.
- Clock bean: `core/.../config/CoreClockConfig.java` `clock()` (global single bean, `Clock.systemUTC()`), already available in `passkey-app` (FIDO finish services inject it) and `admin-app`.

## Build / test commands (run from worktree root)

```
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/hardening-quick-wins
./gradlew :core:compileJava :admin-app:compileJava :passkey-app:compileJava -q   # EXIT 0
./gradlew :core:test --tests 'com.crosscert.passkey.core.util.CryptoUtilsTest'
./gradlew :core:test --tests 'com.crosscert.passkey.core.jwt.SigningKeyProviderTest'
./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.keymgmt.KeyRotationServiceTest'
./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.tenant.TenantAdminServiceTest'
```

Oracle `*IT` are flaky in this env — run unit/slice tests only; record any env failures separately, do not treat them as regressions.

---

## Task 1 — `CryptoUtils.hex` → `HexFormat.of().formatHex` (finding: `perf-cryptoutils-hex-string-format`)

**Files**
- `core/src/main/java/com/crosscert/passkey/core/util/CryptoUtils.java`
- `core/src/test/java/com/crosscert/passkey/core/util/CryptoUtilsTest.java`

**Invariant at stake**: `hex()` output is the canonical input to the audit hash-chain (`AuditLogService.java:169` `CryptoUtils.hex(prevHash)`) and to `sha256Hex`/`randomToken`. Output must stay byte-identical lowercase hex with zero-padding.

**Steps**

1. (TDD — strengthen the pin first) In `CryptoUtilsTest.java`, add an explicit hash-chain-relevant regression case next to the existing `hexLowercasesAndPadsBytes` test. This asserts the full lowercase/padding contract over a 32-byte value (the SHA-256 size used by the chain):

```java
    @Test
    void hexMatchesHexFormatForAllByteValuesAndIsLowercasePadded() {
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) all[i] = (byte) i;
        String out = CryptoUtils.hex(all);
        assertThat(out).isEqualTo(java.util.HexFormat.of().formatHex(all));
        assertThat(out).hasSize(512);
        assertThat(out).isEqualTo(out.toLowerCase());
        assertThat(out).startsWith("000102");
        assertThat(out).endsWith("fdfeff");
    }
```

2. Run the test against the CURRENT (String.format) implementation to prove the expectation holds before the change:
   `./gradlew :core:test --tests 'com.crosscert.passkey.core.util.CryptoUtilsTest' -q` → expect EXIT 0 (the new test passes on the old impl too, since the output is identical — this is the invariant pin).

3. Add the import. In `CryptoUtils.java` after line 6 (`import java.security.SecureRandom;`) add:

```java
import java.util.HexFormat;
```

4. Replace the `hex` method body (lines 29–35) with:

```java
    /** byte[] 를 소문자 hex 문자열로. */
    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
```

   (`HexFormat.of()` is lowercase by default; `formatHex` zero-pads each byte to 2 chars — identical to the old loop.)

5. Re-run `./gradlew :core:test --tests 'com.crosscert.passkey.core.util.CryptoUtilsTest' -q` → EXIT 0 (all 6+1 tests green, including `sha256HexOfAbc`, `sha256HexOfEmptyString`, `randomTokenHasPrefixExpectedLengthAndIsUnique`, and the new pin).

6. **Invariant check (hash-chain)**: confirm no other caller path changes. Run:
   `./gradlew :core:compileJava :admin-app:compileJava :passkey-app:compileJava -q` → EXIT 0. The new test (step 1) is itself the proof that `hex()` output == `HexFormat` output for all 256 byte values, so `AuditLogService` chain input is unchanged.

7. Commit:
```
git add core/src/main/java/com/crosscert/passkey/core/util/CryptoUtils.java core/src/test/java/com/crosscert/passkey/core/util/CryptoUtilsTest.java
git commit -m "perf(core): CryptoUtils.hex → HexFormat.of().formatHex (output-invariant)

Replace per-byte String.format(\"%02x\") loop with JDK17 HexFormat.
Lowercase/padding identical → audit hash-chain hex unchanged.
Added all-256-byte-value regression pinning hex==HexFormat output.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2 — Extract RSA keygen into `core/jwt/SigningKeyFactory` (findings: `cq-rsa-keygen-dup`)

**Files**
- `core/src/main/java/com/crosscert/passkey/core/jwt/SigningKeyFactory.java` (new)
- `core/src/main/java/com/crosscert/passkey/core/jwt/SigningKeyProvider.java`
- `admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyRotationService.java`

**Invariant at stake**: the issued `SigningKey(kid, "RS256", publicJwk, sealed)` format — RSA-2048, `KeyUse.SIGNATURE`, `JWSAlgorithm.RS256`, thumbprint-derived `kid`, `publicJwk` = `toPublicJWK().toJSONString()`, `sealed` = `envelope.seal(private.getEncoded())`. This is a **pure function extraction**; the algorithm body is moved verbatim. Pinned by `SigningKeyProviderTest` (asserts kid/alg/use/private on the generate path) and `KeyRotationServiceTest`.

**Steps**

1. Create `core/src/main/java/com/crosscert/passkey/core/jwt/SigningKeyFactory.java` with the extracted body (identical to `SigningKeyProvider.generate()` lines 149–168, generalized exception message):

```java
package com.crosscert.passkey.core.jwt;

import com.crosscert.passkey.core.entity.SigningKey;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Single factory for fresh RS256 signing-key material. Extracted from
 * the byte-identical keygen previously duplicated in
 * {@code SigningKeyProvider.generate()} and
 * {@code KeyRotationService.generateFreshActive()}.
 *
 * <p>Output contract (must stay invariant — signing-key wire format):
 * RSA-2048, KeyUse.SIGNATURE, JWSAlgorithm.RS256, kid = RFC7638
 * thumbprint, publicJwk = toPublicJWK().toJSONString(), sealed =
 * envelope.seal(PKCS8 private bytes).
 */
public final class SigningKeyFactory {

    private SigningKeyFactory() {}

    public static SigningKey newRsaSigningKey(KeyEnvelope envelope) {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();

            RSAKey withoutKid = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
            String kid = withoutKid.computeThumbprint().toString();
            RSAKey rsa = new RSAKey.Builder(withoutKid).keyID(kid).build();
            String publicJwk = rsa.toPublicJWK().toJSONString();
            byte[] sealed = envelope.seal(pair.getPrivate().getEncoded());
            return new SigningKey(kid, "RS256", publicJwk, sealed);
        } catch (Exception e) {
            throw new IllegalStateException("signing key generation failed", e);
        }
    }
}
```

2. In `SigningKeyProvider.java`, replace the `generate()` method body (lines 149–168) with delegation:

```java
    private SigningKey generate() {
        return SigningKeyFactory.newRsaSigningKey(envelope);
    }
```

   (Keep the private `generate()` wrapper so `createInitialKey()`'s call site at line 122 is untouched.)

3. Remove now-unused imports from `SigningKeyProvider.java` **only if no longer referenced**. Before deleting, grep the file:
   `grep -n "KeyPairGenerator\|KeyPair\b\|RSAPublicKey\|RSAPrivateKey\|JWSAlgorithm\|KeyUse\b" core/src/main/java/com/crosscert/passkey/core/jwt/SigningKeyProvider.java`
   `RSAKey`, `KeyFactory`, `PKCS8EncodedKeySpec` are still used by `decodeWithPrivate` (below line 170) — keep those. Remove `KeyPairGenerator`, `KeyPair`, `RSAPublicKey`, `RSAPrivateKey`, `JWSAlgorithm`, `KeyUse` imports **only** if the grep shows zero remaining uses. If any remain (e.g. `RSAPublicKey`/`RSAPrivateKey` used in `decodeWithPrivate`), leave that import. Do not guess — act on grep output.

4. In `KeyRotationService.java`, replace `generateFreshActive()` (lines 144–161) call site. Change line 105 from:
```java
        SigningKey fresh = generateFreshActive();
```
   to:
```java
        SigningKey fresh = SigningKeyFactory.newRsaSigningKey(envelope);
```
   Then delete the entire `generateFreshActive()` private method (lines 144–161).

5. Add the import in `KeyRotationService.java` after line 10 (`import com.crosscert.passkey.core.jwt.SigningKeyProvider;`):
```java
import com.crosscert.passkey.core.jwt.SigningKeyFactory;
```
   Then remove now-unused imports `KeyPairGenerator`, `KeyPair`, `RSAPublicKey`, `RSAPrivateKey`, `JWSAlgorithm`, `KeyUse`, `RSAKey` from `KeyRotationService.java` — but first grep to confirm none are used elsewhere in the file:
   `grep -n "KeyPairGenerator\|KeyPair\b\|RSAPublicKey\|RSAPrivateKey\|JWSAlgorithm\|KeyUse\b\|RSAKey\b" admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyRotationService.java`
   Remove only imports with zero remaining hits.

6. Compile: `./gradlew :core:compileJava :admin-app:compileJava -q` → EXIT 0.

7. **Invariant check (signing-key format)**: run both pinning tests:
   `./gradlew :core:test --tests 'com.crosscert.passkey.core.jwt.SigningKeyProviderTest' -q` → EXIT 0 (`initGeneratesAndBootstrapsViaJdbcWhenNoActiveExists` exercises `generate()` → `SigningKeyFactory`; asserts kid/RS256/SIGNATURE/private).
   `./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.keymgmt.KeyRotationServiceTest' -q` → EXIT 0 (exercises rotation keygen). If `KeyRotationServiceTest` has no keygen-format assertion, that is acceptable — the `SigningKeyProviderTest` generate-path test plus identical extracted body proves format invariance; note in commit if so.

8. Commit:
```
git add core/src/main/java/com/crosscert/passkey/core/jwt/SigningKeyFactory.java core/src/main/java/com/crosscert/passkey/core/jwt/SigningKeyProvider.java admin-app/src/main/java/com/crosscert/passkey/admin/keymgmt/KeyRotationService.java
git commit -m "refactor(core,admin): extract RSA keygen to SigningKeyFactory

Single core/jwt factory newRsaSigningKey(KeyEnvelope) shared by
SigningKeyProvider.generate() and KeyRotationService rotation. Body
moved verbatim → issued key format (kid/RS256/publicJwk/sealed)
unchanged. Verified by SigningKeyProviderTest generate path.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3 — `ObjectConverter` + wrapper Converters as shared bean/fields (findings: `cq-objectconverter-field-init`, `perf-webauthn-converter-alloc`)

**Files**
- `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/Webauthn4jConfig.java`
- `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java`
- `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java`

**Invariant at stake**: serialized/deserialized credential-record envelope bytes (the `{ao,cd,ce,tr}` JSON and the CBOR/JSON inside). webauthn4j Converters are stateless wrappers over `ObjectConverter` (Jackson ObjectMapper for JSON+CBOR); reuse must produce identical bytes.

**THREAD-SAFETY GATE (do this Step FIRST — if it fails, STOP this task, leave the code unchanged, and report DONE_WITH_CONCERNS for findings `cq-objectconverter-field-init`/`perf-webauthn-converter-alloc`):**

0. Confirm webauthn4j `ObjectConverter` and the three wrapper Converters are safe to share as singletons. Verify via the dependency source jar (do not assume):

```
find ~/.gradle/caches -name 'webauthn4j-core-0.31.5.RELEASE-sources.jar' 2>/dev/null | head -1
```
   If a sources jar is found, extract and inspect `ObjectConverter`, `AttestationObjectConverter`, `CollectedClientDataConverter`, `AuthenticationExtensionsClientOutputsConverter`:
```
SRC=$(find ~/.gradle/caches -name 'webauthn4j-core-0.31.5.RELEASE-sources.jar' 2>/dev/null | head -1)
mkdir -p /tmp/w4j-src && (cd /tmp/w4j-src && unzip -o "$SRC" 'com/webauthn4j/converter/**' >/dev/null 2>&1)
ls /tmp/w4j-src/com/webauthn4j/converter/util/ObjectConverter.java
grep -n "private\|final\|class\|public " /tmp/w4j-src/com/webauthn4j/converter/util/ObjectConverter.java | head -40
```
   Acceptance criteria for proceeding: the wrapper Converters hold only a final reference to the injected `ObjectConverter` (no per-call mutable instance state), and `ObjectConverter` wraps Jackson `ObjectMapper`s that are configured once in its constructor and only read thereafter. (Jackson `ObjectMapper` read/write is thread-safe once configuration is frozen.) The existing code already shares one `ObjectConverter` field across concurrent `finish` calls (line 81 / 91), so `ObjectConverter` itself is *already* treated as shared — promoting the three wrappers to fields is the same safety class.
   **If the sources jar is unavailable OR inspection shows per-call mutable state in any wrapper**: do NOT change the wrappers. At minimum still add the `ObjectConverter` bean (the `ObjectConverter` is already shared today, so the bean is safe), but keep the three `new XxxConverter(objectConverter)` calls per-call. Note the partial completion in the commit message and the Self-Review. Proceed to remaining steps accordingly.

**Steps (assuming the gate passed for full reuse)**

1. In `Webauthn4jConfig.java`, add the bean. Add import after line 1's package — insert after `import com.webauthn4j.WebAuthnManager;`:
```java
import com.webauthn4j.converter.util.ObjectConverter;
```
   Add the bean method inside the class, after `webAuthnManager()`:
```java
    @Bean
    public ObjectConverter objectConverter() {
        return new ObjectConverter();
    }
```

2. In `AuthenticationFinishService.java`:
   - Add wrapper Converter fields. After line 61 (`private final ObjectConverter objectConverter;`) add:
```java
    private final AttestationObjectConverter attestationObjectConverter;
    private final CollectedClientDataConverter collectedClientDataConverter;
    private final AuthenticationExtensionsClientOutputsConverter extensionsConverter;
```
   - Change the constructor parameter: replace `ObjectMapper mapper,` ... and the implicit `new ObjectConverter()` by injecting the bean. Change constructor signature to add `ObjectConverter objectConverter` as a parameter (place it right after `ObjectMapper mapper,` on line 71):
```java
                                       ObjectMapper mapper,
                                       ObjectConverter objectConverter,
                                       Clock clock,
```
   - Replace line 81 `this.objectConverter = new ObjectConverter();` with:
```java
        this.objectConverter = objectConverter;
        this.attestationObjectConverter = new AttestationObjectConverter(objectConverter);
        this.collectedClientDataConverter = new CollectedClientDataConverter(objectConverter);
        this.extensionsConverter = new AuthenticationExtensionsClientOutputsConverter(objectConverter);
```
   - In `deserializeCredentialRecordEnvelope` replace the per-call construction (lines 279–283) — delete the three `new ...Converter(objectConverter)` locals and use the fields. Change lines 284–287 to reference the fields:
```java
        AttestationObject attestationObject = attestationObjectConverter.convert(ao);
        CollectedClientData clientData = collectedClientDataConverter.convert(cd);
        AuthenticationExtensionsClientOutputs<RegistrationExtensionClientOutput> clientExtensions =
                extensionsConverter.convert(ceJson);
```
   (Remove the now-deleted `aoConv`/`cdConv`/`ceConv` locals at 279–283.)

3. In `RegistrationFinishService.java` (mirror):
   - After line 71 (`private final ObjectConverter objectConverter;`) add the same three fields.
   - Add `ObjectConverter objectConverter` constructor parameter after `ObjectMapper mapper,` (line 81).
   - Replace line 91 `this.objectConverter = new ObjectConverter();` with the same 4-line field assignment block as in step 2.
   - In `serializeCredentialRecordEnvelope` (lines 232–235) delete the three `new ...Converter` locals and use the fields at 237–239:
```java
            byte[] aoBytes = attestationObjectConverter.convertToBytes(data.getAttestationObject());
            byte[] cdBytes = collectedClientDataConverter.convertToBytes(data.getCollectedClientData());
            String ceJson = extensionsConverter.convertToString(data.getClientExtensions());
```

4. Compile: `./gradlew :passkey-app:compileJava -q` → EXIT 0. (Spring DI now supplies the `ObjectConverter` bean to both constructors.)

5. **Invariant check (serialized bytes)**: locate and run any existing FIDO finish/round-trip unit/slice test:
   `find passkey-app/src/test -name '*FinishService*Test.java' -o -name '*Registration*Test.java' -o -name '*Authentication*Test.java'`
   Run the matched non-IT tests, e.g. `./gradlew :passkey-app:test --tests '*RegistrationFinishServiceTest' --tests '*AuthenticationFinishServiceTest' -q`. If these tests construct the service directly, **their constructor calls must be updated** to pass a `new ObjectConverter()` for the new parameter — do this and re-run. If no such unit test exists, add a focused round-trip test asserting envelope bytes are identical before/after by serializing a fixed `RegistrationData` through the field-based path; if constructing `RegistrationData` is impractical in a unit test, rely on the existing webauthn IT (record as env-gated) and document that the change is a verbatim wrapper-reference swap. Note which path was used in the commit.

6. Commit:
```
git add passkey-app/src/main/java/com/crosscert/passkey/app/fido2/Webauthn4jConfig.java passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java
git commit -m "perf(passkey): reuse webauthn4j ObjectConverter + wrapper Converters

Add @Bean ObjectConverter in Webauthn4jConfig; inject into both FIDO
finish services and hoist Attestation/CollectedClientData/Extensions
converters to final fields (stateless, thread-safe per source review).
Serialized envelope bytes unchanged.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4 — `ProblemJson` helper for filter problem+json (findings: `cq-problemjson-inline-literals`)

**Files**
- `passkey-app/src/main/java/com/crosscert/passkey/app/security/ProblemJson.java` (new)
- `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java`
- `passkey-app/src/main/java/com/crosscert/passkey/app/security/RateLimitFilter.java`
- `passkey-app/src/test/java/com/crosscert/passkey/app/security/ProblemJsonTest.java` (new)

**Invariant at stake**: the exact wire bytes of the three responses:
- 401: `{"type":"about:blank","status":401,"title":"Unauthorized"}`
- 403: `{"type":"about:blank","status":403,"title":"Forbidden","error":"insufficient_scope"}`
- 429: `{"type":"about:blank","status":429,"title":"Too Many Requests"}` + header `Retry-After: 60` (header stays at the call site).
All keep `Content-Type: application/problem+json`. The helper must emit byte-identical JSON; field order preserved (`type,status,title[,error]`).

**Steps**

1. (TDD) Create the snapshot test `passkey-app/src/test/java/com/crosscert/passkey/app/security/ProblemJsonTest.java` that pins the exact bytes against a `MockHttpServletResponse`:

```java
package com.crosscert.passkey.app.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemJsonTest {

    @Test
    void unauthorizedBytesMatchLegacyLiteral() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        ProblemJson.write(res, 401, "Unauthorized");
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentType()).isEqualTo("application/problem+json");
        assertThat(res.getContentAsString())
                .isEqualTo("{\"type\":\"about:blank\",\"status\":401,\"title\":\"Unauthorized\"}");
    }

    @Test
    void forbiddenWithErrorBytesMatchLegacyLiteral() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        ProblemJson.write(res, 403, "Forbidden", "insufficient_scope");
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentType()).isEqualTo("application/problem+json");
        assertThat(res.getContentAsString())
                .isEqualTo("{\"type\":\"about:blank\",\"status\":403,\"title\":\"Forbidden\",\"error\":\"insufficient_scope\"}");
    }

    @Test
    void tooManyRequestsBytesMatchLegacyLiteral() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        ProblemJson.write(res, 429, "Too Many Requests");
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getContentType()).isEqualTo("application/problem+json");
        assertThat(res.getContentAsString())
                .isEqualTo("{\"type\":\"about:blank\",\"status\":429,\"title\":\"Too Many Requests\"}");
    }
}
```

   Run it now → expect **compile failure** (ProblemJson doesn't exist). That is the red state.

2. Create `passkey-app/src/main/java/com/crosscert/passkey/app/security/ProblemJson.java`. The helper hand-assembles the same literal (status echoed into body; title/error are caller-supplied literals only — never user input):

```java
package com.crosscert.passkey.app.security;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Shared RFC7807 problem+json writer for security filters that run
 * OUTSIDE Spring MVC (ApiKeyAuthFilter, RateLimitFilter), where the
 * GlobalExceptionHandler/ApiResponse envelope is unavailable.
 *
 * <p>Output is byte-identical to the previously inline literals:
 * {@code {"type":"about:blank","status":<status>,"title":"<title>"[,"error":"<error>"]}}.
 * title/error are hardcoded literals at the call sites — never user
 * input — so manual JSON assembly carries no injection risk. Callers
 * own any extra headers (e.g. Retry-After).
 */
final class ProblemJson {

    private ProblemJson() {}

    static void write(HttpServletResponse res, int status, String title) throws IOException {
        write(res, status, title, null);
    }

    static void write(HttpServletResponse res, int status, String title, String error)
            throws IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json");
        StringBuilder body = new StringBuilder(96)
                .append("{\"type\":\"about:blank\",\"status\":")
                .append(status)
                .append(",\"title\":\"")
                .append(title)
                .append('"');
        if (error != null) {
            body.append(",\"error\":\"").append(error).append('"');
        }
        body.append('}');
        res.getWriter().write(body.toString());
    }
}
```

3. Run `./gradlew :passkey-app:test --tests 'com.crosscert.passkey.app.security.ProblemJsonTest' -q` → EXIT 0 (3 green). This is the byte-identical proof.

4. Refactor `ApiKeyAuthFilter.java`. Replace the `unauthorized` method (lines 199–205) body and `forbidden` (lines 207–213) body to delegate:
```java
    /** Single generic 401 — no detail leaks. */
    private void unauthorized(HttpServletResponse res) throws IOException {
        ProblemJson.write(res, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
    }

    /** 403 — 키는 유효하나 요청 경로 scope 미보유. */
    private void forbidden(HttpServletResponse res) throws IOException {
        ProblemJson.write(res, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "insufficient_scope");
    }
```
   (`SC_UNAUTHORIZED`=401, `SC_FORBIDDEN`=403 — identical status ints.) No new import needed (`ProblemJson` is same package).

5. Refactor `RateLimitFilter.java` `tooManyRequests` (lines 178–184) — keep the `Retry-After` header at the call site, delegate body:
```java
    private void tooManyRequests(HttpServletResponse res) throws IOException {
        res.setHeader("Retry-After", "60");
        ProblemJson.write(res, 429, "Too Many Requests");
    }
```
   (Order note: `ProblemJson.write` calls `setStatus(429)` and `setContentType`; setting the `Retry-After` header before delegating preserves the original `setStatus`→`setHeader`→`setContentType`→`write` observable result — status, both headers, body all identical.)

6. Compile: `./gradlew :passkey-app:compileJava -q` → EXIT 0.

7. **Invariant check (wire response)**: run any existing filter tests that assert these responses:
   `find passkey-app/src/test -name '*ApiKeyAuthFilter*Test.java' -o -name '*RateLimitFilter*Test.java'`
   Run the matched tests → EXIT 0. If they assert the 401/403/429 body strings, they pin the wire-byte invariant directly; the new `ProblemJsonTest` covers it regardless.

8. Commit:
```
git add passkey-app/src/main/java/com/crosscert/passkey/app/security/ProblemJson.java passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java passkey-app/src/main/java/com/crosscert/passkey/app/security/RateLimitFilter.java passkey-app/src/test/java/com/crosscert/passkey/app/security/ProblemJsonTest.java
git commit -m "refactor(passkey): extract ProblemJson helper for filter 401/403/429

Shared problem+json writer for ApiKeyAuthFilter (401/403) and
RateLimitFilter (429). Retry-After header stays at call site. Byte
snapshot test pins output identical to prior inline literals.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5 — `Clock` injection: `TenantAdminService` + `ApiKeyAuthFilter` (findings: `cq-clock-inconsistency`)

**Files**
- `admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java`
- `admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminServiceTest.java`
- `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java`

**Invariant at stake**: time-dependent branch outputs — `apiKeyRepository.countActiveByTenantId(id, <now>)` and `row.isActive(<now>)`/`touchLastUsed(id, <now>)`. Only the time *source* changes (`Instant.now()` → injected `clock.instant()`); the `Clock` bean is `Clock.systemUTC()`, so produced instants are equivalent. No decision flips.

**Steps**

1. **`TenantAdminService`** — add `Clock` field + constructor param (append at end, per existing pattern):
   - Add import after line 25 (`import java.time.Instant;`):
```java
import java.time.Clock;
```
   - Add field after line 48 (`private final ObjectMapper objectMapper;`):
```java
    private final Clock clock;
```
   - Add constructor parameter as the last param (after line 59 `ObjectMapper objectMapper)`), changing the signature line and adding assignment after line 69:
```java
                              ObjectMapper objectMapper,
                              Clock clock) {
```
   and after `this.objectMapper = objectMapper;` (line 69):
```java
        this.clock = clock;
```
   - Replace line 102 `Instant.now()` usage in `toView`:
```java
        long apiKeys = apiKeyRepository.countActiveByTenantId(t.getId(), clock.instant());
```

2. Update `TenantAdminServiceTest.java` constructor call (line 53–56) to pass a fixed `Clock`. Add import and pass `Clock.systemUTC()` (the test currently exercises only `create`, which doesn't read the clock, so any clock is fine; using `systemUTC` keeps parity with production). Add at top of file:
```java
import java.time.Clock;
```
   Change the constructor call to append the clock arg:
```java
        service = new TenantAdminService(repo, audit, em, boundary,
                aaguidPolicyRepo, snapshotRepo,
                credentialRepository, apiKeyRepository, auditLogRepository,
                new ObjectMapper(), Clock.systemUTC());
```

3. **`ApiKeyAuthFilter`** — add `Clock`:
   - Add import after line 22 (`import java.time.Instant;`):
```java
import java.time.Clock;
```
   - Add field after line 82 (`private final ApplicationEventPublisher eventPublisher;`):
```java
    private final Clock clock;
```
   - Add constructor parameter as last param (after line 89 `ApplicationEventPublisher eventPublisher)`) and assignment after line 95:
```java
                            ApplicationEventPublisher eventPublisher,
                            Clock clock) {
```
   and after `this.eventPublisher = eventPublisher;`:
```java
        this.clock = clock;
```
   - Replace line 135 `Instant now = Instant.now();` with:
```java
        Instant now = clock.instant();
```
   (`now` is still used at lines 136/184 unchanged.)

4. **Bean-availability check (passkey-app)**: confirm the `Clock` bean resolves in `passkey-app`. Evidence already gathered: `AuthenticationFinishService`/`RegistrationFinishService` inject `Clock` successfully, so the bean is on the context. No `@Import` change needed. Verify by compile + any existing `ApiKeyAuthFilter` test still wiring the constructor.

5. Compile: `./gradlew :admin-app:compileJava :passkey-app:compileJava -q` → EXIT 0.

6. **Invariant check**: run the affected unit tests and any filter test that constructs `ApiKeyAuthFilter` directly:
   `./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.tenant.TenantAdminServiceTest' -q` → EXIT 0.
   `find passkey-app/src/test -name '*ApiKeyAuthFilter*Test.java'` — if found and it calls `new ApiKeyAuthFilter(...)`, update that call to append a `Clock` arg (`Clock.fixed(...)` or `Clock.systemUTC()`); since the filter's expiry/touch behavior is unchanged with `systemUTC`, existing assertions hold. Run the filter test → EXIT 0.

7. Commit:
```
git add admin-app/src/main/java/com/crosscert/passkey/admin/tenant/TenantAdminService.java admin-app/src/test/java/com/crosscert/passkey/admin/tenant/TenantAdminServiceTest.java passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java
git commit -m "refactor(admin,passkey): inject Clock into TenantAdminService + ApiKeyAuthFilter

Replace Instant.now() with injected clock.instant() (Clock.systemUTC
bean) for the api-key active-count and the filter expiry/touch reads.
Time source only; branch decisions unchanged.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6 — `touchLastUsed` swallow gets a log line (finding: `quality-touchlastused-silent-swallow`)

**Files**
- `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyLookupService.java`

**Invariant at stake**: authentication still returns success on touch failure (best-effort preserved); response/UI unchanged. Only an observability log line is added. `MeterRegistry` is NOT injected here (constructor takes `DataSource` only) — adding it would widen the dependency surface, so per spec we take the conservative SLF4J option.

**Steps**

1. Add an SLF4J Logger. Add imports after line 6 (`import org.springframework.stereotype.Service;`):
```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

2. Add the logger field inside the class after line 40 (`private final JdbcTemplate jdbc;`):
```java
    private static final Logger log = LoggerFactory.getLogger(ApiKeyLookupService.class);
```

3. Replace the empty catch body (lines 87–90) with a single warn line (the call runs with tenant/MDC context already set by `ApiKeyAuthFilter`, so prefix/tenantId appear in the log via MDC):
```java
        } catch (DataAccessException e) {
            // Best-effort: do NOT fail a valid auth. last_used_at stays
            // stale by at most one tx. Log so a persistent touch failure
            // (which silently degrades stale-key detection) is visible.
            log.warn("api-key touch_last_used failed (best-effort): {}", e.toString());
        }
```

4. Compile: `./gradlew :passkey-app:compileJava -q` → EXIT 0.

5. **Invariant check**: the method signature, return type (`void`), and best-effort semantics are unchanged — the catch still swallows the exception (no rethrow), so auth success is preserved. Run any existing `ApiKeyLookupService` test if present (`find passkey-app/src/test -name '*ApiKeyLookupService*Test.java'`) → EXIT 0; no test asserts the absence of a log line, so adding one cannot break a green test.

6. Commit:
```
git add passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyLookupService.java
git commit -m "quality(passkey): log best-effort touch_last_used failures

Add SLF4J warn in the DataAccessException swallow so a persistent
last_used_at update failure (degrades stale-key detection) is no
longer invisible. Auth result/response unchanged; still swallowed.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7 — `CredentialRepository` ORDER BY / Pageable Sort de-duplication (finding: `perf-findall-ordered-query-pageable-sort`)

**Files**
- `admin-app/src/main/java/com/crosscert/passkey/admin/credential/CredentialAdminService.java`

**Invariant at stake**: result ordering of `list()` — `lastUsedAt DESC NULLS LAST, id DESC`. The `@Query` on `findAllByTenantId` already encodes this exact order; the call site passes a duplicate `Sort`. The native `searchByTenantId` query has **no** ORDER BY and Spring Data does not reliably translate `Sort` into native SQL, so the search-path order is already query-driven (unspecified there) — removing `Sort` does not change its behavior. We **unify on the `@Query` ORDER BY** (remove `Sort` from the call site) — minimal-impact, preserves NULLS LAST semantics.

**Steps**

1. In `CredentialAdminService.java` `list()` (lines 54–56), replace the `Sort`-bearing `PageRequest` with size/page only:
```java
        // Ordering is owned by the @Query ORDER BY of findAllByTenantId
        // (lastUsedAt desc nulls last, id desc). searchByTenantId is a
        // native query with no ORDER BY and Spring Data does not push
        // Pageable Sort into native SQL — so passing Sort here was a no-op
        // there and a duplicate for the JPQL path. Removing it keeps the
        // JPQL ordering authoritative and avoids the duplicate ORDER BY.
        Pageable pageReq = PageRequest.of(Math.max(page, 0), cappedSize);
```

2. Remove the now-unused `Sort` import if no other usage remains. Grep:
   `grep -n "Sort" admin-app/src/main/java/com/crosscert/passkey/admin/credential/CredentialAdminService.java`
   If the only hits were the removed lines, delete `import org.springframework.data.domain.Sort;`. If `Sort` is referenced elsewhere, leave the import.

3. Compile: `./gradlew :admin-app:compileJava -q` → EXIT 0.

4. **Invariant check (ordering)**: run the existing credential list test:
   `find admin-app/src/test -name '*CredentialAdminService*Test.java'` then run it (e.g. `./gradlew :admin-app:test --tests '*CredentialAdminServiceTest' -q`). If the test asserts ordering, it pins the invariant. If no unit test exercises ordering (likely, since ordering needs DB rows), document that the JPQL `@Query` ORDER BY clause is unchanged and remains the single ordering source — the change only removes a redundant duplicate `Sort` for the JPQL path and a no-op `Sort` for the native path; visible order is preserved. Note this reasoning in the commit body.

5. Commit:
```
git add admin-app/src/main/java/com/crosscert/passkey/admin/credential/CredentialAdminService.java
git commit -m "refactor(admin): drop duplicate Sort in CredentialAdminService.list

findAllByTenantId @Query already encodes lastUsedAt desc nulls last,
id desc. The call-site PageRequest Sort duplicated it (JPQL) and was a
no-op for the native search query. Unify on the @Query ORDER BY —
visible ordering and NULLS LAST semantics unchanged.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8 — `Credential.recordAuthentication` no-op arg removal (finding: `cq-credential-recordauth-noop-arg`)

**Files**
- `core/src/main/java/com/crosscert/passkey/core/entity/Credential.java`
- `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java`

**Invariant at stake**: persisted row state after authentication — `signCount` and `lastUsedAt` updated, BLOB (`publicKey`) **untouched**. The sole caller passes `cred.getCredentialRecordBytes()` back into the BLOB (self-assign no-op), so dropping the param yields identical persisted bytes.

**Pre-check (mandatory — confirm single caller before changing the signature):**

0. Grep the whole repo for callers:
   `grep -rn "recordAuthentication(" core admin-app passkey-app sdk-java`
   Expected: exactly one call site — `AuthenticationFinishService.java:232` — plus the declaration in `Credential.java:79`. If any other caller exists, STOP and re-plan (the simplification assumption is broken). Proceed only if single caller confirmed.

**Steps**

1. In `Credential.java`, change `recordAuthentication` (lines 79–83) to drop the `byte[]` param and leave the BLOB untouched:
```java
    /**
     * Atomic state mutation after a successful authentication. signCount
     * must be strictly increasing (replay defense); callers verify that
     * before calling this. The stored CBOR CredentialRecord BLOB is
     * immutable here — the prior signature took a byte[] that callers
     * only ever passed back unchanged (self-assign no-op), so it was
     * removed.
     */
    public void recordAuthentication(long newSignCount, Instant now) {
        this.signCount = newSignCount;
        this.lastUsedAt = now;
    }
```

2. In `AuthenticationFinishService.java` line 232, change the call to the 2-arg form:
```java
            cred.recordAuthentication(newCounter, clock.instant());
```

3. Compile: `./gradlew :core:compileJava :passkey-app:compileJava -q` → EXIT 0.

4. **Invariant check (persisted state)**: the BLOB column (`publicKey`) is no longer written by this method, but since the old call wrote back the *same* bytes (`cred.getCredentialRecordBytes()`), Hibernate's dirty-checking produces the same persisted row. Run any existing `Credential` or `AuthenticationFinishService` test:
   `find core/src/test passkey-app/src/test -name '*Credential*Test.java' -o -name '*AuthenticationFinishService*Test.java'`
   Run matched non-IT tests → EXIT 0. If a test calls `recordAuthentication(count, bytes, now)` (3-arg), update it to 2-arg and re-run. Confirm signCount/lastUsedAt assertions still pass.

5. Commit:
```
git add core/src/main/java/com/crosscert/passkey/core/entity/Credential.java passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java
git commit -m "refactor(core): drop self-assign byte[] arg from recordAuthentication

The sole caller passed the current BLOB straight back (no-op). Simplify
to recordAuthentication(signCount, now); BLOB untouched. Persisted row
state (signCount, lastUsedAt, publicKey) unchanged.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9 — `DevTenantHeaderFilter` TODO cleanup (finding: `cq-devtenant-todo-deadpath`)

**Files**
- `core/src/main/java/com/crosscert/passkey/core/vpd/DevTenantHeaderFilter.java`

**Invariant at stake**: dev-only filter behavior (UUID-only `X-Tenant-Id` resolution). This is the codebase's only remaining TODO. Per spec, we **fix intent via comment** (do not implement slug fallback — it's unneeded; the filter is `@Profile({local,dev,test})` and already rejects non-UUID values with a warning). No runtime branch changes.

**Steps**

1. Replace the deferred-TODO comment block (the lines around 62–64 containing `// TODO T13/T14: add slug fallback via TenantRepository.findBySlug.`) with a decisive intent comment. Change:
```java
        // Phase 6: X-Tenant-Id must be a UUID string for dev/test use.
        // Slug → UUID resolution is deferred to T13/T14 (TenantRepository
        // findBySlug). For now, operators pass the UUID directly.
        // TODO T13/T14: add slug fallback via TenantRepository.findBySlug.
```
   to:
```java
        // X-Tenant-Id is a UUID string only. This dev/test-only filter
        // (@Profile local/dev/test) deliberately does NOT resolve slugs:
        // production traffic authenticates via X-API-Key (ApiKeyAuthFilter),
        // which sets tenant context from the verified key, so a slug
        // fallback here would be dead code. Non-UUID values are rejected
        // with a warning below.
```

2. Also update the inner comment at the `catch (IllegalArgumentException e)` branch (the line `// Slug resolution deferred to T13/T14. For now reject invalid UUID.`) to match the decided intent:
```java
                // Reject non-UUID X-Tenant-Id (slug resolution intentionally
                // unsupported in this dev-only filter — see header note).
```

3. Compile: `./gradlew :core:compileJava -q` → EXIT 0.

4. **Invariant check (behavior)**: only comments changed — no statement edited. Verify the only TODO/FIXME marker is gone repo-wide:
   `grep -rn "TODO\|FIXME" core/src/main passkey-app/src/main admin-app/src/main` → expect 0 hits (matching the audit's "codebase's only TODO" claim). Run any existing `DevTenantHeaderFilter` test (`find core/src/test -name '*DevTenantHeaderFilter*Test.java'`) → EXIT 0.

5. Commit:
```
git add core/src/main/java/com/crosscert/passkey/core/vpd/DevTenantHeaderFilter.java
git commit -m "docs(core): resolve DevTenantHeaderFilter slug-fallback TODO as intentional

The dev/test-only filter deliberately supports UUID X-Tenant-Id only;
production uses X-API-Key. Replace the deferred TODO with decisive
intent comments — codebase's last TODO marker. No behavior change.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 10 (OPTIONAL — finding `perf-meter-counter-resolve`) — cache result Counters in `ApiKeyAuthFilter`

**Files**
- `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java`

**Invariant at stake**: emitted metric series — same counter name `passkey_apikey_auth_total` with tag `result=<value>` for each branch (`unknown_prefix`, `revoked`, `expired`, `bad_secret`, `insufficient_scope`, `success`). Resolving each `Counter` once and reusing it produces the identical time-series (Micrometer returns the same Counter for a given name+tags). This is optional per spec — implement only if time allows after Tasks 1–9 are green.

**Note on the `revoked`/`expired` branch**: line 139–141 currently resolves the tag at runtime (`reason` is `"revoked"` or `"expired"`). Caching requires two pre-resolved counters keyed by reason. Keep both.

**Steps**

1. After the `AUTH_COUNTER` constant (line 75) and the field declarations, resolve the counters in the constructor. Add fields after line 82:
```java
    private final io.micrometer.core.instrument.Counter cSuccess;
    private final io.micrometer.core.instrument.Counter cUnknownPrefix;
    private final io.micrometer.core.instrument.Counter cRevoked;
    private final io.micrometer.core.instrument.Counter cExpired;
    private final io.micrometer.core.instrument.Counter cBadSecret;
    private final io.micrometer.core.instrument.Counter cInsufficientScope;
```

2. In the constructor (after `this.meterRegistry = meterRegistry;`, before/after other assignments), resolve once:
```java
        this.cSuccess = meterRegistry.counter(AUTH_COUNTER, "result", "success");
        this.cUnknownPrefix = meterRegistry.counter(AUTH_COUNTER, "result", "unknown_prefix");
        this.cRevoked = meterRegistry.counter(AUTH_COUNTER, "result", "revoked");
        this.cExpired = meterRegistry.counter(AUTH_COUNTER, "result", "expired");
        this.cBadSecret = meterRegistry.counter(AUTH_COUNTER, "result", "bad_secret");
        this.cInsufficientScope = meterRegistry.counter(AUTH_COUNTER, "result", "insufficient_scope");
```

3. Replace each hot-path `meterRegistry.counter(AUTH_COUNTER, "result", "...").increment()` with the cached field:
   - line 129 → `cUnknownPrefix.increment();`
   - lines 139–141: keep `reason` for the log; for the counter use `(row.revokedAt() != null ? cRevoked : cExpired).increment();`
   - line 147 → `cBadSecret.increment();`
   - line 172 → `cInsufficientScope.increment();`
   - line 188 → `cSuccess.increment();`

4. Compile: `./gradlew :passkey-app:compileJava -q` → EXIT 0.

5. **Invariant check (metric series)**: counter name + tags are unchanged (same `AUTH_COUNTER` constant + same `result` tag values), so Micrometer registers the same meters; only the lookup is hoisted. Run any `ApiKeyAuthFilter` test (constructor now resolves 6 counters from the injected/mocked `MeterRegistry` — a real `SimpleMeterRegistry` or a Mockito mock returning real counters works; if a test mocks `MeterRegistry`, ensure `counter(...)` is stubbed to return a `Counter` or use `SimpleMeterRegistry`). Update the test wiring if needed → EXIT 0.

6. Commit:
```
git add passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java
git commit -m "perf(passkey): cache result Counters in ApiKeyAuthFilter hot path

Resolve the 6 result-tagged passkey_apikey_auth_total counters once in
the constructor instead of per-request name+tag lookup. Same meter
name/tags → identical metric series.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Final verification (after all tasks)

1. Full compile gate: `./gradlew :core:compileJava :admin-app:compileJava :passkey-app:compileJava -q` → EXIT 0.
2. Targeted invariant tests:
```
./gradlew :core:test --tests 'com.crosscert.passkey.core.util.CryptoUtilsTest' \
                      --tests 'com.crosscert.passkey.core.jwt.SigningKeyProviderTest' -q
./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.keymgmt.KeyRotationServiceTest' \
                          --tests 'com.crosscert.passkey.admin.tenant.TenantAdminServiceTest' -q
./gradlew :passkey-app:test --tests 'com.crosscert.passkey.app.security.ProblemJsonTest' -q
```
   All EXIT 0. Record any Oracle `*IT` env failures separately (flaky, not regressions).
3. Confirm no TODO/FIXME remains: `grep -rn "TODO\|FIXME" core/src/main passkey-app/src/main admin-app/src/main` → 0 hits.

---

## Self-Review

### Spec coverage (every QW-4 finding id → Task)

| Finding id | Task | Invariant pinned by |
|---|---|---|
| `perf-cryptoutils-hex-string-format` | 1 | `CryptoUtilsTest` all-256-byte pin (hex==HexFormat) → audit hash-chain hex |
| `cq-rsa-keygen-dup` | 2 | `SigningKeyProviderTest` generate path + `KeyRotationServiceTest` → signing-key format |
| `cq-objectconverter-field-init` | 3 | thread-safety gate + finish-service round-trip/IT → serialized envelope bytes |
| `perf-webauthn-converter-alloc` | 3 | same as above |
| `cq-clock-inconsistency` | 5 | `TenantAdminServiceTest` + filter test → branch decisions (systemUTC) |
| `cq-problemjson-inline-literals` | 4 | `ProblemJsonTest` byte snapshot → 401/403/429 wire bytes |
| `quality-touchlastused-silent-swallow` | 6 | signature/best-effort unchanged; log-only |
| `perf-meter-counter-resolve` (optional) | 10 | same meter name+tags → identical series |
| `perf-findall-ordered-query-pageable-sort` | 7 | `@Query` ORDER BY retained as single source |
| `cq-credential-recordauth-noop-arg` | 8 | single-caller pre-check + BLOB-untouched reasoning |
| `cq-devtenant-todo-deadpath` | 9 | comment-only; grep confirms last TODO removed |

All 11 findings covered (10 mandatory + 1 optional). No finding omitted.

### Placeholder scan
- No "TBD"/"적절히 처리"/"테스트 추가" placeholders. Every code Step has a concrete block, command, or grep-driven decision. Where a file's exact test surface is unknown (FIDO round-trip, credential ordering, filter tests), the plan inserts an explicit `find ...` discovery Step plus a documented fallback (verbatim-swap reasoning) rather than inventing a test that may not compile.

### Type consistency
- `CryptoUtils.hex(byte[]) → String` signature unchanged (Task 1).
- `SigningKeyFactory.newRsaSigningKey(KeyEnvelope) → SigningKey` matches `SigningKey(String,String,String,byte[])` ctor (Task 2).
- `ProblemJson.write(HttpServletResponse,int,String[,String])` returns `void throws IOException` — matches filter call sites that already `throws IOException` (Task 4).
- `Clock` injected as the **last** constructor parameter in both `TenantAdminService` and `ApiKeyAuthFilter`, matching the established admin/passkey pattern; `clock.instant()` returns `Instant`, drop-in for the prior `Instant.now()` (Task 5).
- `recordAuthentication(long, Instant)` — caller updated to 2-arg; `clock.instant()` supplies the `Instant` (Task 8).
- Optional Task 10 uses `io.micrometer.core.instrument.Counter` (fully qualified to avoid an import churn decision); `.increment()` is the same call used today.

### Ordering & risk notes
- Tasks are independent and may run in any order, except: Task 5 and Task 8 both touch `AuthenticationFinishService.java` (Clock is already a field there; Task 8 uses `clock.instant()` which already exists at line 232) — no conflict, but if executed by parallel subagents, serialize edits to that file. Task 3 also edits `AuthenticationFinishService.java` — **Tasks 3, 5, 8 share that file; run them sequentially, not in parallel.**
- Highest-risk invariants (hash-chain, signing-key, wire bytes) are each guarded by a dedicated assertion (Tasks 1, 2, 4). Task 3 carries an explicit STOP/DONE_WITH_CONCERNS gate if webauthn4j thread-safety can't be confirmed from source.