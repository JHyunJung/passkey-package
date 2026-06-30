# 코드베이스 심층 감사 A그룹(동작 불변 14건) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 2026-07-01 심층 감사에서 확정된 동작 불변(none) 개선 14건을 회귀 없이 적용한다.

**Architecture:** 각 Task는 독립적인 단일 finding 수정이다. 모듈 경계를 넘지 않으며, 관찰 가능한
출력/응답/순서/동작을 바꾸지 않는다. 대부분 방어심화(가드 추가)·중복 제거·비잠금 조회 전환이다.

**Tech Stack:** Java 21, Spring Boot 3.x, JUnit 5, JPA/Hibernate, Oracle, Redis, nimbus-jose-jwt.
빌드: `./gradlew :<module>:test`.

## Global Constraints

- **동작 불변**: 정상 입력에 대한 출력/응답/순서/동작이 바뀌어선 안 된다. 회귀 발견 시 즉시 중단.
- **회귀 판정**: 전체 `./gradlew build`는 SliceConfig 충돌·Oracle 컨테이너 경합으로 항상 빨감 —
  머지 게이트로 쓰지 않는다. 대상 모듈의 관련 테스트(`:<module>:test --tests <Class>`)만 그린이면 OK.
  pre-existing 실패는 base(main) 대조로 확정.
- **언어**: 한국어 주석·커밋 메시지. 기존 코드 스타일(Lombok @Slf4j/@RequiredArgsConstructor 등) 유지.
- **테스트 우선(TDD)**: 가능한 곳은 실패 테스트 → 구현 → 통과 → 커밋. 테스트가 비현실적인 방어심화
  (예: JCA가 이미 막는 alg 핀)는 명시적으로 "테스트 생략 사유"를 적고 단정문/주석으로 의도 고정.
