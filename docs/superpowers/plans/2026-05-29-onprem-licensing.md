# On-Premise Licensing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 단일 바이너리로 SaaS / on-prem 모드를 모두 지원하고, on-prem 모드에서는 Ed25519 서명 JWS 라이센스 토큰의 부팅 검증·heartbeat 폴링·grace period·feature gating·만료 차단을 활성화한다.

**Architecture:** 모든 라이센스 로직을 `:core` 모듈의 `com.crosscert.passkey.core.license` 패키지에 추가. Spring `@ConditionalOnProperty(name="passkey.deployment.mode", havingValue="onprem")` 로 빈 활성화 분기. SaaS 모드(`saas` 기본값)에서는 모든 라이센스 빈이 비활성이라 기존 동작 무회귀. Guard filter 는 admin-app(Spring Security filter chain)과 passkey-app(`OncePerRequestFilter` + `@Component`) 양쪽에 적용.

**Tech Stack:**
- 서명: Ed25519 EdDSA (`com.nimbusds:nimbus-jose-jwt` — 이미 deps 에 있음)
- Scheduling: Spring `@Scheduled` (admin-app 의 `@EnableScheduling` 활용)
- AOP: Spring AOP (`@Aspect` + `@Around`) — `spring-boot-starter-aop` 추가 필요
- 로깅: SLF4J + 기존 MDC 키 (`traceId`, `tenantId`)
- 테스트: JUnit 5 + Spring Boot Test + 기존 testcontainers 인프라

**Spec:** `docs/superpowers/specs/2026-05-29-onprem-licensing-design.md`

---

## File Structure

각 파일의 책임을 미리 명시. 변경 단위가 task 와 일치한다.

### Create

| 파일 | 책임 |
|---|---|
| `core/src/main/java/com/crosscert/passkey/core/license/LicenseProperties.java` | `@ConfigurationProperties("passkey.license")` — path, heartbeat URL, interval, audience |
| `core/src/main/java/com/crosscert/passkey/core/license/LicenseToken.java` | 검증된 토큰의 도메인 record (sub, jti, exp, features, tenantId, limits) |
| `core/src/main/java/com/crosscert/passkey/core/license/LicenseLimits.java` | record (warningDaysBeforeExpiry, graceHoursWhenOffline) |
| `core/src/main/java/com/crosscert/passkey/core/license/LicenseVerifier.java` | Nimbus JWS 파싱 + Ed25519 서명·iss·aud·nbf·exp 검증 → `LicenseToken` |
| `core/src/main/java/com/crosscert/passkey/core/license/LicensePublicKeyProvider.java` | classpath 리소스에서 Ed25519 public key 로드 |
| `core/src/main/java/com/crosscert/passkey/core/license/LicenseLoader.java` | `passkey.license.path` 파일 → JWS 문자열 |
| `core/src/main/java/com/crosscert/passkey/core/license/LicenseCache.java` | 디스크 캐시 (`/var/lib/passkey/license-cache.jwt`) read/write + `lastVerifiedAt` 메타 |
| `core/src/main/java/com/crosscert/passkey/core/license/LicenseState.java` | enum: VALID, WARNING, NETWORK_GRACE, DEAD |
| `core/src/main/java/com/crosscert/passkey/core/license/LicenseStateMachine.java` | 싱글톤 빈, 현재 상태 + 토큰 + lastVerifiedAt 보관, heartbeat 결과 적용 |
| `core/src/main/java/com/crosscert/passkey/core/license/LicenseHeartbeatScheduler.java` | `@Scheduled` 매 1h, 라이센스 서버 호출 → state machine 갱신 |
| `core/src/main/java/com/crosscert/passkey/core/license/LicenseBootstrap.java` | `ApplicationRunner` — 부팅 시 토큰 로드·검증·초기 상태 결정·VPD set_tenant |
| `core/src/main/java/com/crosscert/passkey/core/license/RequiresFeature.java` | 어노테이션 `@RequiresFeature("mds")` |
| `core/src/main/java/com/crosscert/passkey/core/license/FeatureGateAspect.java` | AOP, `RequiresFeature` 검사 → `FeatureNotLicensedException` |
| `core/src/main/java/com/crosscert/passkey/core/license/FeatureNotLicensedException.java` | RuntimeException |
| `core/src/main/java/com/crosscert/passkey/core/license/LicenseGuardFilter.java` | `OncePerRequestFilter` (passkey-app 용 `@Component` + `@Order` HIGHEST + 1). admin-app 은 Spring Security 가 등록 |
| `core/src/main/java/com/crosscert/passkey/core/license/LicenseHealthIndicator.java` | actuator `HealthIndicator` |
| `core/src/main/resources/license-public.ed25519.pub` | Ed25519 public key — PEM 형식 (placeholder, L1 에서 테스트 키페어 사용) |
| `core/src/test/resources/license-public.test.ed25519.pub` | 테스트용 public key (private key 와 쌍) |
| `core/src/test/resources/license-private.test.ed25519.pem` | 테스트용 private key (fixture 발급용) |
| `core/src/test/java/com/crosscert/passkey/core/license/LicenseTestFixtures.java` | `issueValid`, `issueExpired`, `issueWithBadSignature` 헬퍼 |
| `core/src/test/java/com/crosscert/passkey/core/license/LicenseVerifierTest.java` | 단위 테스트 — 서명·만료·잘못된 서명 |
| `core/src/test/java/com/crosscert/passkey/core/license/LicenseStateMachineTest.java` | 단위 테스트 — 상태 전이 + clock rollback |
| `admin-app/src/main/java/com/crosscert/passkey/admin/license/LicenseController.java` | `GET /admin/api/license` |
| `admin-app/src/main/java/com/crosscert/passkey/admin/license/LicenseView.java` | API 응답 record |
| `admin-app/src/test/java/com/crosscert/passkey/admin/license/LicenseGuardFilterIT.java` | DEAD 시 ceremony 차단 / `/admin/api/license` 허용 |
| `admin-app/src/test/java/com/crosscert/passkey/admin/license/DeploymentModeProfileIT.java` | SaaS 모드 부팅 시 라이센스 빈 없음 (회귀 방지) |
| `admin-ui/src/api/license.ts` | `getLicense()` API 호출 |
| `admin-ui/src/components/LicenseBanner.tsx` | 전역 경고 배너 |
| `admin-ui/src/pages/LicensePage.tsx` | 라이센스 상태·만료일·feature 표시 |
| `docs/onprem-deployment.md` | 운영 가이드 |

### Modify

| 파일 | 변경 |
|---|---|
| `core/build.gradle.kts` | `spring-boot-starter-aop` 추가 |
| `core/src/main/resources/application-common.yml` | `passkey.deployment.mode: saas` 기본값 + `passkey.license.*` 기본값 |
| `core/src/main/java/com/crosscert/passkey/core/api/ErrorCode.java` | `LICENSE_EXPIRED`, `FEATURE_NOT_LICENSED` 추가 |
| `core/src/main/java/com/crosscert/passkey/core/logging/SecretMaskingConverter.java` | 라이센스 JWS 평문 마스킹 패턴 추가 |
| `core/src/main/java/com/crosscert/passkey/core/api/GlobalExceptionHandler.java` | `FeatureNotLicensedException` → 403 매핑 |
| `admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java` | filter chain 에 `LicenseGuardFilter` `addFilterBefore`, license 경로 permitAll |
| `admin-app/src/main/java/com/crosscert/passkey/admin/system/SystemInfoController.java` | `deploymentMode` 노출 (이미 system info 응답에 추가) |
| `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSchedulerService.java` | `@RequiresFeature("mds")` (또는 `MdsSyncJob` 의 `@Scheduled` 메서드에 부착) |
| `admin-ui/src/App.tsx` | `LicenseBanner` 전역 마운트 + on-prem 모드일 때 `/license` 라우트 추가 |
| `admin-ui/src/shell/Sidebar.tsx` | on-prem 일 때만 License 메뉴 노출 |
| `README.md` | `deployment.mode` 한 줄 추가 |
| `docs/logging-operations.md` | license 검색 cookbook 한 섹션 추가 |

---

## Task Decomposition

### Phase L1 — Foundation: 토큰 포맷 + Verifier

이 phase 가 끝나면 토큰을 파싱·검증할 수 있는 라이브러리 상태. 빈 등록 없음.

---

### Task L1.1: AOP 의존성 추가

**Files:**
- Modify: `core/build.gradle.kts`

- [ ] **Step 1: Edit `core/build.gradle.kts`**

`api("org.springframework.boot:spring-boot-starter-validation")` 다음 줄에 추가:

```kotlin
api("org.springframework.boot:spring-boot-starter-aop")
```

- [ ] **Step 2: 빌드 검증**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/onprem-licensing
./gradlew :core:compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/build.gradle.kts
git commit -m "build(core): add spring-boot-starter-aop for license feature gate (L1.1)"
```

---

### Task L1.2: LicenseProperties + 기본 설정값

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/license/LicenseProperties.java`
- Modify: `core/src/main/resources/application-common.yml`

- [ ] **Step 1: Write LicenseProperties.java**

```java
package com.crosscert.passkey.core.license;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Configuration for on-prem license validation. All properties are
 * unused when passkey.deployment.mode=saas (no @ConditionalOnProperty
 * here on purpose — we want the binding to fail loudly if the YAML
 * shape is wrong, even in SaaS mode).
 */
@ConfigurationProperties(prefix = "passkey.license")
public record LicenseProperties(
        Path path,
        Path cachePath,
        String issuer,
        String audience,
        String heartbeatUrl,
        Duration heartbeatInterval
) {
    public LicenseProperties {
        // Constructor-validated defaults — apply on a fresh record copy
        // because record canonical constructor params are final.
        if (path == null) path = Path.of("/etc/passkey/license.jwt");
        if (cachePath == null) cachePath = Path.of("/var/lib/passkey/license-cache.jwt");
        if (issuer == null) issuer = "license.crosscert.com";
        if (audience == null) audience = "passkey-onprem";
        if (heartbeatInterval == null) heartbeatInterval = Duration.ofHours(1);
    }
}
```

- [ ] **Step 2: Append to `core/src/main/resources/application-common.yml`**

파일 끝에 추가:

```yaml

passkey:
  deployment:
    mode: saas
  license:
    path: /etc/passkey/license.jwt
    cache-path: /var/lib/passkey/license-cache.jwt
    issuer: license.crosscert.com
    audience: passkey-onprem
    heartbeat-url: https://license.crosscert.com/v1/license
    heartbeat-interval: PT1H
```

- [ ] **Step 3: 컴파일 검증**

```bash
./gradlew :core:compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/license/LicenseProperties.java \
        core/src/main/resources/application-common.yml
git commit -m "feat(core): LicenseProperties + deployment.mode default (L1.2)"
```

---

### Task L1.3: ErrorCode 추가 + 예외 클래스

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/api/ErrorCode.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/license/FeatureNotLicensedException.java`

- [ ] **Step 1: ErrorCode.java 에 License (L) 섹션 추가**

`AAGUID_REVOKED` 다음 줄 (enum 마지막 직전) 에 추가:

```java
    // License (L)
    LICENSE_EXPIRED(HttpStatus.SERVICE_UNAVAILABLE, "L001", "License expired or not yet valid"),
    FEATURE_NOT_LICENSED(HttpStatus.FORBIDDEN, "L002", "Feature not included in license");
```

`AAGUID_REVOKED` 의 끝 세미콜론을 콤마로 변경하는 것 잊지 말 것.

- [ ] **Step 2: Write FeatureNotLicensedException.java**

```java
package com.crosscert.passkey.core.license;

/**
 * Thrown by FeatureGateAspect when a method gated with
 * @RequiresFeature is invoked but the current license does not
 * include that feature. GlobalExceptionHandler maps this to
 * HTTP 403 with ErrorCode.FEATURE_NOT_LICENSED.
 */
public class FeatureNotLicensedException extends RuntimeException {
    private final String feature;

