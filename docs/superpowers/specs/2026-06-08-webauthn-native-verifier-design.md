# WebAuthn 등록/인증 검증 코어 자체 구현 (`:webauthn` 모듈)

- **작성일**: 2026-06-08
- **상태**: 설계 승인 대기 → 구현 계획 작성 예정
- **서브프로젝트**: 1/3 (등록/인증 검증 코어). 후속 = MDS 자체구현, 테스트 authenticator 에뮬레이터 자체구현

## 1. 배경 & 동기

현재 WebAuthn 등록/인증 검증과 MDS 통합은 `webauthn4j` 0.31.5 라이브러리에 의존한다.
이를 W3C WebAuthn Level 3 spec(https://www.w3.org/TR/webauthn-3/)을 직접 구현한 코드로 대체한다.

핵심 동기 (사용자 확정):

1. **외부 의존성 제거** — webauthn4j 및 그로 인한 전이 의존성(Jackson 3 충돌 등) 제거.
2. **내부 동작 완전 이해/제어** — 검증 로직의 모든 단계를 직접 소유하고 정책·로깅·예외를 커스터마이즈.
3. **규제/인증 요건** — 제3자 라이브러리 대신 감사 가능한 자체 검증 구현 확보.

이 동기 조합은 "프로덕션 교체"를 진지하게 목표로 하며, **정확성·감사가능성**이 최우선이다.

## 2. 범위 (확정)

| 항목 | 결정 |
|------|------|
| 이번 서브프로젝트 범위 | **등록/인증 검증 코어만** (RegistrationFinish / AuthenticationFinish 내부) |
| Attestation 검증 깊이 | **주요 포맷 전부**: none, packed(self+x5c), fido-u2f, tpm, android-key, android-safetynet, apple + X.509 체인 검증 |
| 저수준 암호 연산 | **JDK 표준 API 활용** (`java.security`: Signature / CertPathValidator / MessageDigest / KeyFactory). WebAuthn·CBOR·COSE 로직은 자체 구현 |
| 기존 저장 데이터 | **없음 (초기 구축)** — 호환성 제약 없음, 자체 최적화 스키마 자유 설계 |
| 검증 전략 | **① webauthn4j 병행 differential 테스트 + ② W3C/FIDO 공식 test vector** |
| 새 모듈명 | **`:webauthn`** (패키지 `com.crosscert.passkey.webauthn`) |
| 접근법 | **A — 어댑터 우선 + 모듈 격리** (인터페이스 뒤에서 점진 스위치) |

**범위 밖 (후속 서브프로젝트)**:

- MDS3 JWS BLOB 다운로드/서명검증/AAGUID 캐시 자체 구현 (현재 `admin-app`의 webauthn4j-metadata 유지)
- 테스트 authenticator 에뮬레이터(`Fido2TestAuthenticator`, webauthn4j-test) 자체 구현
- 위 둘 완료 시에야 webauthn4j **완전 제거** 달성

## 3. 사전 조사 결과 (현재 webauthn4j 의존 지점)

- 빌드: `gradle/libs.versions.toml` → `webauthn4j = "0.31.5.RELEASE"`. `core` 모듈이 `webauthn4j-core`·`webauthn4j-metadata`를 `api()`로 노출. `build.gradle.kts`에서 jackson-annotations 2.21 오버라이드(Jackson 3 충돌 회피).
- **컨트롤러·DTO에는 webauthn4j import 0건** (확인 완료):
  - `RegistrationController`, `AuthenticationController` (`/api/v1/rp/{registration,authentication}/{start,finish}`)
  - 공개 메서드: `finish(RegistrationFinishRequest) → RegistrationFinishResponse` 등 전부 자체 DTO
- webauthn4j는 **finish 서비스 메서드 본문 안에만** 갇혀 있음:
  - `passkey-app/.../app/fido2/registration/RegistrationFinishService.java` — `WebAuthnManager.parseRegistrationResponseJSON` / `verify` / `AttestationObjectConverter` 등
  - `passkey-app/.../app/fido2/authentication/AuthenticationFinishService.java` — `parseAuthenticationResponseJSON` / `verify` / `CredentialRecordImpl` 등
  - `*StartService`는 webauthn4j 미사용 (순수 옵션 JSON 생성)
- 빈 구성: `passkey-app/.../app/fido2/Webauthn4jConfig.java` — `WebAuthnManager.createNonStrictWebAuthnManager()` + `ObjectConverter`
- 저장: `core/.../entity/Credential.java` — `publicKey` 컬럼에 통짜 JSON 엔벨로프(ao/cd/ce/tr) 저장. `sign_count`/`aaguid`/`transports`/`attestation_fmt` 별도 컬럼 존재.
- MDS(범위 밖): `admin-app/.../mds/*` + `passkey-app/.../app/fido2/mds/MdsVerifier.java` — AAGUID 추출만 코어와 연결, 검증은 앱이 담당.

## 4. API 영향 — 없음 (확인 완료)

| 계층 | webauthn4j 노출 | 변경 |
|------|:---:|:---:|
| 컨트롤러 (URL/매핑) | ❌ | ❌ |
| 공개 메서드 시그니처 | ❌ | ❌ |
| 요청/응답 DTO | ❌ | ❌ |
| 서비스 내부 구현 | ✅ | ✅ (본문만) |

`/api/v1/rp/registration/{start,finish}`, `/api/v1/rp/authentication/{start,finish}` 의
URL·HTTP 메서드·요청/응답 JSON 스키마는 전부 그대로. 클라이언트(SDK·브라우저) 변경 불필요.

## 5. 아키텍처 — 접근법 A (어댑터 우선 + 모듈 격리)

새 Gradle 모듈 `:webauthn` (순수 Java, Spring/프레임워크 의존 없음, JDK `java.security`만 사용).

```
webauthn/                              ← 새 Gradle 모듈 (:webauthn)
└─ src/main/java/com/crosscert/passkey/webauthn/
   ├─ cbor/         CBOR 디코더 (RFC 8949 subset — WebAuthn에 필요한 만큼)
   ├─ cose/         COSE 공개키 파싱 (ES256/RS256/… → java.security.PublicKey)
   ├─ authdata/     AuthenticatorData 파서 (rpIdHash, flags(UP/UV/BE/BS/AT/ED), signCount, attestedCredentialData)
   ├─ attestation/  AttestationVerifier 인터페이스 + 포맷별 구현
   ├─ verifier/     WebAuthnVerifier 인터페이스 (앱 진입점) + Input/Result DTO + 예외
   └─ trust/        X.509 체인 검증 (CertPathValidator 래핑) + TrustAnchorProvider
```

**핵심 경계 = `WebAuthnVerifier` 인터페이스.** 앱은 이 인터페이스에만 의존. 구현체 2개:

- `NativeWebAuthnVerifier` — 자체 구현 (최종 프로덕션, 기본값)
- `Webauthn4jVerifier` — 기존 webauthn4j 위임 (과도기 differential 테스트용). **`:webauthn` 모듈의 `testImplementation` 영역에만 존재** → 프로덕션 classpath 오염 없음.

**데이터 흐름** (registration/finish 기준):

```
RegistrationController (변경 없음)
  └→ RegistrationFinishService (본문만 변경)
       └→ WebAuthnVerifier.verifyRegistration(input)        ← 인터페이스
            └→ NativeWebAuthnVerifier
                 ├→ CBOR 디코드 → attestationObject {fmt, authData, attStmt}
                 ├→ AuthenticatorData 파싱 (rpIdHash 대조, flags 검사)
                 ├→ CollectedClientData 검증 (type/challenge/origin)
                 ├→ AttestationVerifier(fmt) → 서명·trust chain (JDK Signature/CertPath)
                 └→ RegistrationResult(credId, cosePublicKey, signCount, aaguid, fmt, transports, flags)
       └→ 자체 저장 스키마로 Credential 영속화
       └→ (앱 유지) MdsVerifier·AAGUID 정책 검사 / signCount 단조증가 검증
```

## 6. `WebAuthnVerifier` 인터페이스 계약

webauthn4j 타입이 이 경계를 절대 넘지 않도록, 입출력은 전부 `:webauthn` 자체 타입(원시 byte[]·자체 enum)으로 정의한다.

```java
package com.crosscert.passkey.webauthn.verifier;

public interface WebAuthnVerifier {
    RegistrationResult verifyRegistration(RegistrationInput input) throws WebAuthnVerificationException;
    AuthenticationResult verifyAuthentication(AuthenticationInput input) throws WebAuthnVerificationException;
}
```

**입력 (앱 → verifier)** — 앱이 가진 raw 재료만:

```java
record RegistrationInput(
    String credentialJson,          // 클라가 보낸 PublicKeyCredential JSON (그대로)
    byte[] challenge,               // 서버 발급 challenge (검증 기준)
    Set<String> allowedOrigins,     // 테넌트 origin 화이트리스트
    String rpId,                    // 테넌트 rpId
    boolean userVerificationRequired,
    Set<COSEAlgorithm> allowedAlgorithms,      // ES256, RS256
    Set<String> acceptedAttestationFormats,    // none, packed, ... (테넌트 정책)
    AttestationTrustPolicy trustPolicy         // NONE_ONLY / SELF_ALLOWED / TRUST_CHAIN_REQUIRED
) {}

record AuthenticationInput(
    String credentialJson,
    byte[] challenge,
    Set<String> allowedOrigins,
    String rpId,
    boolean userVerificationRequired,
    StoredCredential storedCredential   // DB에서 로드 (자체 저장 스키마)
) {}
```

**출력 (verifier → 앱)** — 앱이 저장·후처리에 쓸 결과만:

```java
record RegistrationResult(
    byte[] credentialId,
    byte[] cosePublicKey,           // 자체 저장 스키마의 핵심 (COSE_Key CBOR)
    long signCount,
    byte[] aaguid,
    String attestationFormat,
    Set<String> transports,
    boolean uvVerified, boolean upVerified, boolean backupEligible, boolean backupState
) {}

record AuthenticationResult(
    byte[] credentialId,
    long newSignCount,              // 앱이 단조증가 검증·갱신
    boolean uvVerified, boolean upVerified, boolean backupState
) {}
```

**오류 처리**: 검증 실패 시 단일 체크 예외 `WebAuthnVerificationException`(`Reason` enum 포함).
Reason 예: `BAD_SIGNATURE`, `ORIGIN_MISMATCH`, `CHALLENGE_MISMATCH`, `RP_ID_HASH_MISMATCH`,
`UV_REQUIRED`, `UP_REQUIRED`, `UNSUPPORTED_ALGORITHM`, `ATTESTATION_UNTRUSTED`,
`UNSUPPORTED_ATTESTATION_FORMAT`, `MALFORMED_INPUT`.
앱 서비스는 이 reason을 기존 `ErrorCode`로 매핑 — **현재 webauthn4j 예외를 잡던 위치와 동일한 자리**라 앱 변경 최소.

**책임 분리 (앱에 유지)**:

- `signCount` 단조증가 검증·DB 갱신 → 앱 (verifier는 `newSignCount`만 제공)
- MDS/AAGUID 정책 검사 → 앱 (verifier는 `aaguid` 추출만 제공) → MDS 서브프로젝트와 깔끔히 분리

## 7. 자체 구현 내부 (검증 파이프라인)

**공통 빌딩블록** (포맷·세리머니 무관, 먼저 구현·검증):

1. `CborDecoder` — RFC 8949 subset (map/array/int/bytes/text/tag). 보안 민감: 깊이 제한, 길이 검증, 잉여 바이트 거부.
2. `CoseKeyParser` — COSE_Key map → `java.security.PublicKey`. kty/alg/crv 매핑(EC2 P-256→ES256, RSA→RS256). JDK `KeyFactory`로 복원.
3. `AuthenticatorDataParser` — 고정 오프셋: rpIdHash(32) ‖ flags(1) ‖ signCount(4) ‖ [attestedCredentialData] ‖ [extensions]. attestedCredentialData = aaguid(16) ‖ credIdLen(2) ‖ credId ‖ COSE_Key.
4. `ClientDataValidator` — clientDataJSON 파싱 → type(`webauthn.create`/`webauthn.get`) ‖ challenge(base64url 비교) ‖ origin(화이트리스트 ∈).

**등록 파이프라인** (`verifyRegistration`):

```
credentialJson → attestationObject(CBOR) → {fmt, authData, attStmt}
 ├ AuthenticatorData: rpIdHash == SHA-256(rpId)?  flags.AT==1?  UV 정책?  flags.UP==1?
 ├ ClientData: type=="webauthn.create", challenge·origin 일치?
 ├ COSE alg ∈ allowedAlgorithms?
 ├ AttestationVerifier[fmt].verify(authData, attStmt, clientDataHash) → trust 판정
 │     (clientDataHash = SHA-256(rawClientDataJSON))
 └ RegistrationResult(credId, cosePublicKey, signCount, aaguid, fmt, transports, flags)
```

**인증 파이프라인** (`verifyAuthentication`):

```
credentialJson → {authenticatorData, signature, clientDataJSON, credentialId}
 ├ ClientData: type=="webauthn.get", challenge·origin 일치?
 ├ AuthenticatorData: rpIdHash 일치?  flags.UP==1?  UV 정책?
 ├ storedCredential.cosePublicKey → PublicKey 복원
 ├ Signature.verify(pubKey, authData ‖ SHA-256(clientDataJSON), signature)   ← JDK
 └ AuthenticationResult(credId, newSignCount=authData.signCount, flags)
```

**Attestation 포맷별 검증기** (`AttestationVerifier` 인터페이스, 포맷마다 1구현):

| 포맷 | 핵심 검증 | JDK 사용 |
|------|----------|----------|
| `none` | attStmt 비어있음 확인 | — |
| `packed` | self: 자기 공개키로 sig 검증 / x5c: leaf cert로 sig 검증 + AAGUID 일치 + 체인 | Signature, CertPath |
| `fido-u2f` | U2F 공개키 포맷 변환 후 sig 검증 + 체인 | Signature, CertPath |
| `tpm` | pubArea/certInfo 파싱, TPMS_ATTEST 구조, AIK cert 체인 (가장 큼) | Signature, CertPath |
| `android-key` | extension OID 검증 + leaf cert sig | CertPath |
| `android-safetynet` | JWS(response) 파싱·서명 검증 + nonce | Signature, JWS |
| `apple` | nonce extension(SHA-256(authData‖clientDataHash)) + 체인 | CertPath |

**Trust chain**: `TRUST_CHAIN_REQUIRED`일 때 `trust/`가 `CertPathValidator`로 leaf→루트 검증.
루트 신뢰 앵커는 본래 MDS에서 오지만, MDS는 후속 서브프로젝트이므로 **`TrustAnchorProvider` 인터페이스로 추상화**한다.
이번 범위에서는 설정 기반 기본 구현(빈 셋 = self/none만 통과)을 제공하고, MDS 서브프로젝트가 이 provider를 구현해 끼운다.

**COSE 알고리즘 제약**: EdDSA/Ed25519 등 일부 알고리즘은 JDK 버전에 따라 미지원일 수 있음.
이번 범위는 `allowedAlgorithms`(ES256, RS256)에 한정하며, 미지원 알고리즘 입력은 `UNSUPPORTED_ALGORITHM`으로 거부한다.

## 8. 저장 스키마 변경

기존 데이터 없음 확정 → 마이그레이션 불요. 통짜 `publicKey` BLOB 엔벨로프(ao/cd/ce/tr) 대신 개별 필드:

- `cose_public_key` (BLOB, COSE_Key CBOR) — 인증 시 서명검증 키
- `sign_count`, `aaguid`, `attestation_fmt`, `transports` — 기존 컬럼 재사용
- attestationObject/clientData 전체 저장은 중단 (감사용 원본 컬럼은 YAGNI로 이번엔 제외)
- DDL: 신규 Flyway 마이그레이션으로 컬럼 정의. 기존 데이터 없으므로 단순 정의.

## 9. 검증 & 전환 전략

**테스트 구조** (`webauthn/src/test/`):

1. **Differential 테스트** — 동일 test vector를 `NativeWebAuthnVerifier`와 `Webauthn4jVerifier` 양쪽에 주입 → 판정(통과/거부+reason)·추출값(credId/pubKey/signCount/aaguid) 일치 단언. webauthn4j는 `testImplementation`에만 존재.
2. **공식 test vector** — `webauthn/src/test/resources/vectors/`. W3C spec 예제 + FIDO conformance 샘플을 포맷별 픽스처로. 각 포맷 최소 1 happy-path + 변조(서명·challenge·origin·rpIdHash) negative.
3. **단위 테스트** — 빌딩블록별(CborDecoder/CoseKeyParser/AuthenticatorDataParser/ClientDataValidator). 악성 입력 방어 포함.
4. **E2E 회귀** — 기존 passkey-app finish 통합테스트(`Fido2TestAuthenticator` 기반)가 verifier 교체 후에도 green. (이 에뮬레이터는 webauthn4j-test 사용 — 테스트 영역이라 이번 범위에서 유지)

**전환(스위치) 메커니즘**:

- Spring 빈 선택: `passkey-app`이 `WebAuthnVerifier` 빈 1개를 주입. 플래그 `passkey.webauthn.verifier=native|webauthn4j` (기본 `native`).
- 과도기: `webauthn4j` 값으로 즉시 롤백 가능. differential + E2E green 확인 후 플래그 제거하고 `native` 고정 → `Webauthn4jVerifier`를 test 영역에만 남김.

**구현·검증 순서** (TDD: red→green→refactor, 각 단계 differential + 공식 벡터 green 후 다음):
공통 빌딩블록 → none → packed → 인증 파이프라인 → fido-u2f → apple → android-key → android-safetynet → tpm.

## 10. 작업 경계 요약

- ✅ 변경: `:webauthn` 새 모듈 / `RegistrationFinishService`·`AuthenticationFinishService` 본문 / `Credential` 엔티티·저장 로직 / Flyway DDL / 빈 구성(`Webauthn4jConfig` → verifier 빈)
- ❌ 무변경: 컨트롤러·API 계약·DTO / `*StartService` / MDS 코드 / admin-app / 클라이언트 SDK
- ⏭ 후속 서브프로젝트: MDS 자체구현(`TrustAnchorProvider` 구현 포함), 테스트 authenticator 에뮬레이터 자체구현 → 이 둘 완료 시 webauthn4j **완전 제거**

## 11. 모듈 단위 정의 (isolation & clarity)

| 단위 | 역할 | 입력 | 출력 | 의존 |
|------|------|------|------|------|
| `CborDecoder` | CBOR 바이트 디코드 | byte[] | 자체 CborValue 트리 | 없음 |
| `CoseKeyParser` | COSE_Key → PublicKey | CborValue map | java.security.PublicKey | JDK KeyFactory |
| `AuthenticatorDataParser` | authData 파싱 | byte[] | AuthenticatorData record | 없음 |
| `ClientDataValidator` | clientDataJSON 검증 | byte[], 기대값 | 검증 통과/예외 | JSON 파서 |
| `AttestationVerifier` | 포맷별 attestation 검증 | authData, attStmt, clientDataHash | trust 판정 | Signature/CertPath, TrustAnchorProvider |
| `TrustAnchorProvider` | 루트 신뢰 앵커 제공 | aaguid/fmt | Set<TrustAnchor> | (MDS 서브프로젝트가 구현) |
| `WebAuthnVerifier` | 세리머니 오케스트레이션 | Registration/Authentication Input | Result | 위 전부 |

각 단위는 단일 책임이며, 인터페이스로 통신하고 독립 테스트 가능하다.