- **커밋**: Task당 1커밋. 메시지에 finding ID(F##) 포함.

---

## Task 1: F12 — Credential 조회 GET에 @PreAuthorize 추가

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/credential/CredentialAdminController.java:31,40`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/credential/CredentialAdminControllerSecurityIT.java` (기존)

**Interfaces:**
- Consumes: 기존 `@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")` 패턴(revoke:49에 이미 존재).
- Produces: 없음(엔드포인트 동작 동일, 인가 어노테이션만 추가).

- [ ] **Step 1: 기존 보안 IT 확인 후 list/auth-events 인가 케이스 보강**

`CredentialAdminControllerSecurityIT.java`를 열어 revoke에 대한 role 거부 테스트 패턴을 확인한다.
list와 authEvents에 대해 "인증됐지만 잘못된 role(예: 무권한 사용자)이면 403" 케이스가 없으면 추가한다.
이미 `tenantBoundary`로 404/403이 나는 케이스가 있다면, 그것이 `@PreAuthorize`로도 막히는지
(컨트롤러 진입 전 403) 확인하는 테스트를 추가:

```java
@Test
void list_withoutRequiredRole_forbiddenAtControllerGate() throws Exception {
    mvc.perform(get("/admin/api/tenants/{tid}/credentials", TENANT_ID)
            .with(user("noRole").roles("SOMETHING_ELSE")))
       .andExpect(status().isForbidden());
}
```

- [ ] **Step 2: 테스트 실행 — 현재 통과(어노테이션 없어 .authenticated()만 → 진입 후 처리)인지 FAIL인지 확인**

Run: `./gradlew :admin-app:test --tests "*CredentialAdminControllerSecurityIT" -q`
Expected: 위 신규 테스트가 FAIL(현재는 403이 아닌 다른 코드) 또는 기존 동작 확인.

- [ ] **Step 3: 두 GET에 @PreAuthorize 추가**

`list` 메서드(31행 `@GetMapping` 위)와 `authEvents` 메서드(40행 `@GetMapping(...)` 위)에 추가:

```java
    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    @GetMapping
    public ApiResponse<PageView<CredentialView>> list(
```

```java
    @PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")
    @GetMapping("/{credentialId}/auth-events")
    public ApiResponse<PageView<CredentialAdminDto.AuthEventView>> authEvents(
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :admin-app:test --tests "*CredentialAdminControllerSecurityIT" -q`
Expected: PASS. 정상 role(PLATFORM_OPERATOR/RP_ADMIN)의 기존 케이스도 그대로 통과.

- [ ] **Step 5: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/credential/CredentialAdminController.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/credential/CredentialAdminControllerSecurityIT.java
git commit -m "fix(F12): credential 조회 GET에 @PreAuthorize 추가 — 형제 컨트롤러와 인가 일치"
```

---

## Task 2: F17 — canonicalAaguid 길이 가드

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/mds/MdsAaguidCache.java:44`
- Test: `core/src/test/java/com/crosscert/passkey/core/mds/MdsAaguidCacheTest.java` (없으면 생성)

**Interfaces:**
- Consumes: 없음.
- Produces: `canonicalAaguid(byte[])`가 16바이트 아닌 입력에 `IllegalArgumentException`(기존 AIOOBE 대신).

- [ ] **Step 1: 실패 테스트 작성**

`MdsAaguidCacheTest.java`를 생성(없을 경우). Redis 의존 없는 static 메서드만 테스트:

```java
package com.crosscert.passkey.core.mds;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MdsAaguidCacheTest {

    @Test
    void canonicalAaguid_16bytes_ok() {
        byte[] a = new byte[16];
        a[0] = 0x01;
        UUID u = MdsAaguidCache.canonicalAaguid(a);
        assertEquals("01000000-0000-0000-0000-000000000000", u.toString());
    }

    @Test
    void canonicalAaguid_shortArray_throwsIllegalArgument() {
        byte[] tooShort = new byte[8];
        assertThrows(IllegalArgumentException.class,
                () -> MdsAaguidCache.canonicalAaguid(tooShort));
    }

    @Test
    void canonicalAaguid_null_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> MdsAaguidCache.canonicalAaguid(null));
    }
}
```

- [ ] **Step 2: 테스트 실행 — short/null 케이스 FAIL 확인**

Run: `./gradlew :core:test --tests "*MdsAaguidCacheTest" -q`
Expected: `shortArray`는 AIOOBE(IllegalArgument 아님)로 FAIL, `null`은 NPE로 FAIL.

- [ ] **Step 3: 길이 가드 추가**

`MdsAaguidCache.java:44` canonicalAaguid 상단에:

```java
    /** AAGUID = 16-byte raw → UUID canonical form. */
    public static UUID canonicalAaguid(byte[] aaguid) {
        if (aaguid == null || aaguid.length != 16) {
            throw new IllegalArgumentException("aaguid must be 16 bytes");
        }
        long msb = 0, lsb = 0;
        for (int i = 0; i < 8; i++) msb = (msb << 8) | (aaguid[i] & 0xff);
        for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (aaguid[i] & 0xff);
        return new UUID(msb, lsb);
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test --tests "*MdsAaguidCacheTest" -q`
Expected: PASS(3개). 정상 16바이트 경로 불변.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/mds/MdsAaguidCache.java \
        core/src/test/java/com/crosscert/passkey/core/mds/MdsAaguidCacheTest.java
git commit -m "fix(F17): canonicalAaguid 길이 가드 — <16바이트시 typed error(AIOOBE→IllegalArgument)"
```

---

## Task 3: F36 — fido-u2f ES256 명시 검증

**Files:**
- Modify: `webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/FidoU2fAttestationVerifier.java:40-43`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/attestation/FidoU2fAttestationVerifierTest.java` (기존 있으면 보강)

**Interfaces:**
- Consumes: `CoseKey.algorithm()`(→ `CoseAlgorithm`), `CoseAlgorithm.ES256`.
- Produces: 없음(valid U2F는 항상 ES256이라 정상 경로 불변).

- [ ] **Step 1: 코드 확인**

`CoseKey`는 `record CoseKey(CoseAlgorithm algorithm, PublicKey publicKey)`. `CoseAlgorithm.ES256` 존재.
기존 코드(41행)는 `credKey.publicKey() instanceof ECPublicKey`만 검사.

- [ ] **Step 2: ES256 명시 단정 추가**

`FidoU2fAttestationVerifier.java:40-43`을 다음으로:

```java
        CoseKey credKey = CoseKeyParser.parse(acd.coseKeyMap());
        if (credKey.algorithm() != CoseAlgorithm.ES256) {
            throw new AttestationException("fido-u2f credential must be ES256 (P-256)");
        }
        if (!(credKey.publicKey() instanceof ECPublicKey ec)) {
            throw new AttestationException("fido-u2f credential is not EC");
        }
```

import 추가: `import com.crosscert.passkey.webauthn.cose.CoseAlgorithm;`

- [ ] **Step 3: 테스트 실행 — 정상 U2F 케이스 그대로 통과 확인**

Run: `./gradlew :webauthn:test --tests "*FidoU2fAttestationVerifierTest" -q`
Expected: PASS. (CoseKeyParser가 EC2를 ES256으로만 파싱하므로 기존 정상 케이스 불변.)

- [ ] **Step 4: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/FidoU2fAttestationVerifier.java
git commit -m "fix(F36): fido-u2f가 ES256 명시 검증 — U2F P-256 불변식을 파서 부수효과 의존에서 분리"
```

---

## Task 4: F22 — credential rename() 비잠금 조회

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/repository/CredentialRepository.java` (findOwned 추가)
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/credential/CredentialSelfService.java:34`
- Test: `passkey-app` 기존 self-service 테스트 또는 IT

**Interfaces:**
- Consumes: 기존 `findOwnedForUpdate(byte[] credentialId, byte[] userHandle)` 패턴.
- Produces: `CredentialRepository.findOwned(byte[] credentialId, byte[] userHandle): Optional<Credential>`
  — PESSIMISTIC 락 없는 동일 조회.

- [ ] **Step 1: findOwnedForUpdate 정의 확인**

`CredentialRepository.java:108` 부근의 `findOwnedForUpdate` @Query/@Lock을 읽는다. 동일 WHERE 절을
락 없이 복제할 비잠금 메서드를 만든다.

- [ ] **Step 2: findOwned 비잠금 메서드 추가**

`findOwnedForUpdate` 바로 위/아래에 동일 @Query에서 `@Lock(LockModeType.PESSIMISTIC_WRITE)`만 뺀 메서드 추가:

```java
    @Query("select c from Credential c where c.credentialId = :credentialId and c.userHandle = :userHandle")
    Optional<Credential> findOwned(@Param("credentialId") byte[] credentialId,
                                   @Param("userHandle") byte[] userHandle);
```

(주의: 기존 `findOwnedForUpdate`의 @Query JPQL을 그대로 복사하되 @Lock만 제거. 실제 JPQL은 108행 위에서 확인.)

- [ ] **Step 3: rename()이 findOwned 사용하도록 변경**

`CredentialSelfService.java:34`:

```java
    @Transactional
    public void rename(byte[] userHandle, byte[] credentialId, String label) {
        Credential c = creds.findOwned(credentialId, userHandle)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENTITY_NOT_FOUND,
                        "credential not found or not owned"));
        c.setLabel(label);
    }