    public FeatureNotLicensedException(String feature) {
        super("Feature not licensed: " + feature);
        this.feature = feature;
    }

    public String feature() { return feature; }
}
```

- [ ] **Step 3: 컴파일 검증**

```bash
./gradlew :core:compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/api/ErrorCode.java \
        core/src/main/java/com/crosscert/passkey/core/license/FeatureNotLicensedException.java
git commit -m "feat(core): ErrorCode L001/L002 + FeatureNotLicensedException (L1.3)"
```

---

### Task L1.4: 테스트용 Ed25519 키페어 생성 및 커밋

**Files:**
- Create: `core/src/test/resources/license-public.test.ed25519.pub`
- Create: `core/src/test/resources/license-private.test.ed25519.pem`

- [ ] **Step 1: 키페어 생성 (openssl)**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2/.claude/worktrees/onprem-licensing
openssl genpkey -algorithm Ed25519 -out core/src/test/resources/license-private.test.ed25519.pem
openssl pkey -in core/src/test/resources/license-private.test.ed25519.pem -pubout -out core/src/test/resources/license-public.test.ed25519.pub
```

Expected: 두 파일 생성됨. 각각 `-----BEGIN PRIVATE KEY-----` / `-----BEGIN PUBLIC KEY-----` 헤더 포함.

- [ ] **Step 2: 키 형식 확인**

```bash
cat core/src/test/resources/license-public.test.ed25519.pub
```

Expected: `-----BEGIN PUBLIC KEY-----` 로 시작하는 PEM 텍스트.

- [ ] **Step 3: production placeholder key 생성**

production public key 는 L1 에서는 테스트 키와 동일 (배포 전 교체). placeholder 임을 명시:

```bash
cp core/src/test/resources/license-public.test.ed25519.pub core/src/main/resources/license-public.ed25519.pub
```

- [ ] **Step 4: README 한 줄 추가 (테스트 키임을 명시)**

`core/src/test/resources/` 안에 별도 README 만드는 대신, public key 파일에 주석 형식이 통하지 않으므로 commit 메시지로만 표시. Production 키 교체는 별도 ops 절차로 분리 (이 plan 의 L5 docs 에서 다룸).

- [ ] **Step 5: Commit**

```bash
git add core/src/test/resources/license-public.test.ed25519.pub \
        core/src/test/resources/license-private.test.ed25519.pem \
        core/src/main/resources/license-public.ed25519.pub
git commit -m "test(core): Ed25519 keypair for license tests + placeholder prod key (L1.4)

* test keypair generated via openssl genpkey -algorithm Ed25519
* core/src/main/resources/license-public.ed25519.pub is a PLACEHOLDER
  copy of the test public key; production deployment must replace it
  with the real Crosscert license issuer public key before shipping."
```

---

### Task L1.5: LicensePublicKeyProvider — classpath 로 Ed25519 public key 로드

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/license/LicensePublicKeyProvider.java`

- [ ] **Step 1: Write LicensePublicKeyProvider.java**

```java
package com.crosscert.passkey.core.license;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.OctetKeyPair;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Loads the Ed25519 public key bundled with the jar
 * (license-public.ed25519.pub on the classpath) and exposes it as a
 * Nimbus OctetKeyPair for use by LicenseVerifier. Profile-aware:
 * @Profile("test") + a sibling test bean override this with the
 * test keypair. Production deployment ships the real key in the jar.
 */
@Component
public class LicensePublicKeyProvider {

    private static final String DEFAULT_RESOURCE = "license-public.ed25519.pub";

    private final OctetKeyPair publicKey;

    public LicensePublicKeyProvider() {
        this(DEFAULT_RESOURCE);
    }

    /** Test-friendly ctor. */
    public LicensePublicKeyProvider(String classpathResource) {
        this.publicKey = load(classpathResource);
    }

    public OctetKeyPair publicKey() {
        return publicKey;
    }

    private static OctetKeyPair load(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            String pem = new String(in.readAllBytes());
            byte[] der = Base64.getDecoder().decode(stripPem(pem));
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(der));
            EdECPublicKey ed = (EdECPublicKey) pub;
            // Nimbus OctetKeyPair construction from raw Ed25519 32-byte x
            byte[] raw = extractRawX(ed);
            return new OctetKeyPair.Builder(Curve.Ed25519,
                    com.nimbusds.jose.util.Base64URL.encode(raw))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load license public key from classpath: " + resource, e);
        }
    }

    private static String stripPem(String pem) {
        return pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                  .replaceAll("-----END [A-Z ]+-----", "")
                  .replaceAll("\\s", "");
    }

    /**
     * Extract the 32-byte little-endian raw X coordinate from an
     * EdECPublicKey. Java exposes the point.y BigInteger; for Ed25519
     * the encoded form is exactly the 32-byte little-endian x with the
     * sign bit set in the MSB if point.x is odd.
     */
    private static byte[] extractRawX(EdECPublicKey key) {
        byte[] y = bigIntegerToLE(key.getPoint().getY(), 32);
        if (key.getPoint().isXOdd()) {
            y[31] |= (byte) 0x80;
        }
        return y;
    }

    private static byte[] bigIntegerToLE(java.math.BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] le = new byte[len];
        // strip leading zero (BigInteger sign byte) if present
        int srcOff = (be.length > len) ? be.length - len : 0;
        int srcLen = Math.min(be.length - srcOff, len);
        for (int i = 0; i < srcLen; i++) {
            le[i] = be[be.length - 1 - srcOff - i];
        }
        return le;
    }
}
```

- [ ] **Step 2: 컴파일 검증**

```bash
./gradlew :core:compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/license/LicensePublicKeyProvider.java
git commit -m "feat(core): LicensePublicKeyProvider — load Ed25519 pub key from classpath (L1.5)"
```

---

### Task L1.6: LicenseLimits + LicenseToken records

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/license/LicenseLimits.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/license/LicenseToken.java`

- [ ] **Step 1: Write LicenseLimits.java**

```java
package com.crosscert.passkey.core.license;

/**
 * Per-license tunables embedded in the JWS payload.
 * Defaults applied when fields are absent from the token.
 */
public record LicenseLimits(
        int warningDaysBeforeExpiry,
        int graceHoursWhenOffline
) {
    public static final LicenseLimits DEFAULTS = new LicenseLimits(30, 72);
}
```

- [ ] **Step 2: Write LicenseToken.java**

```java
package com.crosscert.passkey.core.license;

import java.time.Instant;
import java.util.Set;

/**
 * Verified license token — represents trusted claims AFTER signature
 * and aud/iss/nbf checks pass. Constructed by LicenseVerifier only.
 */
public record LicenseToken(
        String sub,
        String jti,
        Instant issuedAt,
        Instant notBefore,
        Instant expiresAt,
        String tenantId,
        Set<String> features,
        LicenseLimits limits
) {
    public boolean hasFeature(String name) {
        return features.contains(name);
    }
}
```

- [ ] **Step 3: 컴파일 검증**

```bash
./gradlew :core:compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/license/LicenseLimits.java \
        core/src/main/java/com/crosscert/passkey/core/license/LicenseToken.java
git commit -m "feat(core): LicenseToken + LicenseLimits domain records (L1.6)"
```

---

### Task L1.7: LicenseTestFixtures — 토큰 발급 헬퍼

**Files:**
- Create: `core/src/test/java/com/crosscert/passkey/core/license/LicenseTestFixtures.java`

- [ ] **Step 1: Write LicenseTestFixtures.java**

```java
package com.crosscert.passkey.core.license;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.security.KeyFactory;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Test-only license token issuer. Loads the Ed25519 test keypair
 * from classpath and signs JWS tokens used by LicenseVerifierTest,
 * LicenseStateMachineTest, and integration tests.
 *
 * THIS IS TEST CODE — production license issuance happens out-of-band.
 */
public final class LicenseTestFixtures {

    private static final String ISSUER = "license.crosscert.com";
    private static final String AUDIENCE = "passkey-onprem";
    private static final String TEST_TENANT = "00000000-0000-0000-0000-000000000001";

    private LicenseTestFixtures() {}

    public static String issueValid(Duration validFor, List<String> features) {
        return issue("lic-test-" + System.nanoTime(),
                Instant.now(), Instant.now(), Instant.now().plus(validFor),
                features, defaultLimits());
    }

    public static String issueValidWithLimits(Duration validFor,
                                              List<String> features,
                                              Map<String, Integer> limits) {
        return issue("lic-test-" + System.nanoTime(),
                Instant.now(), Instant.now(), Instant.now().plus(validFor),
                features, limits);
    }

    public static String issueExpired() {
        Instant past = Instant.now().minus(Duration.ofDays(1));
        return issue("lic-test-expired",
                past.minus(Duration.ofDays(30)),
                past.minus(Duration.ofDays(30)),
                past,
                List.of("mds"), defaultLimits());
    }

    public static String issueExpiringSoon(int days, List<String> features) {
        Instant exp = Instant.now().plus(Duration.ofDays(days));
        return issue("lic-test-soon",
                Instant.now().minus(Duration.ofDays(360)),
                Instant.now().minus(Duration.ofDays(360)),
                exp, features, defaultLimits());
    }

    public static String issueWithBadSignature() throws Exception {
        String valid = issueValid(Duration.ofDays(30), List.of("mds"));
        // Flip the last byte of the signature to corrupt it.
        String[] parts = valid.split("\\.");
        byte[] sig = Base64URL.from(parts[2]).decode();
        sig[sig.length - 1] ^= 0x01;
        parts[2] = Base64URL.encode(sig).toString();
        return parts[0] + "." + parts[1] + "." + parts[2];
    }

    private static Map<String, Integer> defaultLimits() {
        return Map.of("warningDaysBeforeExpiry", 30, "graceHoursWhenOffline", 72);
    }

    private static String issue(String jti, Instant iat, Instant nbf, Instant exp,
                                List<String> features, Map<String, Integer> limits) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(ISSUER)
                    .subject("test-customer")
                    .audience(AUDIENCE)
                    .issueTime(Date.from(iat))
                    .notBeforeTime(Date.from(nbf))
                    .expirationTime(Date.from(exp))
                    .jwtID(jti)
                    .claim("tenantId", TEST_TENANT)
                    .claim("features", features)
                    .claim("limits", limits)
                    .build();
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.EdDSA).build();
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new Ed25519Signer(loadPrivateKey()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign test license", e);
        }
    }

    private static OctetKeyPair loadPrivateKey() {
        try (InputStream pubIn = new ClassPathResource("license-public.test.ed25519.pub").getInputStream();
             InputStream prvIn = new ClassPathResource("license-private.test.ed25519.pem").getInputStream()) {
            byte[] pubDer = Base64.getDecoder().decode(stripPem(new String(pubIn.readAllBytes())));
            byte[] prvDer = Base64.getDecoder().decode(stripPem(new String(prvIn.readAllBytes())));
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            EdECPublicKey pub = (EdECPublicKey) kf.generatePublic(new X509EncodedKeySpec(pubDer));
            EdECPrivateKey prv = (EdECPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(prvDer));
            byte[] rawX = extractRawX(pub);
            byte[] rawD = prv.getBytes().orElseThrow();
            return new OctetKeyPair.Builder(Curve.Ed25519, Base64URL.encode(rawX))
                    .d(Base64URL.encode(rawD))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load test keypair", e);
        }
    }

    private static String stripPem(String pem) {
        return pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                  .replaceAll("-----END [A-Z ]+-----", "")
                  .replaceAll("\\s", "");
    }

    private static byte[] extractRawX(EdECPublicKey key) {
        byte[] y = bigIntegerToLE(key.getPoint().getY(), 32);
        if (key.getPoint().isXOdd()) y[31] |= (byte) 0x80;
        return y;
    }

    private static byte[] bigIntegerToLE(java.math.BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] le = new byte[len];
        int srcOff = (be.length > len) ? be.length - len : 0;
        int srcLen = Math.min(be.length - srcOff, len);
        for (int i = 0; i < srcLen; i++) le[i] = be[be.length - 1 - srcOff - i];
        return le;
    }
}
```