```

(delete()는 findOwnedForUpdate 그대로 유지.)

- [ ] **Step 4: 테스트 실행 — rename 동작 불변 확인**

Run: `./gradlew :passkey-app:test --tests "*CredentialSelfService*" -q`
(해당 테스트 없으면 Fido2EndToEndIT의 rename 흐름) Expected: PASS — 라벨 변경이 dirty-checking으로 저장.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/repository/CredentialRepository.java \
        passkey-app/src/main/java/com/crosscert/passkey/app/fido2/credential/CredentialSelfService.java
git commit -m "fix(F22): credential rename()을 비잠금 findOwned로 — 라벨 변경에 불필요한 행 락 제거"
```

---

## Task 5: F29 — amr 원소 타입 정규화

**Files:**
- Modify: `sdk-java/src/main/java/com/crosscert/passkey/sdk/idtoken/IdTokenVerifier.java:66-68,81`
- Test: `sdk-java/src/test/java/com/crosscert/passkey/sdk/idtoken/IdTokenVerifierTest.java` (기존)

**Interfaces:**
- Consumes: 없음.
- Produces: `IdTokenClaims.amr()`는 항상 `List<String>`(비-String 원소 필터/문자열화).

- [ ] **Step 1: 실패 테스트 — amr에 비-String 원소가 섞인 토큰**

기존 `IdTokenVerifierTest`에 amr이 `["pin", 7]` 같은 혼합 배열인 토큰을 만들어 verify 후
`claims.amr()`를 String으로 순회해도 ClassCastException이 안 나는지 검증:

```java
@Test
void amr_withNonStringElement_normalizedNotPolluted() {
    // amr = ["pin", 7] 인 토큰을 발급(테스트 issuer로 서명)
    IdTokenClaims claims = verifier.verify(jwtWithAmr(List.of("pin", 7)));
    // 모든 원소가 실제 String 이어야 함 (heap pollution 없음)
    for (Object e : claims.amr()) {
        assertTrue(e instanceof String);
    }
}
```

- [ ] **Step 2: 테스트 실행 — 현재는 7이 Integer로 남아 instanceof String FAIL**

Run: `./gradlew :sdk-java:test --tests "*IdTokenVerifierTest" -q`
Expected: 신규 테스트 FAIL(원소 7이 Integer).

- [ ] **Step 3: 원소 정규화 적용**

`IdTokenVerifier.java:66-68`을 원소 필터로 변경(lenient 의도 보존 — 비-List는 여전히 빈 리스트):

```java
            Object amrRaw = c.getClaim("amr");
            List<String> amr = null;
            if (amrRaw instanceof List<?> rawList) {
                amr = rawList.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(String::valueOf)
                        .toList();
            }
```

81행 `(amr == null) ? List.of() : List.copyOf(amr)`는 그대로 둔다(이미 안전).
기존 `@SuppressWarnings("unchecked")`는 제거(이제 unchecked 캐스트 없음).

- [ ] **Step 4: 테스트 통과 + 정상 토큰 회귀 없음 확인**

Run: `./gradlew :sdk-java:test --tests "*IdTokenVerifierTest" -q`
Expected: PASS. amr=["pin"] 같은 정상 케이스도 동일 결과.

- [ ] **Step 5: Commit**

```bash
git add sdk-java/src/main/java/com/crosscert/passkey/sdk/idtoken/IdTokenVerifier.java \
        sdk-java/src/test/java/com/crosscert/passkey/sdk/idtoken/IdTokenVerifierTest.java
git commit -m "fix(F29): amr 원소를 String으로 정규화 — 비-String 원소의 지연 ClassCastException 차단"
```

---

## Task 6: F09 — Activity feed slug 배치 조회

**Files:**
- Modify: `admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityService.java:105-111`
- Test: `admin-app/src/test/java/com/crosscert/passkey/admin/activity/ActivityControllerIT.java` (기존)

**Interfaces:**
- Consumes: `tenants.findAllById(Iterable<UUID>)`(JpaRepository 기본 제공).
- Produces: 없음(slugByTenant Map 내용 동일).

- [ ] **Step 1: 현재 코드 확인**

105-111행: distinct tenantId마다 `tenants.findById(tid)`. 옆 `AuditChainMonitorController.buildLookups`는
`tenantRepo.findAllById(...)` 배치. 비대칭.

- [ ] **Step 2: 배치 조회로 변경**

```java
        // distinct tenantId 들을 한 번의 IN 쿼리로 로드 — incident buildLookups 와 동일 패턴.
        java.util.Set<UUID> tenantIds = feed.stream()
                .map(AuditLog::getTenantId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> slugByTenant = tenants.findAllById(tenantIds).stream()
                .collect(Collectors.toMap(Tenant::getId, Tenant::getSlug));
```

그리고 121행 `slugByTenant.get(a.getTenantId())`는 deleted 테넌트(findAllById가 안 돌려줌)면 null →
기존 `.orElse("(deleted)")`와 동작이 달라진다(주의!). **동작 보존을 위해** 조회 시 fallback 유지:

```java
                        a.getTenantId() == null ? null
                                : slugByTenant.getOrDefault(a.getTenantId(), "(deleted)"),
```

- [ ] **Step 3: 테스트 실행 — feed의 slug/(deleted) 표기 불변 확인**

Run: `./gradlew :admin-app:test --tests "*ActivityControllerIT" -q`
Expected: PASS. deleted 테넌트 행이 여전히 "(deleted)"로 표기되는지 특히 확인(없으면 케이스 추가).

- [ ] **Step 4: deleted 테넌트 fallback 테스트 보강(없으면)**

feed에 존재하지 않는 tenantId를 가진 AuditLog가 있을 때 event.tenantSlug가 "(deleted)"인지 검증하는
케이스가 ActivityControllerIT에 없으면 추가.

- [ ] **Step 5: Commit**

```bash
git add admin-app/src/main/java/com/crosscert/passkey/admin/activity/ActivityService.java \
        admin-app/src/test/java/com/crosscert/passkey/admin/activity/ActivityControllerIT.java
git commit -m "fix(F09): activity feed slug를 findAllById 배치로 — per-tenant findById N+1 제거(표기 불변)"
```

---

## Task 7: F19 — challenge issuedAt 앱-레벨 freshness 검증(방어심화)

**Files:**
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/registration/RegistrationFinishService.java`
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/authentication/AuthenticationFinishService.java`
- Read: `passkey-app/src/main/java/com/crosscert/passkey/app/fido2/challenge/ChallengeStore.java` (TTL 상수)
- Test: 각 FinishService 테스트(기존) + 만료 challenge 거부 케이스

**Interfaces:**
- Consumes: `AuthenticationChallenge.issuedAt()`, `RegistrationChallenge.issuedAt()`, ChallengeStore TTL 상수, `Clock`.
- Produces: 없음(정상 흐름은 TTL 내라 불변; 만료 경계만 거부 — 사실상 Redis TTL과 동일 결과).

- [ ] **Step 1: ChallengeStore TTL 상수 확인**

`ChallengeStore.java`를 읽어 TTL(예 `Duration TTL = Duration.ofMinutes(5)`) 노출 여부 확인. private면
`public static final Duration TTL`로 노출하거나 getter 추가.

- [ ] **Step 2: 실패 테스트 — issuedAt이 TTL보다 오래된 challenge면 거부**

RegistrationFinishService 테스트에 Clock을 TTL+1분 앞으로 돌려(또는 issuedAt을 과거로 한 challenge를
store에 직접 주입) finish가 거부(BusinessException)되는지 검증:

```java
@Test
void finish_staleChallenge_rejected() {
    // store 에 issuedAt = now - (TTL + 1m) 인 challenge 주입
    // finish 호출 시 freshness 위반으로 거부되어야 함
    assertThrows(BusinessException.class, () -> service.finish(...staleHandle...));
}
```

- [ ] **Step 3: 테스트 실행 — 현재는 앱-레벨 검사 없어 통과(=FAIL 기대)**

Run: `./gradlew :passkey-app:test --tests "*RegistrationFinishService*" -q`
Expected: 신규 테스트 FAIL(현재 issuedAt 미검사라 거부 안 됨).

- [ ] **Step 4: finish 경로에 freshness 가드 추가**

challenge를 store에서 꺼낸 직후(takeXxx 후) 두 FinishService 각각에:

```java
        if (clock.instant().minus(ChallengeStore.TTL).isAfter(ch.issuedAt())) {
            throw new BusinessException(ErrorCode.CHALLENGE_EXPIRED, "challenge expired");
        }
```

(ErrorCode에 적절한 값이 없으면 기존 challenge 관련 에러코드 재사용. clock이 주입돼 있지 않으면
Clock 빈을 생성자 주입.)

- [ ] **Step 5: 테스트 통과 + 정상 challenge 회귀 없음 확인**

Run: `./gradlew :passkey-app:test --tests "*FinishService*" -q`
Expected: PASS. 정상 challenge(issuedAt=now)는 그대로 통과.

- [ ] **Step 6: Commit**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/fido2/
git commit -m "fix(F19): finish 경로에 challenge issuedAt freshness 가드 — Redis TTL 단일방어를 앱-레벨로 이중화"
```

---

## Task 8: F34 — CertPathVerifier 검증 시각 주입

**Files:**
- Modify: `webauthn/src/main/java/com/crosscert/passkey/webauthn/trust/CertPathVerifier.java`
- Modify: 호출부(`NativeWebAuthnVerifier` 또는 trust 사용처 — grep으로 확인)
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/trust/CertPathVerifierTest.java` (없으면 생성)