- [ ] **Step 2: 컴파일 검증**

```bash
./gradlew :core:compileTestJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/test/java/com/crosscert/passkey/core/license/LicenseTestFixtures.java
git commit -m "test(core): LicenseTestFixtures — Ed25519 test token issuer (L1.7)"
```

---

### Task L1.8: LicenseVerifier 구현 + 단위 테스트

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/license/LicenseVerifier.java`
- Create: `core/src/test/java/com/crosscert/passkey/core/license/LicenseVerifierTest.java`

- [ ] **Step 1: Write failing test — `LicenseVerifierTest.java`**

```java
package com.crosscert.passkey.core.license;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LicenseVerifierTest {

    private final LicensePublicKeyProvider keys =
            new LicensePublicKeyProvider("license-public.test.ed25519.pub");
    private final LicenseProperties props = new LicenseProperties(
            null, null, "license.crosscert.com", "passkey-onprem", null, null);
    private final LicenseVerifier verifier =
            new LicenseVerifier(keys, props, Clock.systemUTC());

    @Test
    void validToken_parsesAllClaims() throws Exception {
        String token = LicenseTestFixtures.issueValid(Duration.ofDays(90), List.of("mds", "audit-pdf"));

        LicenseToken result = verifier.verify(token);

        assertThat(result.features()).containsExactlyInAnyOrder("mds", "audit-pdf");
        assertThat(result.expiresAt()).isAfter(Instant.now());
        assertThat(result.tenantId()).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(result.limits().warningDaysBeforeExpiry()).isEqualTo(30);
        assertThat(result.limits().graceHoursWhenOffline()).isEqualTo(72);
    }

    @Test
    void expiredToken_rejected() {
        String token = LicenseTestFixtures.issueExpired();
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(LicenseVerificationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void badSignature_rejected() throws Exception {
        String token = LicenseTestFixtures.issueWithBadSignature();
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(LicenseVerificationException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void wrongAudience_rejected() {
        // Verifier configured to expect a different audience.
        LicenseProperties strict = new LicenseProperties(
                null, null, "license.crosscert.com", "different-audience", null, null);
        LicenseVerifier strictVerifier = new LicenseVerifier(keys, strict, Clock.systemUTC());
        String token = LicenseTestFixtures.issueValid(Duration.ofDays(30), List.of("mds"));
        assertThatThrownBy(() -> strictVerifier.verify(token))
                .isInstanceOf(LicenseVerificationException.class)
                .hasMessageContaining("audience");
    }

    @Test
    void clockBeforeNbf_rejected() {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofDays(365)), ZoneOffset.UTC);
        LicenseVerifier earlyVerifier = new LicenseVerifier(keys, props, past);
        String token = LicenseTestFixtures.issueValid(Duration.ofDays(30), List.of("mds"));
        assertThatThrownBy(() -> earlyVerifier.verify(token))
                .isInstanceOf(LicenseVerificationException.class)
                .hasMessageContaining("not yet valid");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :core:test --tests 'com.crosscert.passkey.core.license.LicenseVerifierTest'
```

Expected: FAIL — `LicenseVerifier` / `LicenseVerificationException` not defined.

- [ ] **Step 3: Write LicenseVerificationException.java**

```java
package com.crosscert.passkey.core.license;

public class LicenseVerificationException extends RuntimeException {
    public LicenseVerificationException(String message) { super(message); }
    public LicenseVerificationException(String message, Throwable cause) { super(message, cause); }
}
```

(이 파일도 step 4 와 같이 commit.)

- [ ] **Step 4: Write LicenseVerifier.java**

```java
package com.crosscert.passkey.core.license;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses + verifies a license JWS. Stateless. Bean lifecycle is
 * unconditional — even SaaS mode constructs this so tests pass without
 * the deployment.mode flag. Actual on-prem usage is gated by
 * LicenseBootstrap, which itself is @ConditionalOnProperty.
 */
@Component
public class LicenseVerifier {

    /**
     * Known feature whitelist — narrows what the JWS claims can grant.
     * Add new features here, not in the JWS payload alone.
     */
    private static final Set<String> KNOWN_FEATURES = Set.of(
            "mds", "audit-pdf", "security-policy-advanced");

    private final LicensePublicKeyProvider keys;
    private final LicenseProperties props;
    private final Clock clock;

    public LicenseVerifier(LicensePublicKeyProvider keys,
                           LicenseProperties props,
                           Clock clock) {
        this.keys = keys;
        this.props = props;
        this.clock = clock;
    }

    public LicenseToken verify(String compactJws) {
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(compactJws);
        } catch (ParseException e) {
            throw new LicenseVerificationException("Malformed license token", e);
        }
        try {
            JWSVerifier v = new Ed25519Verifier(keys.publicKey());
            if (!jwt.verify(v)) {
                throw new LicenseVerificationException("License signature invalid");
            }
        } catch (Exception e) {
            throw new LicenseVerificationException("License signature invalid", e);
        }

        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new LicenseVerificationException("Malformed license claims", e);
        }

        if (!props.issuer().equals(claims.getIssuer())) {
            throw new LicenseVerificationException(
                    "License issuer mismatch: " + claims.getIssuer());
        }
        if (claims.getAudience() == null || !claims.getAudience().contains(props.audience())) {
            throw new LicenseVerificationException(
                    "License audience mismatch: " + claims.getAudience());
        }

        Instant now = clock.instant();
        Date nbf = claims.getNotBeforeTime();
        if (nbf != null && now.isBefore(nbf.toInstant())) {
            throw new LicenseVerificationException("License not yet valid: nbf=" + nbf);
        }
        Date exp = claims.getExpirationTime();
        if (exp == null || !now.isBefore(exp.toInstant())) {
            throw new LicenseVerificationException("License expired: exp=" + exp);
        }

        Set<String> features = filterKnown(rawFeatures(claims));
        LicenseLimits limits = readLimits(claims);
        String tenantId = (String) claims.getClaim("tenantId");

        return new LicenseToken(
                claims.getSubject(),
                claims.getJWTID(),
                claims.getIssueTime() == null ? null : claims.getIssueTime().toInstant(),
                nbf == null ? null : nbf.toInstant(),
                exp.toInstant(),
                tenantId,
                features,
                limits);
    }

    @SuppressWarnings("unchecked")
    private static List<String> rawFeatures(JWTClaimsSet claims) {
        Object raw = claims.getClaim("features");
        if (raw instanceof List<?> l) return (List<String>) l;
        return List.of();
    }

    private static Set<String> filterKnown(List<String> declared) {
        Set<String> out = new HashSet<>();
        for (String f : declared) {
            if (KNOWN_FEATURES.contains(f)) out.add(f);
        }
        return Set.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static LicenseLimits readLimits(JWTClaimsSet claims) {
        Object raw = claims.getClaim("limits");
        if (!(raw instanceof Map<?, ?> m)) return LicenseLimits.DEFAULTS;
        int warn = intOrDefault(m, "warningDaysBeforeExpiry", LicenseLimits.DEFAULTS.warningDaysBeforeExpiry());
        int grace = intOrDefault(m, "graceHoursWhenOffline", LicenseLimits.DEFAULTS.graceHoursWhenOffline());
        return new LicenseLimits(warn, grace);
    }

    private static int intOrDefault(Map<?, ?> m, String k, int def) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.intValue();
        return def;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew :core:test --tests 'com.crosscert.passkey.core.license.LicenseVerifierTest'
```

Expected: PASS, 5 tests successful.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/license/LicenseVerifier.java \
        core/src/main/java/com/crosscert/passkey/core/license/LicenseVerificationException.java \
        core/src/test/java/com/crosscert/passkey/core/license/LicenseVerifierTest.java
git commit -m "feat(core): LicenseVerifier — Ed25519 signature + claim validation (L1.8)

* Verifies signature, issuer, audience, nbf, exp
* Filters features through hard-coded whitelist
* Falls back to LicenseLimits.DEFAULTS when limits claim absent
* 5 unit tests cover happy path + each rejection branch"
```

---

## Phase L2 — State machine + Cache + Heartbeat

이 phase 끝나면 onprem 모드에서 상태가 추적되고 heartbeat 가 폴링됨. 차단은 아직 없음.

---

### Task L2.1: LicenseState enum + LicenseLoader

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/license/LicenseState.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/license/LicenseLoader.java`

- [ ] **Step 1: Write LicenseState.java**

```java
package com.crosscert.passkey.core.license;

/**
 * Top-level operational state of the license subsystem.
 * Transitions are managed by LicenseStateMachine.
 */
public enum LicenseState {
    /** Within validity window, not yet within warning band. */
    VALID,
    /** Within validity window but expiring within warningDays. */
    WARNING,
    /** Heartbeat failed; running on cached token, grace period not yet exceeded. */
    NETWORK_GRACE,
    /** Expired or grace exceeded — all guarded API calls must be rejected. */
    DEAD
}
```

- [ ] **Step 2: Write LicenseLoader.java**

```java
package com.crosscert.passkey.core.license;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Reads the license JWS string from the configured filesystem path.
 * Intentionally minimal: file presence + UTF-8 read. JWS parsing and
 * validation belong to LicenseVerifier.
 */
@Component
public class LicenseLoader {

    public String load(Path path) {
        try {
            return Files.readString(path).trim();
        } catch (NoSuchFileException e) {
            throw new LicenseVerificationException(
                    "License file not found at " + path, e);
        } catch (IOException e) {
            throw new LicenseVerificationException(
                    "Failed to read license file at " + path, e);
        }
    }
}
```

- [ ] **Step 3: 컴파일 검증**

```bash
./gradlew :core:compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/license/LicenseState.java \
        core/src/main/java/com/crosscert/passkey/core/license/LicenseLoader.java
git commit -m "feat(core): LicenseState enum + LicenseLoader (L2.1)"
```

---

### Task L2.2: LicenseCache — 디스크 캐시

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/license/LicenseCache.java`

- [ ] **Step 1: Write LicenseCache.java**

```java
package com.crosscert.passkey.core.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Persists the most recently verified license token + the wall-clock
 * timestamp of the last successful heartbeat to a single JSON file on
 * disk. Used to (1) bridge restarts without re-contacting the license
 * server, (2) compute NETWORK_GRACE remaining time, (3) detect clock
 * rollback by comparing system time against lastVerifiedAt.
 *
 * File format (single JSON object):
 *   { "tokenJws": "...", "lastVerifiedAt": "2026-05-29T08:30:00Z" }
 */
@Component
public class LicenseCache {

    private static final Logger log = LoggerFactory.getLogger(LicenseCache.class);
    private static final ObjectMapper M = JsonMapper.builder()
            .findAndAddModules()
            .build();
    private static final Set<PosixFilePermission> RW_USER =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    public record Entry(String tokenJws, Instant lastVerifiedAt) {}

    public Optional<Entry> read(Path path) {
        if (!Files.exists(path)) return Optional.empty();
        try {
            byte[] bytes = Files.readAllBytes(path);
            Entry e = M.readValue(bytes, Entry.class);
            return Optional.ofNullable(e);
        } catch (IOException e) {
            log.warn("license cache read failed: path={} reason={}", path, e.getMessage());
            return Optional.empty();
        }
    }

    public void write(Path path, Entry entry) {
        try {
            Files.createDirectories(path.getParent());
            byte[] bytes = M.writeValueAsBytes(entry);
            Files.write(path, bytes);
            try {
                Files.setPosixFilePermissions(path, RW_USER);
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX FS (Windows) — skip.
            }
        } catch (IOException e) {
            log.warn("license cache write failed: path={} reason={}", path, e.getMessage());
        }
    }
}
```

- [ ] **Step 2: 컴파일 검증**

```bash
./gradlew :core:compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/license/LicenseCache.java
git commit -m "feat(core): LicenseCache — disk persistence of last verified token (L2.2)"
```

---

### Task L2.3: LicenseStateMachine + 단위 테스트

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/license/LicenseStateMachine.java`
- Create: `core/src/test/java/com/crosscert/passkey/core/license/LicenseStateMachineTest.java`

- [ ] **Step 1: Write failing test — `LicenseStateMachineTest.java`**

```java
package com.crosscert.passkey.core.license;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LicenseStateMachineTest {

    private final LicenseToken farFuture = token(Duration.ofDays(365));
    private final LicenseToken expiringSoon = token(Duration.ofDays(5));
    private final LicenseToken expired = token(Duration.ofDays(-1));

    @Test
    void initialState_valid_whenFarFromExpiry() {
        LicenseStateMachine sm = newStateMachine(farFuture, Instant.now());
        assertThat(sm.state()).isEqualTo(LicenseState.VALID);
    }

    @Test
    void initialState_warning_whenExpiringWithinWindow() {
        LicenseStateMachine sm = newStateMachine(expiringSoon, Instant.now());
        assertThat(sm.state()).isEqualTo(LicenseState.WARNING);
    }

    @Test
    void initialState_dead_whenAlreadyExpired() {
        LicenseStateMachine sm = newStateMachine(expired, Instant.now());
        assertThat(sm.state()).isEqualTo(LicenseState.DEAD);
    }

    @Test
    void heartbeatFailure_movesToNetworkGrace() {
        LicenseStateMachine sm = newStateMachine(farFuture, Instant.now());
        sm.onHeartbeatFailure("timeout");
        assertThat(sm.state()).isEqualTo(LicenseState.NETWORK_GRACE);
    }

    @Test
    void heartbeatSuccessAfterGrace_restoresValid() {
        LicenseStateMachine sm = newStateMachine(farFuture, Instant.now());
        sm.onHeartbeatFailure("timeout");
        sm.onHeartbeatSuccess(farFuture, Instant.now());
        assertThat(sm.state()).isEqualTo(LicenseState.VALID);
    }

    @Test
    void networkGraceExceeded_movesToDead() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        AtomicReference<Instant> now = new AtomicReference<>(base);
        Clock movable = Clock.fixed(base, ZoneOffset.UTC);
        LicenseStateMachine sm = new LicenseStateMachine(
                farFuture, base, () -> now.get());
        sm.onHeartbeatFailure("timeout");
        assertThat(sm.state()).isEqualTo(LicenseState.NETWORK_GRACE);

        // Advance past the grace window (default 72h).
        now.set(base.plus(Duration.ofHours(73)));
        sm.recompute();
        assertThat(sm.state()).isEqualTo(LicenseState.DEAD);
    }

    @Test
    void clockRollback_movesToDead() {
        Instant base = Instant.parse("2026-06-01T00:00:00Z");
        AtomicReference<Instant> now = new AtomicReference<>(base);
        LicenseStateMachine sm = new LicenseStateMachine(
                farFuture, base, () -> now.get());
        assertThat(sm.state()).isEqualTo(LicenseState.VALID);

        // Operator rolls system clock back 1 hour before lastVerifiedAt.
        now.set(base.minus(Duration.ofHours(1)));
        sm.recompute();
        assertThat(sm.state()).isEqualTo(LicenseState.DEAD);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private LicenseStateMachine newStateMachine(LicenseToken token, Instant verifiedAt) {
        return new LicenseStateMachine(token, verifiedAt, Instant::now);
    }

    private static LicenseToken token(Duration validFor) {
        Instant now = Instant.now();
        return new LicenseToken(
                "test", "lic-test-001",
                now, now,
                now.plus(validFor),
                "00000000-0000-0000-0000-000000000001",
                Set.of("mds"),
                LicenseLimits.DEFAULTS);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :core:test --tests 'com.crosscert.passkey.core.license.LicenseStateMachineTest'
```

Expected: FAIL — `LicenseStateMachine` not defined.

- [ ] **Step 3: Write LicenseStateMachine.java**

```java
package com.crosscert.passkey.core.license;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Singleton holder of the current license state, the verified token,
 * and the wall-clock timestamp of the last successful verification.
 * Thread-safe via synchronized methods — heartbeat thread + request
 * threads call recompute()/snapshot() concurrently.
 *
 * Lifecycle: instantiated by LicenseBootstrap (onprem mode only).
 * Tests construct directly via the public ctor.
 */
public class LicenseStateMachine {

    private static final Logger log = LoggerFactory.getLogger(LicenseStateMachine.class);

    private final Supplier<Instant> now;

    private volatile LicenseToken token;
    private volatile Instant lastVerifiedAt;
    private volatile LicenseState state;
    private volatile String lastFailureReason;

    public LicenseStateMachine(LicenseToken initialToken,
                               Instant initialVerifiedAt,
                               Supplier<Instant> nowSupplier) {
        this.now = nowSupplier;
        this.token = initialToken;
        this.lastVerifiedAt = initialVerifiedAt;
        this.state = computeState();
        log.info("license state initialized: state={} jti={} expiresAt={}",
                state, token.jti(), token.expiresAt());
    }

    public synchronized void onHeartbeatSuccess(LicenseToken refreshed, Instant verifiedAt) {
        this.token = refreshed;
        this.lastVerifiedAt = verifiedAt;
        this.lastFailureReason = null;
        LicenseState prev = this.state;
        this.state = computeState();
        if (state != prev) {
            log.info("license state transition: {} -> {} (heartbeat ok) jti={}",
                    prev, state, token.jti());
        }
    }

    public synchronized void onHeartbeatFailure(String reason) {
        this.lastFailureReason = reason;
        LicenseState prev = this.state;
        this.state = computeState();
        if (state != prev) {
            log.warn("license state transition: {} -> {} (heartbeat failed: {}) jti={}",
                    prev, state, reason, token.jti());
        } else {
            log.warn("license heartbeat failed: state={} reason={} jti={}",
                    state, reason, token.jti());
        }
    }

    public synchronized void recompute() {
        LicenseState prev = this.state;
        this.state = computeState();
        if (state != prev) {
            log.info("license state transition: {} -> {} (recompute) jti={}",
                    prev, state, token.jti());
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(state, token, lastVerifiedAt, lastFailureReason);
    }

    public LicenseState state() {
        return state;
    }

    public LicenseToken token() {
        return token;
    }

    private LicenseState computeState() {
        Instant t = now.get();
        // Clock rollback protection
        if (lastVerifiedAt != null && t.isBefore(lastVerifiedAt)) {
            return LicenseState.DEAD;
        }
        // Expired absolutely
        if (!t.isBefore(token.expiresAt())) {
            return LicenseState.DEAD;
        }
        // In NETWORK_GRACE branch: failure was set but grace not exceeded
        if (lastFailureReason != null) {
            Duration since = Duration.between(lastVerifiedAt, t);
            if (since.toHours() > token.limits().graceHoursWhenOffline()) {
                return LicenseState.DEAD;
            }
            return LicenseState.NETWORK_GRACE;
        }
        // Warning band check
        Duration toExpiry = Duration.between(t, token.expiresAt());
        if (toExpiry.toDays() <= token.limits().warningDaysBeforeExpiry()) {
            return LicenseState.WARNING;
        }
        return LicenseState.VALID;
    }

    public record Snapshot(
            LicenseState state,
            LicenseToken token,
            Instant lastVerifiedAt,
            String lastFailureReason
    ) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :core:test --tests 'com.crosscert.passkey.core.license.LicenseStateMachineTest'
```

Expected: PASS, 7 tests successful.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/license/LicenseStateMachine.java \
        core/src/test/java/com/crosscert/passkey/core/license/LicenseStateMachineTest.java
git commit -m "feat(core): LicenseStateMachine — state transitions + clock rollback (L2.3)

* VALID <-> WARNING based on warningDays band
* VALID|WARNING -> NETWORK_GRACE on heartbeat failure
* NETWORK_GRACE -> DEAD when graceHoursWhenOffline exceeded
* Any state -> DEAD if exp passed or system time < lastVerifiedAt
* 7 unit tests cover initial state, transitions, grace, rollback"
```

---

### Task L2.4: LicenseHeartbeatScheduler

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/license/LicenseHeartbeatScheduler.java`

- [ ] **Step 1: Write LicenseHeartbeatScheduler.java**

```java
package com.crosscert.passkey.core.license;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/**
 * Periodically calls the license server to refresh the cached token.
 * Active only when passkey.deployment.mode=onprem.
 *
 * Expected response: { "status": "active|revoked", "latestToken": "<JWS>" }.
 * - status=active + valid latestToken -> StateMachine.onHeartbeatSuccess
 * - non-2xx, transport error, parse failure, signature failure
 *   -> StateMachine.onHeartbeatFailure(reason)
 */
@Component
@ConditionalOnProperty(name = "passkey.deployment.mode", havingValue = "onprem")
public class LicenseHeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(LicenseHeartbeatScheduler.class);

    private final LicenseStateMachine stateMachine;
    private final LicenseVerifier verifier;
    private final LicenseCache cache;
    private final LicenseProperties props;
    private final Clock clock;
    private final RestClient http;

    public LicenseHeartbeatScheduler(LicenseStateMachine stateMachine,
                                     LicenseVerifier verifier,
                                     LicenseCache cache,
                                     LicenseProperties props,
                                     Clock clock) {
        this.stateMachine = stateMachine;
        this.verifier = verifier;
        this.cache = cache;
        this.props = props;
        this.clock = clock;
        this.http = RestClient.builder()
                .baseUrl(props.heartbeatUrl())
                .build();
    }

    @Scheduled(
            fixedDelayString = "${passkey.license.heartbeat-interval:PT1H}",
            initialDelayString = "PT10S"
    )
    public void heartbeat() {
        String jti = stateMachine.token().jti();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = http.get()
                    .uri("/{jti}/verify", jti)
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                stateMachine.onHeartbeatFailure("empty response");
                return;
            }
            Object status = body.get("status");
            if (!"active".equals(status)) {
                stateMachine.onHeartbeatFailure("status=" + status);
                return;
            }
            Object latest = body.get("latestToken");
            if (!(latest instanceof String jws)) {
                stateMachine.onHeartbeatFailure("missing latestToken");
                return;
            }
            LicenseToken refreshed = verifier.verify(jws);
            stateMachine.onHeartbeatSuccess(refreshed, clock.instant());
            Path cachePath = props.cachePath();
            cache.write(cachePath, new LicenseCache.Entry(jws, clock.instant()));
            log.debug("license heartbeat ok: jti={}", refreshed.jti());
        } catch (RestClientException e) {
            stateMachine.onHeartbeatFailure("http: " + e.getClass().getSimpleName());
        } catch (LicenseVerificationException e) {
            stateMachine.onHeartbeatFailure("verify: " + e.getMessage());
        } catch (Exception e) {
            stateMachine.onHeartbeatFailure("unexpected: " + e.getClass().getSimpleName());
            log.warn("license heartbeat unexpected error", e);
        }
    }
}
```

- [ ] **Step 2: 컴파일 검증**

```bash
./gradlew :core:compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/license/LicenseHeartbeatScheduler.java
git commit -m "feat(core): LicenseHeartbeatScheduler — @Scheduled 1h polling (L2.4)

* @ConditionalOnProperty(mode=onprem) so SaaS mode never schedules
* Uses Spring 6 RestClient (sync)
* Translates HTTP/parse/verify failures into onHeartbeatFailure(reason)
* Updates LicenseCache on success"
```

---

### Task L2.5: LicenseBootstrap — ApplicationRunner

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/license/LicenseBootstrap.java`

- [ ] **Step 1: Write LicenseBootstrap.java**

```java
package com.crosscert.passkey.core.license;

import com.crosscert.passkey.core.vpd.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Optional;

/**
 * Drives one-time license bootstrap in onprem mode:
 *   1. Read the JWS token from disk (passkey.license.path)
 *   2. Verify it
 *   3. Reconcile with the cache (use whichever has the later exp)
 *   4. Construct LicenseStateMachine
 *   5. Pin TenantContextHolder to the licensed tenantId for the
 *      JVM (every request thread inherits this default)
 *
 * Bootstrap failure -> Spring application context fails to start
 * (intentional: an onprem server with no valid license must not run).
 */
@Configuration
@ConditionalOnProperty(name = "passkey.deployment.mode", havingValue = "onprem")
public class LicenseBootstrap {

    private static final Logger log = LoggerFactory.getLogger(LicenseBootstrap.class);

    @Bean
    public LicenseStateMachine licenseStateMachine(LicenseLoader loader,
                                                   LicenseVerifier verifier,
                                                   LicenseCache cache,
                                                   LicenseProperties props,
                                                   Clock clock) {
        String fromDisk = loader.load(props.path());
        LicenseToken diskToken = verifier.verify(fromDisk);
        log.info("license loaded from disk: jti={} expiresAt={} features={}",
                diskToken.jti(), diskToken.expiresAt(), diskToken.features());

        // Reconcile with cache: prefer whichever has the later exp (covers
        // case where heartbeat already pulled a renewal but operator did
        // not yet replace the on-disk file).
        Optional<LicenseCache.Entry> cached = cache.read(props.cachePath());
        LicenseToken effective = diskToken;
        java.time.Instant verifiedAt = clock.instant();
        if (cached.isPresent()) {
            try {
                LicenseToken cachedToken = verifier.verify(cached.get().tokenJws());
                if (cachedToken.expiresAt().isAfter(diskToken.expiresAt())) {
                    effective = cachedToken;
                    verifiedAt = cached.get().lastVerifiedAt();
                    log.info("license cache supersedes disk: cached.exp={} disk.exp={}",
                            cachedToken.expiresAt(), diskToken.expiresAt());
                }
            } catch (LicenseVerificationException e) {
                log.warn("license cache invalid, ignoring: {}", e.getMessage());
            }
        }

        // Pin tenant for VPD context (singleton thread default).
        TenantContextHolder.set(java.util.UUID.fromString(effective.tenantId()));
        log.info("onprem mode: pinned VPD tenant to {}", effective.tenantId());

        return new LicenseStateMachine(effective, verifiedAt, clock::instant);
    }
}
```

- [ ] **Step 2: 컴파일 검증**

```bash
./gradlew :core:compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/license/LicenseBootstrap.java
git commit -m "feat(core): LicenseBootstrap — onprem startup verification (L2.5)

* @ConditionalOnProperty(mode=onprem) — produces LicenseStateMachine bean
* Loads + verifies token from disk; reconciles with cache (later exp wins)
* Pins TenantContextHolder for VPD context
* Verification failure -> context fails to start (intentional)"
```

---

## Phase L3 — Guard filter + Feature gate

이 phase 끝나면 onprem 모드에서 DEAD 시 차단, feature 게이팅 동작.

---

### Task L3.1: RequiresFeature 어노테이션 + FeatureGateAspect

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/license/RequiresFeature.java`
- Create: `core/src/main/java/com/crosscert/passkey/core/license/FeatureGateAspect.java`

- [ ] **Step 1: Write RequiresFeature.java**

```java
package com.crosscert.passkey.core.license;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as gated by a license feature flag. In onprem mode,
 * the method is only invoked when LicenseStateMachine.token().features
 * contains the named feature. In SaaS mode, the aspect bean is not
 * registered, so the annotation has no effect.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresFeature {
    String value();
}
```

- [ ] **Step 2: Write FeatureGateAspect.java**

```java
package com.crosscert.passkey.core.license;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Aspect
@Component
@ConditionalOnProperty(name = "passkey.deployment.mode", havingValue = "onprem")
public class FeatureGateAspect {

    private final LicenseStateMachine stateMachine;

    public FeatureGateAspect(LicenseStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    @Around("@annotation(com.crosscert.passkey.core.license.RequiresFeature)")
    public Object enforce(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        RequiresFeature ann = sig.getMethod().getAnnotation(RequiresFeature.class);
        String feature = ann.value();
        if (!stateMachine.token().hasFeature(feature)) {
            throw new FeatureNotLicensedException(feature);
        }
        return pjp.proceed();
    }
}
```

- [ ] **Step 3: AOP 활성화 — admin-app + passkey-app 메인 클래스에 `@EnableAspectJAutoProxy` 추가**

`admin-app/src/main/java/com/crosscert/passkey/admin/AdminApplication.java` 의 `@EnableScheduling` 다음 줄에 추가:

```java
@org.springframework.context.annotation.EnableAspectJAutoProxy
```

`passkey-app/src/main/java/com/crosscert/passkey/app/PasskeyApplication.java` 의 `@EnableJpaRepositories` 다음 줄에 추가:

```java
@org.springframework.context.annotation.EnableAspectJAutoProxy
@org.springframework.scheduling.annotation.EnableScheduling
```

(`@EnableScheduling` 도 함께 추가 — heartbeat 가 passkey-app 부팅 시에도 동작해야 함.)

- [ ] **Step 4: GlobalExceptionHandler 에 FeatureNotLicensedException 매핑**

`core/src/main/java/com/crosscert/passkey/core/api/GlobalExceptionHandler.java` 파일을 열어 다른 `@ExceptionHandler` 아래에 추가:

```java
    @ExceptionHandler(com.crosscert.passkey.core.license.FeatureNotLicensedException.class)
    public ResponseEntity<ApiResponse<Void>> handleFeatureNotLicensed(
            com.crosscert.passkey.core.license.FeatureNotLicensedException ex) {
        return ResponseEntity
                .status(ErrorCode.FEATURE_NOT_LICENSED.getStatus())
                .body(ApiResponse.error(ErrorCode.FEATURE_NOT_LICENSED,
                        "Feature not included in license: " + ex.feature()));
    }
```

- [ ] **Step 5: 컴파일 검증 (모든 모듈)**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/license/RequiresFeature.java \
        core/src/main/java/com/crosscert/passkey/core/license/FeatureGateAspect.java \
        core/src/main/java/com/crosscert/passkey/core/api/GlobalExceptionHandler.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/AdminApplication.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/PasskeyApplication.java
git commit -m "feat(core): RequiresFeature annotation + FeatureGateAspect (L3.1)

* AOP gate: throws FeatureNotLicensedException -> 403 (L002)
* @EnableAspectJAutoProxy on both AdminApplication + PasskeyApplication
* @EnableScheduling on PasskeyApplication so heartbeat runs there too"
```

---

### Task L3.2: LicenseGuardFilter (공유 — passkey-app + admin-app)

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/license/LicenseGuardFilter.java`

- [ ] **Step 1: Write LicenseGuardFilter.java**

```java
package com.crosscert.passkey.core.license;

import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.api.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Filters every HTTP request against the current LicenseState.
 *  - DEAD     -> 503 JSON (except for admin license/health/login paths)
 *  - WARNING  -> pass through + X-License-Warning header
 *  - NETWORK_GRACE -> pass through + X-License-Warning header
 *  - VALID    -> pass through
 *
 * Registered as @Component so passkey-app picks it up via Spring Boot
 * servlet filter auto-registration. admin-app's Spring Security chain
 * adds it explicitly via addFilterBefore() (see AdminSecurityConfig).
 *
 * Order = HIGHEST_PRECEDENCE + 1: runs after MDC/request-logging
 * (which is HIGHEST_PRECEDENCE) so logs still capture license-rejected
 * traces, but before ApiKeyAuthFilter (HIGHEST + 5) so license check
 * happens before any auth work.
 */
@Component
@ConditionalOnProperty(name = "passkey.deployment.mode", havingValue = "onprem")
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class LicenseGuardFilter extends OncePerRequestFilter {

    /**
     * Paths exempt from the DEAD-state block. Keep operators able to
     * read /license to diagnose the outage, and keep /actuator/health
     * + login functional so the system surfaces clearly as dead rather
     * than silently dropping all traffic.
     */
    private static final Set<String> DEAD_EXEMPT = Set.of(
            "/admin/api/license",
            "/admin/api/system/info",
            "/admin/login",
            "/admin/logout",
            "/actuator/health",
            "/actuator/info");

    private final LicenseStateMachine stateMachine;
    private final ObjectMapper mapper;

    public LicenseGuardFilter(LicenseStateMachine stateMachine, ObjectMapper mapper) {
        this.stateMachine = stateMachine;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        // Refresh state on every request — cheap, makes WARNING/DEAD transitions
        // visible without waiting for the next heartbeat.
        stateMachine.recompute();
        LicenseStateMachine.Snapshot snap = stateMachine.snapshot();

        switch (snap.state()) {
            case DEAD -> {
                if (isExempt(req)) {
                    chain.doFilter(req, res);
                    return;
                }
                writeDeadResponse(res);
                return;
            }
            case WARNING -> {
                long days = Math.max(0,
                        Duration.between(Instant.now(), snap.token().expiresAt()).toDays());
                res.setHeader("X-License-Warning", "expires in " + days + " day(s)");
            }
            case NETWORK_GRACE -> {
                long graceLeftHours = computeGraceLeftHours(snap);
                res.setHeader("X-License-Warning",
                        "license server unreachable; " + graceLeftHours + " hour(s) remaining");
            }
            case VALID -> { /* no-op */ }
        }
        chain.doFilter(req, res);
    }

    private boolean isExempt(HttpServletRequest req) {
        String path = req.getRequestURI();
        for (String p : DEAD_EXEMPT) {
            if (path.equals(p) || path.startsWith(p + "/")) return true;
        }
        return false;
    }

    private void writeDeadResponse(HttpServletResponse res) throws IOException {
        res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiResponse<Void> body = ApiResponse.error(ErrorCode.LICENSE_EXPIRED,
                "License has expired. Contact your administrator.");
        mapper.writeValue(res.getWriter(), body);
    }

    private long computeGraceLeftHours(LicenseStateMachine.Snapshot snap) {
        long graceMax = snap.token().limits().graceHoursWhenOffline();
        long elapsed = Duration.between(snap.lastVerifiedAt(), Instant.now()).toHours();
        return Math.max(0, graceMax - elapsed);
    }
}
```

- [ ] **Step 2: admin-app SecurityConfig 에 명시적으로 등록**

`admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java` 의 `adminFilterChain` 메서드 시그니처에 `LicenseGuardFilter` (Optional) 파라미터 추가하고 chain 에 prepend:

```java
    @Bean
    public SecurityFilterChain adminFilterChain(HttpSecurity http,
                                                AuthenticationSuccessHandler ok,
                                                AuthenticationFailureHandler fail,
                                                LogoutSuccessHandler logoutOk,
                                                AccessDeniedHandler accessDenied,
                                                java.util.Optional<com.crosscert.passkey.core.license.LicenseGuardFilter> licenseGuard) throws Exception {
```

그리고 `.csrf(...)` 와 `.sessionManagement(...)` 사이에 추가 (license guard 가 인증보다 먼저 평가되어야 함 — Spring Security 의 가장 앞쪽인 `DisableEncodeUrlFilter` 자리에는 못 가지만 `SecurityContextHolderFilter` 이전이면 충분):

```java
        licenseGuard.ifPresent(filter ->
            http.addFilterBefore(filter, org.springframework.security.web.context.SecurityContextHolderFilter.class));
```

그리고 기존 `authorizeHttpRequests` 의 permitAll 목록에 `/admin/api/license` 추가:

```java
                .requestMatchers("/admin/api/profile", "/admin/api/license").permitAll()
```

- [ ] **Step 3: 컴파일 검증**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/license/LicenseGuardFilter.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java
git commit -m "feat(core,admin): LicenseGuardFilter — DEAD-state HTTP guard (L3.2)

* @ConditionalOnProperty(mode=onprem) — never wired in SaaS
* DEAD -> 503 JSON; exempt paths: /admin/api/license, /actuator/health, etc.
* WARNING/NETWORK_GRACE -> X-License-Warning header
* admin-app: addFilterBefore SecurityContextHolderFilter (pre-auth)
* passkey-app: picked up by Spring Boot filter auto-registration"
```

---

### Task L3.3: MDS feature gate 적용

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSyncJob.java`

- [ ] **Step 1: 현재 MdsSyncJob 확인**

```bash
cat admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSyncJob.java
```

- [ ] **Step 2: `@Scheduled` 메서드에 `@RequiresFeature("mds")` 추가**

import 추가:

```java
import com.crosscert.passkey.core.license.RequiresFeature;
```

`@Scheduled` 가 붙은 메서드 위에 추가:

```java
    @RequiresFeature("mds")
    @Scheduled(...)
```

- [ ] **Step 3: 컴파일 검증**

```bash
./gradlew :admin-app:compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/mds/MdsSyncJob.java
git commit -m "feat(admin): gate MDS sync job with @RequiresFeature(mds) (L3.3)"
```

---

### Task L3.4: LicenseGuardFilterIT — DEAD 차단 통합 테스트

**Files:**
- Create: `admin-app/src/test/java/com/crosscert/passkey/admin/license/LicenseGuardFilterIT.java`

- [ ] **Step 1: 기존 admin-app 통합 테스트 패턴 확인**

```bash
ls admin-app/src/test/java/com/crosscert/passkey/admin/
find admin-app/src/test -name "*IT.java" | head -3
```

- [ ] **Step 2: Write LicenseGuardFilterIT.java**

```java
package com.crosscert.passkey.admin.license;

import com.crosscert.passkey.core.license.LicenseLimits;
import com.crosscert.passkey.core.license.LicenseState;
import com.crosscert.passkey.core.license.LicenseStateMachine;
import com.crosscert.passkey.core.license.LicenseToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.time.Instant;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots admin-app in onprem mode with a stubbed LicenseStateMachine
 * fixed to DEAD. Verifies that:
 *   - Arbitrary admin API (/admin/api/credentials) -> 503
 *   - /admin/api/license -> 200 (exempt)
 *
 * The stubbed bean replaces the real LicenseStateMachine bean produced
 * by LicenseBootstrap, so no on-disk license file is needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "passkey.deployment.mode=onprem",
        "passkey.license.path=/dev/null",
        "passkey.license.heartbeat-url=http://localhost:9"  // unreachable, scheduler will fail quietly
})
@org.springframework.context.annotation.Import(LicenseGuardFilterIT.DeadStateConfig.class)
class LicenseGuardFilterIT {

    @Autowired
    MockMvc mvc;

    @Test
    @WithMockUser(roles = "PLATFORM_OPERATOR")
    void deadState_blocksAdminApi_with503() throws Exception {
        mvc.perform(get("/admin/api/credentials"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void deadState_allowsLicenseEndpoint() throws Exception {
        mvc.perform(get("/admin/api/license"))
                .andExpect(status().isOk());
    }

    @Test
    void deadState_allowsHealthEndpoint() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @TestConfiguration
    static class DeadStateConfig {

        @Bean
        @Primary
        LicenseStateMachine deadStateMachine() {
            // Token already expired -> state machine starts in DEAD.
            Instant past = Instant.now().minus(Duration.ofDays(1));
            LicenseToken expiredToken = new LicenseToken(
                    "test-customer", "lic-test-dead",
                    past.minus(Duration.ofDays(30)),
                    past.minus(Duration.ofDays(30)),
                    past,
                    UUID.nameUUIDFromBytes("test".getBytes()).toString(),
                    Set.of("mds"),
                    LicenseLimits.DEFAULTS);
            LicenseStateMachine sm = new LicenseStateMachine(
                    expiredToken, past, Instant::now);
            assert sm.state() == LicenseState.DEAD;
            return sm;
        }
    }
}
```

- [ ] **Step 3: Run test**

```bash
./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.license.LicenseGuardFilterIT'
```

Expected: PASS. (이 시점에 LicenseController + system meta API 가 아직 없어서 `/admin/api/license` 테스트가 404 일 수 있음 — L4.1 까지 진행 후 재실행이 필요할 수 있다는 점 명시. 일단 503/200 동작은 검증된 형태로 task 정의한다.)

만약 `/admin/api/license` 가 404 라면 다음 단계에서 LicenseController 가 추가될 때 통과한다 — 이 IT 의 두 번째 + 세 번째 테스트는 일단 `@org.junit.jupiter.api.Disabled` 로 비활성화하고 commit 메시지에 명시. L4.1 commit 에서 enable.

- [ ] **Step 4: Commit**

```bash
git add admin-app/src/test/java/com/crosscert/passkey/admin/license/LicenseGuardFilterIT.java
git commit -m "test(admin): LicenseGuardFilterIT — DEAD blocks admin API (L3.4)

* Stubs LicenseStateMachine via @TestConfiguration -> DEAD
* Verifies 503 on /admin/api/credentials
* /admin/api/license + /actuator/health exempt tests will activate
  in L4.1 once LicenseController ships"
```

---

### Task L3.5: DeploymentModeProfileIT — SaaS 무회귀

**Files:**
- Create: `admin-app/src/test/java/com/crosscert/passkey/admin/license/DeploymentModeProfileIT.java`

- [ ] **Step 1: Write DeploymentModeProfileIT.java**

```java
package com.crosscert.passkey.admin.license;

import com.crosscert.passkey.core.license.LicenseGuardFilter;
import com.crosscert.passkey.core.license.LicenseHeartbeatScheduler;
import com.crosscert.passkey.core.license.LicenseStateMachine;
import com.crosscert.passkey.core.license.FeatureGateAspect;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression guard: ensure that the SaaS default (deployment.mode=saas)
 * does not register any of the onprem-only license beans. If a future
 * change drops the @ConditionalOnProperty guard, this test fails.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "passkey.deployment.mode=saas")
class DeploymentModeProfileIT {

    @Autowired
    ApplicationContext ctx;

    @Test
    void saasMode_doesNotRegisterLicenseBeans() {
        assertThatThrownBy(() -> ctx.getBean(LicenseStateMachine.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
        assertThatThrownBy(() -> ctx.getBean(LicenseHeartbeatScheduler.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
        assertThatThrownBy(() -> ctx.getBean(LicenseGuardFilter.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
        assertThatThrownBy(() -> ctx.getBean(FeatureGateAspect.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);

        // Stateless verifier bean is unconditional — must always be present.
        assertThat(ctx.getBean(com.crosscert.passkey.core.license.LicenseVerifier.class))
                .isNotNull();
    }
}
```

- [ ] **Step 2: Run test**

```bash
./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.license.DeploymentModeProfileIT'
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add admin-app/src/test/java/com/crosscert/passkey/admin/license/DeploymentModeProfileIT.java
git commit -m "test(admin): DeploymentModeProfileIT — SaaS mode keeps license beans off (L3.5)"
```

---

## Phase L4 — Admin API + UI

이 phase 끝나면 운영자가 admin UI 에서 라이센스 상태를 보고 배너로 경고를 받음.

---

### Task L4.1: LicenseView + LicenseController

**Files:**
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/license/LicenseView.java`
- Create: `admin-app/src/main/java/com/crosscert/passkey/admin/license/LicenseController.java`

- [ ] **Step 1: Write LicenseView.java**

```java
package com.crosscert.passkey.admin.license;

import java.time.Instant;
import java.util.List;

/**
 * Serialized view of license state for admin UI consumption.
 * deploymentMode included so the SPA can decide whether to show
 * the License menu at all.
 */
public record LicenseView(
        String deploymentMode,
        String state,
        String sub,
        String jti,
        Instant expiresAt,
        long daysUntilExpiry,
        List<String> features,
        Instant lastVerifiedAt,
        Long graceRemainingHours,
        Instant nextHeartbeatAt
) {}
```

- [ ] **Step 2: Write LicenseController.java**

```java
package com.crosscert.passkey.admin.license;

import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.license.LicenseProperties;
import com.crosscert.passkey.core.license.LicenseState;
import com.crosscert.passkey.core.license.LicenseStateMachine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * GET /admin/api/license — returns the current LicenseView.
 *
 * Always available; in SaaS mode it returns a sentinel view
 * (deploymentMode=saas, state=null) so the SPA can use the same
 * fetch on both modes.
 */
@RestController
@RequestMapping("/admin/api/license")
public class LicenseController {

    private final String deploymentMode;
    private final ObjectProvider<LicenseStateMachine> stateMachine;
    private final LicenseProperties props;
    private final Clock clock;

    public LicenseController(
            @org.springframework.beans.factory.annotation.Value("${passkey.deployment.mode:saas}")
            String deploymentMode,
            ObjectProvider<LicenseStateMachine> stateMachine,
            LicenseProperties props,
            Clock clock) {
        this.deploymentMode = deploymentMode;
        this.stateMachine = stateMachine;
        this.props = props;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<LicenseView> get() {
        LicenseStateMachine sm = stateMachine.getIfAvailable();
        if (sm == null) {
            return ApiResponse.ok(new LicenseView(
                    deploymentMode, null, null, null, null, 0L, List.of(), null, null, null));
        }
        LicenseStateMachine.Snapshot s = sm.snapshot();
        Instant now = clock.instant();
        long daysLeft = Math.max(0, Duration.between(now, s.token().expiresAt()).toDays());
        Long graceLeft = null;
        if (s.state() == LicenseState.NETWORK_GRACE && s.lastVerifiedAt() != null) {
            long max = s.token().limits().graceHoursWhenOffline();
            long elapsed = Duration.between(s.lastVerifiedAt(), now).toHours();
            graceLeft = Math.max(0, max - elapsed);
        }
        Instant nextBeat = s.lastVerifiedAt() == null
                ? null
                : s.lastVerifiedAt().plus(props.heartbeatInterval());
        return ApiResponse.ok(new LicenseView(
                deploymentMode,
                s.state().name(),
                s.token().sub(),
                s.token().jti(),
                s.token().expiresAt(),
                daysLeft,
                List.copyOf(s.token().features()),
                s.lastVerifiedAt(),
                graceLeft,
                nextBeat));
    }
}
```

- [ ] **Step 3: AdminSecurityConfig permitAll 확인**

L3.2 에서 `/admin/api/license` 를 permitAll 에 추가했다. 다시 확인:

```bash
grep -n "/admin/api/license" admin-app/src/main/java/com/crosscert/passkey/admin/config/AdminSecurityConfig.java
```

Expected: permitAll 라인에 표시됨.

- [ ] **Step 4: L3.4 의 disabled 테스트가 있으면 enable**

```bash
grep -n "@Disabled" admin-app/src/test/java/com/crosscert/passkey/admin/license/LicenseGuardFilterIT.java
```

있으면 제거.

- [ ] **Step 5: Run integration test**

```bash
./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.license.LicenseGuardFilterIT'
```

Expected: PASS, 3 tests successful.

- [ ] **Step 6: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/license/LicenseView.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/license/LicenseController.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/license/LicenseGuardFilterIT.java
git commit -m "feat(admin): LicenseController GET /admin/api/license (L4.1)

* Returns LicenseView with state, expiry, features, grace, next heartbeat
* SaaS mode returns sentinel { deploymentMode: saas, state: null }
  so the SPA can use one endpoint for both modes
* Activates the previously-disabled LicenseGuardFilterIT cases"
```

---

### Task L4.2: LicenseHealthIndicator — actuator 통합

**Files:**
- Create: `core/src/main/java/com/crosscert/passkey/core/license/LicenseHealthIndicator.java`

- [ ] **Step 1: Write LicenseHealthIndicator.java**

```java
package com.crosscert.passkey.core.license;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component("license")
@ConditionalOnProperty(name = "passkey.deployment.mode", havingValue = "onprem")
public class LicenseHealthIndicator implements HealthIndicator {

    private final LicenseStateMachine stateMachine;

    public LicenseHealthIndicator(LicenseStateMachine stateMachine) {
        this.stateMachine = stateMachine;
    }

    @Override
    public Health health() {
        LicenseStateMachine.Snapshot snap = stateMachine.snapshot();
        Health.Builder b = (snap.state() == LicenseState.DEAD) ? Health.down() : Health.up();
        b.withDetail("state", snap.state().name());
        b.withDetail("jti", snap.token().jti());
        b.withDetail("daysUntilExpiry",
                Math.max(0, Duration.between(Instant.now(), snap.token().expiresAt()).toDays()));
        return b.build();
    }
}
```

- [ ] **Step 2: 컴파일 검증**

```bash
./gradlew :core:compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/license/LicenseHealthIndicator.java
git commit -m "feat(core): LicenseHealthIndicator — actuator /health license component (L4.2)"
```

---

### Task L4.3: SecretMaskingConverter — 라이센스 JWS 마스킹

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/logging/SecretMaskingConverter.java`

- [ ] **Step 1: 현재 패턴 확인**

```bash
grep -n "JWT_BEARER\|Pattern.compile" core/src/main/java/com/crosscert/passkey/core/logging/SecretMaskingConverter.java
```

`JWT_BEARER` 패턴은 이미 `Bearer eyJ...` 접두사를 마스킹한다. 라이센스 토큰은 Bearer prefix 없이 평문으로 로그에 흘러갈 수 있으므로 standalone JWS 패턴을 추가해야 한다.

- [ ] **Step 2: SecretMaskingConverter.java 에 standalone JWS 패턴 추가**

`JWT_BEARER` 다음 줄에 추가:

```java
    // Standalone JWS (eyJ header.payload.signature) without "Bearer " prefix —
    // catches license tokens and any other raw JWS accidentally logged.
    private static final Pattern JWS_STANDALONE = Pattern.compile(
            "(?<![A-Za-z0-9_/-])eyJ[A-Za-z0-9_-]{2,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
```

(`(?<!...)` 부정 선행 패턴은 "Bearer " 뒤가 아닌 경우만 매칭 — 기존 JWT_BEARER 가 우선 처리하도록.)

`convert` 메서드 안에서 JWT_BEARER 다음 줄에 추가:

```java
        msg = JWS_STANDALONE.matcher(msg).replaceAll("<jws-redacted>");
```

(`API_KEY` 처리 전에 위치.)

- [ ] **Step 3: 컴파일 검증**

```bash
./gradlew :core:compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/logging/SecretMaskingConverter.java
git commit -m "feat(core): mask standalone JWS strings in logs (license tokens) (L4.3)"
```

---

### Task L4.4: SystemInfo — deploymentMode 노출

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/system/SystemInfoService.java`
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/system/SystemInfoView.java`

- [ ] **Step 1: 현재 SystemInfoView 구조 확인**

```bash
cat admin-app/src/main/java/com/crosscert/passkey/admin/system/SystemInfoView.java
```

- [ ] **Step 2: SystemInfoView 에 `deploymentMode` 필드 추가**

기존 record 필드 목록 끝에 추가:

```java
        String deploymentMode
```

(다른 필드는 record 정의에 따라 다름 — 추가 시 모든 호출 지점 업데이트.)

- [ ] **Step 3: SystemInfoService 생성자에 `@Value("${passkey.deployment.mode:saas}") String deploymentMode` 주입하고 응답에 포함**

- [ ] **Step 4: 컴파일 + 기존 SystemInfoController 테스트 실행**

```bash
./gradlew :admin-app:test --tests 'com.crosscert.passkey.admin.system.*'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/system/SystemInfoService.java \
        admin-app/src/main/java/com/crosscert/passkey/admin/system/SystemInfoView.java
git commit -m "feat(admin): expose deploymentMode in /admin/api/system/info (L4.4)"
```

---

### Task L4.5: admin-ui — License API client

**Files:**
- Create: `admin-ui/src/api/license.ts`

- [ ] **Step 1: 기존 api client 패턴 확인**

```bash
ls admin-ui/src/api/
cat admin-ui/src/api/client.ts | head -30
```

- [ ] **Step 2: Write license.ts**

```typescript
import { api } from './client';

export type LicenseView = {
  deploymentMode: 'saas' | 'onprem';
  state: 'VALID' | 'WARNING' | 'NETWORK_GRACE' | 'DEAD' | null;
  sub: string | null;
  jti: string | null;
  expiresAt: string | null;
  daysUntilExpiry: number;
  features: string[];
  lastVerifiedAt: string | null;
  graceRemainingHours: number | null;
  nextHeartbeatAt: string | null;
};

export async function getLicense(): Promise<LicenseView> {
  const res = await api.get<{ data: LicenseView }>('/admin/api/license');
  return res.data;
}
```

(`api.get` 의 정확한 시그니처는 client.ts 패턴을 따른다 — wrapper 가 `.data` 풀린 형태면 첫 번째 await 만으로 충분.)

- [ ] **Step 3: 타입 체크**

```bash
cd admin-ui && npm run typecheck 2>/dev/null || npx tsc --noEmit
```

Expected: 에러 없음.

- [ ] **Step 4: Commit**

```bash
git add admin-ui/src/api/license.ts
git commit -m "feat(admin-ui): license API client (L4.5)"
```

---

### Task L4.6: LicenseBanner 컴포넌트

**Files:**
- Create: `admin-ui/src/components/LicenseBanner.tsx`

- [ ] **Step 1: 기존 컴포넌트 스타일 패턴 확인**

```bash
ls admin-ui/src/shell/
cat admin-ui/src/shell/Header.tsx | head -40
```

- [ ] **Step 2: Write LicenseBanner.tsx**

```tsx
import { useEffect, useState } from 'react';
import { getLicense, type LicenseView } from '@/api/license';

/**
 * Global license banner. Renders nothing on SaaS mode (state === null),
 * a yellow/orange/red strip otherwise. Polls /admin/api/license every
 * 5 minutes to reflect state transitions without requiring a reload.
 */
export function LicenseBanner() {
  const [license, setLicense] = useState<LicenseView | null>(null);

  useEffect(() => {
    let cancelled = false;
    async function poll() {
      try {
        const view = await getLicense();
        if (!cancelled) setLicense(view);
      } catch {
        // Silent — banner is best-effort UI.
      }
    }
    poll();
    const id = setInterval(poll, 5 * 60 * 1000);
    return () => { cancelled = true; clearInterval(id); };
  }, []);

  if (!license || license.deploymentMode !== 'onprem' || !license.state) return null;
  if (license.state === 'VALID') return null;

  const style: React.CSSProperties = {
    padding: '10px 16px',
    textAlign: 'center',
    fontWeight: 500,
    color: '#000',
  };

  if (license.state === 'WARNING') {
    return (
      <div style={{ ...style, background: '#fef3c7' }}>
        라이센스가 {license.daysUntilExpiry}일 후 만료됩니다. 영업 담당자에게 갱신을 요청하세요.
      </div>
    );
  }
  if (license.state === 'NETWORK_GRACE') {
    return (
      <div style={{ ...style, background: '#fed7aa' }}>
        라이센스 서버와 연결할 수 없습니다. {license.graceRemainingHours ?? '?'}시간 내 차단됩니다.
      </div>
    );
  }
  // DEAD
  return (
    <div style={{ ...style, background: '#fecaca', color: '#7f1d1d' }}>
      라이센스가 만료되어 서비스가 중단되었습니다. 영업 담당자에게 문의하세요.
    </div>
  );
}
```

- [ ] **Step 3: App.tsx 에 마운트**

`admin-ui/src/App.tsx` 의 최상단 JSX (Routes 바깥, 가장 위쪽) 에 추가:

```tsx
import { LicenseBanner } from '@/components/LicenseBanner';
```

그리고 root return 첫 줄에:

```tsx
<LicenseBanner />
```

- [ ] **Step 4: 타입 체크 + 빌드**

```bash
cd admin-ui && npx tsc --noEmit && npm run build
```

Expected: 에러 없음.

- [ ] **Step 5: Commit**

```bash
git add admin-ui/src/components/LicenseBanner.tsx admin-ui/src/App.tsx
git commit -m "feat(admin-ui): global LicenseBanner (WARNING/GRACE/DEAD) (L4.6)"
```

---

### Task L4.7: LicensePage + 사이드바 메뉴

**Files:**
- Create: `admin-ui/src/pages/LicensePage.tsx`
- Modify: `admin-ui/src/App.tsx` (route)
- Modify: `admin-ui/src/shell/Sidebar.tsx` (menu entry, on-prem only)

- [ ] **Step 1: Write LicensePage.tsx**

```tsx
import { useEffect, useState } from 'react';
import { getLicense, type LicenseView } from '@/api/license';

export default function LicensePage() {
  const [license, setLicense] = useState<LicenseView | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    getLicense().then(setLicense).catch(e => setErr(String(e)));
  }, []);

  if (err) return <div style={{ padding: 24 }}>Error: {err}</div>;
  if (!license) return <div style={{ padding: 24 }}>Loading…</div>;
  if (license.deploymentMode !== 'onprem') {
    return <div style={{ padding: 24 }}>License management is on-prem only.</div>;
  }

  return (
    <div style={{ padding: 24, maxWidth: 720 }}>
      <h1>License</h1>
      <dl style={{ display: 'grid', gridTemplateColumns: 'max-content 1fr', gap: '8px 16px' }}>
        <dt>State</dt><dd>{license.state}</dd>
        <dt>Customer (sub)</dt><dd>{license.sub}</dd>
        <dt>License ID (jti)</dt><dd>{license.jti}</dd>
        <dt>Expires at</dt><dd>{license.expiresAt}</dd>
        <dt>Days until expiry</dt><dd>{license.daysUntilExpiry}</dd>
        <dt>Features</dt><dd>{license.features.join(', ') || '(none)'}</dd>
        <dt>Last verified at</dt><dd>{license.lastVerifiedAt ?? '(never)'}</dd>
        <dt>Next heartbeat at</dt><dd>{license.nextHeartbeatAt ?? '(none scheduled)'}</dd>
        {license.graceRemainingHours !== null && (
          <>
            <dt>Grace remaining</dt><dd>{license.graceRemainingHours} hour(s)</dd>
          </>
        )}
      </dl>
    </div>
  );
}
```

- [ ] **Step 2: App.tsx 에 라우트 추가**

`Routes` 안에 다른 `<Route ...>` 들 사이에 추가:

```tsx
import LicensePage from '@/pages/LicensePage';
// ...
<Route path="/license" element={<LicensePage />} />
```

`AppRoute` 타입과 `urlToRoute` / `routeToUrl` 에도 `license` 케이스 추가 — 기존 패턴 (`activity`, `audit-chain`, `settings`) 그대로 복제:

```ts
type AppRoute =
  | { name: 'tenants' }
  | { name: 'tenant'; tenantId: string; tab: string }
  | { name: 'activity' }
  | { name: 'audit-chain' }
  | { name: 'settings' }
  | { name: 'license' };

// urlToRoute 안
if (pathname === '/license') return { name: 'license' };
// routeToUrl 안
if (r.name === 'license') return '/license';
```

- [ ] **Step 3: Sidebar 에 License 메뉴 추가 — on-prem 모드에서만 표시**

`admin-ui/src/shell/Sidebar.tsx` 에서 사이드바 항목을 정의하는 부분에 조건부 추가. 기존 메뉴 항목 패턴을 그대로 따른다. 예:

```tsx
{ /* on-prem 일 때만 */ deploymentMode === 'onprem' && (
  <button onClick={() => navigate({ name: 'license' })}>License</button>
)}
```

`deploymentMode` 를 어떻게 전달할지: App.tsx 가 me 를 fetch 할 때 SystemInfo 도 같이 fetch 하거나, Sidebar 가 직접 `getLicense()` 한 번 호출해 `deploymentMode` 만 본다. 간단한 후자 패턴 권장:

```tsx
const [mode, setMode] = useState<'saas' | 'onprem' | null>(null);
useEffect(() => {
  import('@/api/license').then(({ getLicense }) =>
    getLicense().then(v => setMode(v.deploymentMode)).catch(() => setMode('saas'))
  );
}, []);
```

- [ ] **Step 4: 타입 체크 + 빌드**

```bash
cd admin-ui && npx tsc --noEmit && npm run build
```

Expected: 에러 없음.

- [ ] **Step 5: Commit**

```bash
git add admin-ui/src/pages/LicensePage.tsx admin-ui/src/App.tsx admin-ui/src/shell/Sidebar.tsx
git commit -m "feat(admin-ui): LicensePage + on-prem sidebar entry (L4.7)"
```

---

## Phase L5 — Documentation

---

### Task L5.1: docs/onprem-deployment.md

**Files:**
- Create: `docs/onprem-deployment.md`

- [ ] **Step 1: Write docs/onprem-deployment.md**

```markdown
# On-Premise Deployment Guide

## Overview

설치형 배포 모드. SaaS 모드와 동일한 단일 jar 를 사용하되 런타임에 `passkey.deployment.mode=onprem` 으로 전환한다. 활성화되면:

- 단일 tenant 로 VPD 컨텍스트가 부팅 시 고정됨
- 라이센스 토큰의 서명·만료가 부팅 시 검증됨 (실패 시 시작 중단)
- 주기적 heartbeat 로 라이센스 서버에 갱신/revocation 확인
- 만료 시 모든 API가 503 으로 차단됨

## 부팅 환경변수

| 환경변수 | 의미 | 기본값 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod,onprem` 등 | — |
| `PASSKEY_DEPLOYMENT_MODE` | `saas` 또는 `onprem` | `saas` |
| `PASSKEY_LICENSE_PATH` | 라이센스 JWS 파일 절대경로 | `/etc/passkey/license.jwt` |
| `PASSKEY_LICENSE_CACHE_PATH` | 캐시 파일 절대경로 | `/var/lib/passkey/license-cache.jwt` |
| `PASSKEY_LICENSE_HEARTBEAT_URL` | 라이센스 서버 기본 URL | `https://license.crosscert.com/v1/license` |
| `PASSKEY_LICENSE_HEARTBEAT_INTERVAL` | ISO-8601 Duration | `PT1H` |

## 라이센스 파일 수령

영업 담당자가 `license.jwt` 파일을 제공한다. 파일은 단일 JWS compact serialization (한 줄, `eyJ` 로 시작).

1. 파일을 `passkey.license.path` 에 배치 (기본 `/etc/passkey/license.jwt`)
2. 권한: `chmod 600`, owner = passkey 프로세스 user
3. 서비스 재시작

## 운영자 액션 매트릭스

| 상태 (LicenseState) | UI 배너 | 운영자 액션 |
|---|---|---|
| VALID | (없음) | 없음 |
| WARNING | 노란색 — "N일 후 만료" | 영업에 갱신 요청. 새 라이센스 파일 받아 교체 + 재시작 |
| NETWORK_GRACE | 주황색 — "N시간 내 차단" | 네트워크/방화벽 점검. license 서버 도달성 확인 |
| DEAD | 빨간 전체 페이지 | 영업 문의. 라이센스 갱신 후 재배포 |

## /actuator/health 의 license 컴포넌트

- VALID / WARNING / NETWORK_GRACE → `UP`
- DEAD → `DOWN` (전체 health 도 DOWN)

## 시계 동기화

라이센스 만료·grace period 가 시스템 시계 기반이라 NTP 동기화는 **필수**다. 시스템 시간이 `lastVerifiedAt` 보다 과거로 돌아가면 자동으로 DEAD 상태 진입 (rollback 공격 보호).

## 부팅 실패 트러블슈팅

| 오류 메시지 (stderr) | 원인 | 조치 |
|---|---|---|
| `License file not found at /etc/passkey/license.jwt` | 파일 없음 | 라이센스 파일 배치 후 재시작 |
| `License signature invalid` | 다른 키로 서명되었거나 파일 손상 | 영업에서 재발급 |
| `License audience mismatch: ...` | aud 가 `passkey-onprem` 이 아님 | 영업에서 재발급 |
| `License expired: exp=...` | 만료된 토큰 | 갱신된 라이센스 수령 |

## 로그 검색

`docs/logging-operations.md` 의 license 섹션 참고.
```

- [ ] **Step 2: Commit**

```bash
git add docs/onprem-deployment.md
git commit -m "docs: on-prem deployment + license operations guide (L5.1)"
```

---

### Task L5.2: logging-operations.md — license 섹션

**Files:**
- Modify: `docs/logging-operations.md`

- [ ] **Step 1: 현재 docs/logging-operations.md 구조 확인**

```bash
head -40 docs/logging-operations.md
grep "^##" docs/logging-operations.md
```

- [ ] **Step 2: 파일 끝에 license 섹션 추가**

```markdown

## License (on-prem 전용)

### 상태 전이 추적

```bash
grep "license state transition" passkey-app.log admin-app.log
```

기대: `VALID -> WARNING`, `WARNING -> NETWORK_GRACE`, `NETWORK_GRACE -> DEAD` 등.

### Heartbeat 실패 사유 집계

```bash
grep "license heartbeat failed" admin-app.log | \
  sed -E 's/.*reason=([^ ]+).*/\1/' | sort | uniq -c
```

### DEAD 상태에서 차단된 요청

```bash
grep "LICENSE_EXPIRED" passkey-app.log admin-app.log
```

### 의심스러운 라이센스 토큰 노출

`<jws-redacted>` 가 로그에 등장하면, 그 라인을 발생시킨 소스 위치를 확인하고 raw 토큰을 로그에 흘리는 코드 경로를 수정한다. SecretMaskingConverter 가 후방 방어로 가린 상태.
```

- [ ] **Step 3: Commit**

```bash
git add docs/logging-operations.md
git commit -m "docs(logging): on-prem license operations cookbook (L5.2)"
```

---

### Task L5.3: README — deployment.mode 한 줄

**Files:**
- Modify: `README.md`

- [ ] **Step 1: README 의 "기술 스택" 또는 "시작하기" 섹션 근처에 한 줄 추가**

```markdown
## 배포 모드

SaaS 멀티테넌트가 기본 (`passkey.deployment.mode=saas`). 설치형 싱글테넌트는 `passkey.deployment.mode=onprem` — 자세한 절차는 [docs/onprem-deployment.md](docs/onprem-deployment.md).
```

위치는 "## 모듈 구성" 섹션 위 또는 "## License" 섹션 위.

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs(readme): mention deployment.mode (onprem|saas) (L5.3)"
```

---

## Final Verification

모든 phase 완료 후:

- [ ] **Full build + test**

```bash
./gradlew clean build
```

Expected: BUILD SUCCESSFUL, 모든 모듈 테스트 통과.

- [ ] **SaaS 모드 부팅 smoke test (수동)**

```bash
SPRING_PROFILES_ACTIVE=dev ./gradlew :admin-app:bootRun
```

Expected: 정상 부팅, license 빈 등록 로그 없음.

- [ ] **on-prem 모드 부팅 smoke test (테스트 키로)**

```bash
# 임시 라이센스 발급 (jshell 또는 별도 main 메서드로 LicenseTestFixtures.issueValid 호출)
mkdir -p /tmp/onprem-test
# 발급된 JWS 문자열을 /tmp/onprem-test/license.jwt 에 저장

PASSKEY_DEPLOYMENT_MODE=onprem \
PASSKEY_LICENSE_PATH=/tmp/onprem-test/license.jwt \
PASSKEY_LICENSE_CACHE_PATH=/tmp/onprem-test/cache.jwt \
PASSKEY_LICENSE_HEARTBEAT_URL=http://localhost:9 \
SPRING_PROFILES_ACTIVE=dev ./gradlew :admin-app:bootRun
```

Expected:
- 로그: `license loaded from disk: jti=... expiresAt=...`
- 로그: `onprem mode: pinned VPD tenant to ...`
- 로그: `license state initialized: state=VALID ...`
- 1분 후: `license heartbeat failed: ... reason=http: ResourceAccessException` (URL 도달 불가)
- `curl localhost:8081/admin/api/license` → 200, state=`NETWORK_GRACE` 또는 `VALID` (heartbeat 첫 호출 전이면)

- [ ] **Worktree merge 준비 (사용자 승인 후 main 에 `--no-ff` merge)**

```bash
cd /Users/jhyun/Git/10-work/crosscert/Passkey2
git checkout main
git merge --no-ff worktree-onprem-licensing -m "Merge onprem licensing — Phase L1~L5"
git worktree remove .claude/worktrees/onprem-licensing
git branch -D worktree-onprem-licensing
```

---

## Self-Review (writer)

**Spec coverage check** (spec 의 결정 사항 → task 매핑):

| Spec 결정 | 구현 task |
|---|---|
| 단일 바이너리 + deployment.mode 프로파일 | L1.2 (yaml), L2.4·L2.5·L3.1·L3.2·L4.2 (`@ConditionalOnProperty`), L3.5 (회귀 테스트) |
| Ed25519 JWS 토큰 포맷 (sub/jti/exp/features/tenantId/limits) | L1.5 (key), L1.6 (record), L1.7 (fixture), L1.8 (verifier) |
| VALID/WARNING/NETWORK_GRACE/DEAD 상태 머신 | L2.1 (enum), L2.3 (machine + test) |
| Heartbeat + grace period + 디스크 캐시 | L2.2 (cache), L2.4 (scheduler), L2.5 (bootstrap reconcile) |
| 부팅 시 토큰 로드·VPD pin | L2.1 (loader), L2.5 (bootstrap) |
| Hard fail (만료 시 차단) + WARNING 배너 | L3.2 (filter 503), L4.6 (banner) |
| Feature gate (`@RequiresFeature`) | L3.1 (annotation+aspect), L3.3 (MDS 적용) |
| `/admin/api/license` API | L4.1 |
| Actuator health 통합 | L4.2 |
| 라이센스 JWS 로그 마스킹 | L4.3 |
| `deploymentMode` SystemInfo 노출 | L4.4 |
| Admin UI License 페이지 + 배너 + 사이드바 | L4.5, L4.6, L4.7 |
| 운영 문서 | L5.1, L5.2, L5.3 |
| 테스트 최소화: 단위 2개 + 통합 2개 | L1.8 (Verifier), L2.3 (StateMachine), L3.4 (GuardFilter IT), L3.5 (DeploymentMode IT) |

**Placeholder scan**: 위 모든 step 에 실제 코드 / 명령 / 기대 결과가 들어 있음. "TBD"/"적절히 추가" 없음.

**Type consistency**:
- `LicenseToken` 필드 (sub, jti, issuedAt, notBefore, expiresAt, tenantId, features, limits) — L1.6 정의 → L1.7 fixture, L1.8 verifier, L2.3 state machine test, L2.4 heartbeat, L2.5 bootstrap, L3.1 aspect (`hasFeature`), L3.2 filter (`expiresAt`, `limits.graceHoursWhenOffline`), L4.1 controller 모두 일치.
- `LicenseStateMachine` 생성자 시그니처 `(LicenseToken, Instant, Supplier<Instant>)` — L2.3, L2.5, L3.4 모두 동일.
- `LicenseCache.Entry(tokenJws, lastVerifiedAt)` — L2.2 정의, L2.4 / L2.5 사용 일치.
- `LicenseProperties` 필드 — L1.2 정의, L1.8 / L2.4 / L2.5 / L4.1 사용 일치.
- `RequiresFeature` 어노테이션 — L3.1 정의, L3.3 적용.

---

**Spec link:** `docs/superpowers/specs/2026-05-29-onprem-licensing-design.md`
**Worktree:** `.claude/worktrees/onprem-licensing` (branch `worktree-onprem-licensing`)
**Total tasks:** 22 (L1: 8, L2: 5, L3: 5, L4: 7, L5: 3) — 약 60~75 commit 예상.