**Interfaces:**
- Consumes: 없음.
- Produces: `CertPathVerifier.verify(List<X509Certificate> chain, Set<TrustAnchor> anchors, Instant at): boolean`
  — at 기준 시각으로 PKIX 검증. 기존 `verify(chain, anchors)` 오버로드는 `verify(chain, anchors, Instant.now())`로 유지.

- [ ] **Step 1: 호출부 grep**

Run: `grep -rn "\.verify(" webauthn/src/main/java | grep -i certpath` 및 CertPathVerifier 사용처 확인.

- [ ] **Step 2: 실패 테스트 — 만료된 인증서를 만료 전 시각으로 검증하면 통과**

```java
@Test
void verify_expiredCert_passesWhenValidatedAtPastDate() {
    // 과거에 유효했던(지금은 만료) 체인 + anchor 준비
    Instant whenValid = ...; // 인증서 유효기간 내 시각
    assertTrue(new CertPathVerifier().verify(chain, anchors, whenValid));
    assertFalse(new CertPathVerifier().verify(chain, anchors, Instant.now())); // 지금은 만료
}
```

- [ ] **Step 3: 테스트 실행 — 3-인자 오버로드 없어 컴파일 FAIL**

Run: `./gradlew :webauthn:test --tests "*CertPathVerifierTest" -q`
Expected: 컴파일 에러(메서드 없음).

- [ ] **Step 4: 3-인자 오버로드 추가, 기존은 위임**

```java
    /** 기존 호출부 호환 — 현재 시각 기준 검증. */
    public boolean verify(List<X509Certificate> chain, Set<TrustAnchor> anchors) {
        return verify(chain, anchors, java.time.Instant.now());
    }

    /** at 기준 시각으로 PKIX 검증(결정적 검증·테스트 가능성). */
    public boolean verify(List<X509Certificate> chain, Set<TrustAnchor> anchors, java.time.Instant at) {
        if (anchors == null || anchors.isEmpty() || chain == null || chain.isEmpty()) {
            return false;
        }
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            CertPath path = cf.generateCertPath(chain);
            PKIXParameters params = new PKIXParameters(anchors);
            params.setRevocationEnabled(false);
            params.setDate(java.util.Date.from(at));
            CertPathValidator.getInstance("PKIX").validate(path, params);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
```

- [ ] **Step 5: 테스트 통과 + 기존 호출부 회귀 없음**

Run: `./gradlew :webauthn:test -q`
Expected: PASS. 기존 2-인자 호출부는 Instant.now() 위임이라 동작 불변.

- [ ] **Step 6: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/trust/CertPathVerifier.java \
        webauthn/src/test/java/com/crosscert/passkey/webauthn/trust/CertPathVerifierTest.java
git commit -m "fix(F34): CertPathVerifier에 검증 시각 주입 오버로드 — 결정적 검증·테스트 가능성(기본 동작 불변)"
```

---

## Task 9: F32 — SafetyNet JWS alg↔leaf 키타입 명시 핀

**Files:**
- Modify: `webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AndroidSafetyNetAttestationVerifier.java:93,99`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/attestation/AndroidSafetyNetAttestationVerifierTest.java` (기존)

**Interfaces:**
- Consumes: 없음.
- Produces: 없음(정상 ES256/RSA SafetyNet 응답 불변; 불일치는 명시 거부 — JCA가 이미 막던 것).

- [ ] **Step 1: 현재 verifyJws / alg 처리 확인**

93·99행 부근: header.alg를 verifyJws에 넘기고 leaf 키 타입과 명시 매칭 없음. 예외 일괄 false 흡수 구조 확인.

- [ ] **Step 2: alg↔leaf 키타입 명시 매칭 추가**

verifyJws 진입(또는 서명 검증 직전)에 leaf 공개키 타입과 alg 일치 확인:

```java
        java.security.PublicKey leafKey = leaf.getPublicKey();
        boolean algKeyMatch =
                ("ES256".equals(alg) && leafKey instanceof java.security.interfaces.ECPublicKey)
             || ("RS256".equals(alg) && leafKey instanceof java.security.interfaces.RSAPublicKey);
        if (!algKeyMatch) {
            throw new AttestationException("safetynet alg/leaf-key mismatch: alg=" + alg);
        }
```

(정확한 alg 변수명·leaf 변수명은 코드에서 확인. ES256/RS256 외 알고리즘은 현재 미지원이므로 거부 적절.)

- [ ] **Step 3: 테스트 실행 — 정상 SafetyNet 케이스 그대로 통과**

Run: `./gradlew :webauthn:test --tests "*AndroidSafetyNet*" -q`
Expected: PASS. 정상 응답(ES256 + EC leaf)은 매칭 통과.

- [ ] **Step 4: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/AndroidSafetyNetAttestationVerifier.java
git commit -m "fix(F32): SafetyNet JWS alg↔leaf 키타입 명시 핀 — alg-confusion 회귀 표면 차단(방어심화)"
```

---

## Task 10: F33 — TPM attStmt.alg↔AIK 키타입 명시 검증

**Files:**
- Modify: `webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/TpmAttestationVerifier.java:109`
- Test: `webauthn/src/test/java/com/crosscert/passkey/webauthn/attestation/TpmAttestationVerifierTest.java` (기존)

**Interfaces:**
- Consumes: `CoseAlgorithm`(alg), AIK `X509Certificate.getPublicKey()`.
- Produces: 없음(정상 TPM 불변; 불일치 명시 거부).

- [ ] **Step 1: 109행 verifySignature 호출·coseAlg 출처 확인**

`coseAlg.jcaSignatureName()`이 attStmt.alg에서 옴. AIK 인증서 키 타입 일치 검증 없음 확인.

- [ ] **Step 2: AIK 키타입↔alg 매칭 단정 추가**

verifySignature(109행) 직전에:

```java
        java.security.PublicKey aikKey = aik.getPublicKey();
        boolean algKeyMatch =
                (coseAlg == CoseAlgorithm.ES256 && aikKey instanceof java.security.interfaces.ECPublicKey)
             || (coseAlg == CoseAlgorithm.RS256 && aikKey instanceof java.security.interfaces.RSAPublicKey);
        if (!algKeyMatch) {
            throw new AttestationException("tpm alg/AIK-key mismatch: alg=" + coseAlg);
        }
```

(coseAlg·aik 변수명은 코드에서 확인. import CoseAlgorithm 필요시 추가.)

- [ ] **Step 3: 테스트 실행 — 정상 TPM 케이스 통과**

Run: `./gradlew :webauthn:test --tests "*TpmAttestationVerifierTest" -q`
Expected: PASS(정상 ES256/RS256 + 대응 AIK 키).

- [ ] **Step 4: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/attestation/TpmAttestationVerifier.java
git commit -m "fix(F33): TPM attStmt.alg↔AIK 키타입 명시 검증 — alg 핀을 입력에서 인증서로 고정(방어심화)"
```

---

## Task 11: F37 — webauthn ObjectMapper 정책 통일

**Files:**
- Modify: `webauthn/.../attestation/AndroidSafetyNetAttestationVerifier.java:55`, `webauthn/.../mds/` 의 MDS/MdsBlobParser
- Read: `NativeWebAuthnVerifier` 의 주입 ObjectMapper 사용 패턴
- Test: 기존 webauthn 테스트 그린 유지

**Interfaces:**
- Consumes: 공통 ObjectMapper 팩토리 또는 주입.
- Produces: 없음(파싱 결과 동일; 보안 설정이 모든 JSON 경로에 일관 적용 가능해짐).

- [ ] **Step 1: ObjectMapper 생성 지점 grep**

Run: `grep -rn "new ObjectMapper()" webauthn/src/main/java`
대상: SafetyNet:55, MDS 관련 파서들. NativeWebAuthnVerifier가 주입받는 mapper 출처 확인.

- [ ] **Step 2: 공통 팩토리 도입**

`webauthn/.../JsonMappers.java`(신규) 또는 기존 config에 공통 보안 설정 ObjectMapper 팩토리:

```java
package com.crosscert.passkey.webauthn;

import com.fasterxml.jackson.databind.ObjectMapper;

/** webauthn 내부 JSON 파싱용 공통 ObjectMapper — 보안 설정을 한 곳에서 관리. */
public final class JsonMappers {
    private JsonMappers() {}
    public static ObjectMapper secure() {
        ObjectMapper m = new ObjectMapper();
        // 현재 동작 보존을 위해 기본 설정 유지. 향후 보안 강화(max string length 등)는 여기에서만.
        return m;
    }
}
```

각 `new ObjectMapper()`를 `JsonMappers.secure()`로 치환.

> **주의**: 이 Task는 동작 불변이 최우선. 보안 설정을 *지금 강화하지 않는다*(그건 동작 변경). 단지
> 생성 지점을 한 곳으로 모아 향후 일관 적용이 가능하게 한다. 설정 강화는 B그룹 후속.

- [ ] **Step 3: 테스트 실행 — 전체 webauthn 그린**

Run: `./gradlew :webauthn:test -q`
Expected: PASS. 파싱 동작 불변.

- [ ] **Step 4: Commit**

```bash
git add webauthn/src/main/java/com/crosscert/passkey/webauthn/
git commit -m "fix(F37): webauthn JSON 파싱 ObjectMapper를 공통 팩토리로 통일 — 정책 드리프트 제거(설정 불변)"
```

---

## Task 12: F16 — LicenseBootstrap @Bean ThreadLocal 정리

**Files:**
- Modify: `core/src/main/java/com/crosscert/passkey/core/license/LicenseBootstrap.java:65`
- Test: 기존 license 테스트 그린 유지(onprem 경로)

**Interfaces:**
- Consumes: 없음.
- Produces: 없음(부트 후 ThreadLocal 정리만 — per-request는 OnpremTenantPinFilter가 담당).

- [ ] **Step 1: 65행 set 컨텍스트·이후 사용 범위 확인**

`TenantContextHolder.set(...)` 이후 같은 메서드에서 그 컨텍스트를 실제로 쓰는 작업이 있는지 확인.
주석(63행 부근)이 OnpremTenantPinFilter가 per-request를 커버한다고 명시.

- [ ] **Step 2: set을 try/finally로 감싸 clear (또는 불필요시 제거)**

set 이후 컨텍스트 필요 작업이 있으면:

```java
        TenantContextHolder.set(effective.tenantId());
        try {
            // ... 부트 시 컨텍스트가 필요한 작업들 ...
        } finally {
            TenantContextHolder.clear();
        }
```

set 이후 컨텍스트를 쓰는 작업이 *없으면* set 자체를 제거(per-request 핀이 담당하므로).
어느 쪽이든 부트 스레드 ThreadLocal에 잔존하지 않게 한다.

- [ ] **Step 3: 테스트 실행 — license bootstrap 동작 불변**

Run: `./gradlew :core:test --tests "*License*" -q`
Expected: PASS. onprem 핀 동작 불변.

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/crosscert/passkey/core/license/LicenseBootstrap.java
git commit -m "fix(F16): LicenseBootstrap @Bean의 TenantContext를 try/finally로 정리 — 부트스레드 ThreadLocal 누수 방지"
```

---

## Task 13: F27 — JWKS 갱신 single-flight + negative-cache

**Files:**
- Modify: `sdk-java/src/main/java/com/crosscert/passkey/sdk/idtoken/JwksCache.java`
- Test: `sdk-java/src/test/java/com/crosscert/passkey/sdk/idtoken/JwksCacheTest.java` (없으면 생성)

**Interfaces:**
- Consumes: 없음.
- Produces: 없음(happy path 동일; TTL 만료 시 동시 갱신을 1회로 직렬화, fetch 실패 시 직전 스냅샷 폴백 + 짧은 백오프).

- [ ] **Step 1: 실패 테스트 — fetch 실패 시 직전 유효 스냅샷 폴백**

```java
@Test
void get_fetchFailureAfterSuccess_returnsStaleSnapshotNotThrow() {
    // 1회 성공(스냅샷 보유) → loader가 다음 호출에서 IOException → get()이 직전 스냅샷 반환(예외 X)
    // (loader를 주입 가능하게 리팩터하거나, JWKSet.load를 감싼 protected fetch를 override)
}
```

(JwksCache는 현재 `JWKSet.load(jwksUrl)`를 직접 호출 — 테스트 위해 `protected JWKSet fetch()`를
override 가능하게 두거나 loader 함수형 주입.)

- [ ] **Step 2: 테스트 실행 — 현재는 fetch 실패가 그대로 throw → FAIL**

Run: `./gradlew :sdk-java:test --tests "*JwksCacheTest" -q`
Expected: 신규 테스트 FAIL(예외 전파).

- [ ] **Step 3: single-flight + negative-cache 구현**

`get()`을 다음 의미로 변경:
- 유효 스냅샷 있으면 반환(현행).
- 만료 시: `synchronized`(또는 단일 비행 가드)로 한 스레드만 fetch; 다른 스레드는 락 진입 후 재확인.
- fetch 성공: 스냅샷 갱신.
- fetch 실패: **직전 스냅샷이 있으면** 그것을 반환하고 짧은 백오프 타임스탬프 기록(다음 fetch까지 재시도 억제).
  직전 스냅샷이 *없으면*(최초 부팅 실패) 기존대로 예외.

```java
    private final Object refreshLock = new Object();
    private volatile Instant nextRetryAfter = Instant.MIN;

    public JWKSet get() {
        Snapshot cur = snapshot.get();
        Instant now = clock.instant();
        if (cur != null && cur.fetchedAt().plus(ttl).isAfter(now)) {
            return cur.jwks();
        }
        synchronized (refreshLock) {
            Snapshot again = snapshot.get();
            Instant n2 = clock.instant();
            if (again != null && again.fetchedAt().plus(ttl).isAfter(n2)) {
                return again.jwks(); // 다른 스레드가 이미 갱신
            }
            // 직전 실패 백오프 중이면 stale 스냅샷 반환(있을 때)
            if (again != null && n2.isBefore(nextRetryAfter)) {
                return again.jwks();
            }
            try {
                JWKSet fresh = fetch();
                snapshot.set(new Snapshot(fresh, n2));
                return fresh;
            } catch (PasskeyIdTokenException e) {
                if (again != null) {
                    nextRetryAfter = n2.plus(BACKOFF); // 예: Duration.ofSeconds(5)
                    return again.jwks(); // stale 폴백
                }
                throw e; // 최초 부팅 실패는 전파
            }
        }
    }
```

`fetch()`는 테스트 override 위해 `protected`로. `BACKOFF` 상수 정의.

- [ ] **Step 4: 테스트 통과 + 정상 경로 회귀 없음**

Run: `./gradlew :sdk-java:test -q`
Expected: PASS. happy path(유효 스냅샷) 동일.

- [ ] **Step 5: Commit**

```bash
git add sdk-java/src/main/java/com/crosscert/passkey/sdk/idtoken/JwksCache.java \
        sdk-java/src/test/java/com/crosscert/passkey/sdk/idtoken/JwksCacheTest.java
git commit -m "fix(F27): JWKS 갱신 single-flight + 실패시 stale 폴백·백오프 — thundering herd·장애 증폭 차단(happy path 불변)"
```

---

## Task 14: F21 — API 키 scope 조회 캐싱

**Files:**
- Modify: `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyAuthFilter.java:194`
- Create: `passkey-app/src/main/java/com/crosscert/passkey/app/security/ApiKeyScopeCache.java`
- Modify: admin scope 변경 지점(scope mutation) — evict 연결
- Test: `passkey-app` scope 캐시 단위 테스트(evict 정확성)

**Interfaces:**
- Consumes: `ApiKeyScopeRepository.findScopeValuesByApiKeyId(UUID): Set<String>`.
- Produces: `ApiKeyScopeCache.getScopes(UUID apiKeyId): Set<String>`(짧은 TTL Caffeine, 미스 시 repo 위임),
  `ApiKeyScopeCache.evict(UUID apiKeyId)`.

> **주의**: 이 Task는 동작 불변이 제약이다. 캐시 미스/만료 시 결과는 repo와 동일. **scope 변경 시
> evict가 누락되면 동작이 바뀐다**(TTL만큼 stale). 따라서 evict 연결을 반드시 포함하고, TTL은 매우
> 짧게(예 30~60s) 둬 evict 누락 시에도 자연 수렴하게 한다. Caffeine 의존이 이미 있는지 먼저 확인 —
> 없으면 도입은 별도 결정이 필요하니 **이 Task만 마지막에 두고, 의존 없으면 스킵 후 보고**.

- [ ] **Step 1: Caffeine 의존 존재 확인**

Run: `grep -rn "caffeine" gradle/libs.versions.toml passkey-app/build.gradle.kts core/build.gradle.kts`
- 있으면 진행. **없으면 이 Task를 스킵**하고 "F21은 Caffeine 의존 도입 필요 — 동작불변 범위 밖, B그룹으로
  이관" 보고 후 종료.

- [ ] **Step 2: 실패 테스트 — evict 후 repo 재조회**

```java
@Test
void getScopes_cachesUntilEvict() {
    // repo mock: 첫 호출 {"a"}, 두번째 호출 {"a","b"}
    assertEquals(Set.of("a"), cache.getScopes(id));     // repo 1회
    assertEquals(Set.of("a"), cache.getScopes(id));     // 캐시 — repo 미호출
    cache.evict(id);
    assertEquals(Set.of("a","b"), cache.getScopes(id)); // evict 후 repo 재호출
    verify(scopeRepo, times(2)).findScopeValuesByApiKeyId(id);
}
```

- [ ] **Step 3: ApiKeyScopeCache 구현(Caffeine, TTL 60s)**

```java
@Component
public class ApiKeyScopeCache {
    private final ApiKeyScopeRepository scopeRepo;
    private final Cache<UUID, Set<String>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(10_000)
            .build();
    public ApiKeyScopeCache(ApiKeyScopeRepository scopeRepo) { this.scopeRepo = scopeRepo; }
    public Set<String> getScopes(UUID apiKeyId) {
        return cache.get(apiKeyId, scopeRepo::findScopeValuesByApiKeyId);
    }
    public void evict(UUID apiKeyId) { cache.invalidate(apiKeyId); }
}
```

- [ ] **Step 4: ApiKeyAuthFilter가 캐시 사용(194행)**

`scopeRepo.findScopeValuesByApiKeyId(row.id())` → `scopeCache.getScopes(row.id())`. 캐시 미스/만료
시 repo 위임이라 fail-closed 의미 보존.

- [ ] **Step 5: scope mutation 지점에 evict 연결**

admin-app에서 API 키 scope를 변경/발급/폐기하는 서비스(ApiKeyAdminService 등)를 grep으로 찾아, 변경 후
`scopeCache.evict(apiKeyId)` 호출. **단 admin-app→passkey-app 캐시는 별 프로세스**라 직접 evict 불가할
수 있음 — 그 경우 TTL(60s) 자연 만료에 의존하고 그 사실을 주석·커밋에 명시(동작은 최대 60s stale, 정상
운영에서 scope 변경은 드물어 허용 범위. evict 불가가 곤란하면 이 Task를 B그룹으로 이관).

- [ ] **Step 6: 테스트 통과 + 인증 경로 회귀 없음**

Run: `./gradlew :passkey-app:test -q`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add passkey-app/src/main/java/com/crosscert/passkey/app/security/
git commit -m "fix(F21): API 키 scope 조회 캐싱(Caffeine TTL 60s) — 핫경로 요청당 DB 왕복 제거(미스시 repo 위임)"
```

---

## Self-Review 기록

- **Spec 커버리지**: A그룹 14건(F09,F12,F16,F17,F19,F21,F22,F27,F29,F32,F33,F34,F36,F37) 전부 Task 1~14에 매핑.
- **동작 불변 위험 지점 명시**: F09(deleted fallback 보존), F19/F27/F32/F33/F34(정상 경로 회귀 검증),
  F21(evict 누락 시 TTL stale — cross-process evict 불가 시 B그룹 이관 조건 명시), F37(설정 강화 금지).
- **조건부 스킵**: F21은 Caffeine 의존·cross-process evict 가능성에 따라 B그룹 이관 가능 — Step에 명시.
- **타입 일관성**: `findOwned`/`findOwnedForUpdate`(F22·repository), `verify(...,Instant)`(F34 오버로드),
  `getScopes`/`evict`(F21) 시그니처 일관.
- **권장 실행 순서**: trivial 우선(Task 1~6) → moderate(7~14). F21을 가장 마지막에(조건부 스킵 가능).
