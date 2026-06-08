# MDS(FIDO Metadata Service) 자체 구현 — webauthn4j-metadata 의존 제거

- **작성일**: 2026-06-08
- **상태**: 설계 승인 대기 → 구현 계획 작성 예정
- **서브프로젝트**: WebAuthn 자체구현 후속 2/3 (MDS 통합). 선행 = 등록/인증 검증 코어(완료, `:webauthn` 모듈, merge `4de7229`). 후속 = 테스트 authenticator 에뮬레이터 자체구현.

## 1. 배경 & 동기

선행 서브프로젝트에서 WebAuthn 등록/인증 검증 코어를 webauthn4j 없이 자체 구현(`:webauthn` 모듈)했다. 그러나 webauthn4j는 admin-app의 MDS(FIDO Metadata Service) 통합에 여전히 남아 있다 — `core`가 `api(webauthn4j-metadata)`로 노출하고, admin-app의 `MdsBlobClient`/`MdsBlobStore`/`MdsSchedulerService`가 `com.webauthn4j.metadata.*`를 사용한다.

이 서브프로젝트는 MDS3 BLOB 다운로드·JWS 검증·파싱을 W3C/FIDO spec 직접 구현으로 대체해 webauthn4j-metadata 의존을 제거한다.

핵심 동기 (사용자 확정):

1. **webauthn4j-metadata 의존 제거** — `core`의 `api(webauthn4j-metadata)` 제거. 완료 시 webauthn4j는 프로덕션 런타임에서 완전히 사라지고 테스트 에뮬레이터(후속 서브프로젝트)만 남는다.
2. **실제 BLOB 다운로드 동작** — 현재 BLOB 다운로드/검증은 webauthn4j로 동작 중이나 원본 JWT를 DB에 저장하지 않는다(`blob_jwt = "{}"` 플레이스홀더, Phase 3 미완성). 자체 구현하며 원본 JWT를 저장해 감사·재검증 가능하게 한다.
3. **검증 정확성/감사** — JWS 서명·X.509 체인 검증을 직접 소유해 FIDO MDS3 spec 준수를 자체 명시.

## 2. 범위 (확정)

| 항목 | 결정 |
|------|------|
| 1차 목표 | webauthn4j-metadata 의존 제거 + 실제 BLOB 다운로드 동작 + 검증 정확성/감사 |
| JWS·X.509 검증 도구 | **JDK java.security 자체 구현** (`:webauthn` 코어와 일관, 외부 의존 0). 기존 `CertPathVerifier`(PKIX)·`JwsEcdsa`(ES256 raw↔DER) 재사용 |
| 코드 위치 | **`:webauthn` 모듈에 `mds` 패키지 확장** (순수 Java, 네트워크 무의존) |
| blob_jwt 저장 | **원본 JWT 저장** (현재 `"{}"` 플레이스홀더 대체). 스키마 변경 없음(blob_jwt CLOB 재사용) |
| status 토큰 | **원문 문자열 보존** (enum 강제 안 함 — MDS3 진화에 견고; passkey-app MdsVerifier가 이미 문자열 BLOCKING 목록으로 판정) |
| 검증 전략 | **① webauthn4j-metadata differential(MetadataBLOBFactory로 동일 rawJwt 파싱 대조) + ② FIDO 실제/공식 BLOB 고정 vector + ③ 자체 서명 BLOB(TestCa)** |
| 접근법 | **A — 어댑터 인터페이스 + 모듈 격리** (선행 서브프로젝트와 동일 패턴) |
| HTTP 다운로드 | **호출자(admin-app)가 담당** — `:webauthn`은 네트워크 무의존 순수 검증·파싱 모듈로 유지 |

**범위 밖 (후속/무관)**:

- 테스트 authenticator 에뮬레이터(`Fido2TestAuthenticator`, webauthn4j-test) 자체 구현 — 별도 후속 서브프로젝트.
- MDS BLOB의 metadataStatement 전체 파싱(인증서 디스크립터·biometric 등) — 현재 소비처가 쓰지 않음(YAGNI). aaguid + statusReports만 파싱.
- 스케줄러·leader election·Redis 캐시·DB 스키마 — 이미 자체 구현, 무변경.

## 3. 사전 조사 결과 (현재 webauthn4j-metadata 의존 지점)

- 빌드: `core/build.gradle.kts`가 `api(rootProject.libs.webauthn4j.metadata)` 노출 → admin-app·passkey-app에 전이.
- **admin-app/src/main 3파일만 webauthn4j-metadata 사용**:
  - `MdsBlobClient.java` — `FidoMDS3MetadataBLOBProvider`(다운로드+JWS검증+체인검증), `MetadataBLOBProvider.provide()`, `MetadataBLOB`. `Wiring.fidoMds3MetadataBLOBProvider` 빈.
  - `MdsBlobStore.java` — `MetadataBLOB.getPayload().getNo()/.getNextUpdate()`. **`store(rawJwt, blob)`의 rawJwt에 현재 `"{}"` 저장**(Phase 3 미완성).
  - `MdsSchedulerService.java` — `blob.getPayload().getEntries()` 순회, `entry.getAaguid()`, `entry.getStatusReports()`, `sr.getStatus().getValue()`.
- **자체 구현 완료(무변경)**:
  - `MdsVerifier`(passkey-app) — status 판정(BLOCKING 문자열 목록), webauthn4j 의존 **없음**.
  - `MdsAaguidCache`(core) — Redis `mds:aaguid:<UUID>` → CSV 조회.
  - 스케줄러(`MdsSyncJob` 6h fixed-delay), leader election(`SchedulerLeaseService`), DB(`mds_blob_cache`/`mds_sync_history`).
- 설정: `passkey.mds.blob-endpoint`(기본 mds3.fidoalliance.org), `passkey.mds.root-cert`(기본 classpath:fido/fido-mds-root.crt → `MdsRootCertProvider`).
- 현재 동작 실태: `FidoMDS3MetadataBLOBProvider.provide()`가 실제 HTTPS 다운로드+JWS검증+체인검증 수행 중. 기능은 동작하나 원본 JWT 미저장.

## 4. 아키텍처 — 접근법 A (어댑터 + 모듈 격리, 다운로드/검증 분리)

`:webauthn` 모듈에 `mds` 패키지 추가 (네트워크·Spring·Redis·DB 무의존, JDK + Jackson만).

```
webauthn/src/main/java/com/crosscert/passkey/webauthn/mds/
├─ MdsJws.java                — JWS compact(header.payload.sig) 파싱, x5c·alg 추출
├─ MdsJwsVerifier.java        — JWS 서명검증(JDK Signature) + x5c 체인검증(CertPathVerifier 재사용)
├─ MdsBlob.java               — POJO: no, nextUpdate, entries
├─ MdsBlobEntry.java          — aaguid(nullable), statusReports
├─ MdsStatusReport.java       — status(원문 String), effectiveDate
├─ MdsBlobParser.java         — JWS payload JSON → MdsBlob (Jackson)
├─ MdsException.java          — 검증 실패 (Reason enum)
├─ MetadataBlobVerifier.java  — 인터페이스(진입점): MdsBlob verify(String rawJwt, Set<TrustAnchor>)
└─ NativeMetadataBlobVerifier.java — 자체 구현 오케스트레이션
```

**핵심 경계 = `MetadataBlobVerifier`** (네트워크 무의존). admin-app `MdsBlobClient`가 이 인터페이스에만 의존. 구현체 둘:

- `NativeMetadataBlobVerifier` — 자체 구현 (프로덕션, 기본값)
- `Webauthn4jMetadataBlobVerifier` — webauthn4j `MetadataBLOBFactory` 위임 (differential 테스트 전용, `:webauthn`의 testImplementation에만)

**데이터 흐름** (스케줄러 sync 1회):

```
MdsSchedulerService.runOnce()                       (admin-app)
  └→ MdsBlobClient.fetch() → FetchResult(rawJwt, MdsBlob)
       ├→ HTTP GET endpoint → rawJwt(String)         (admin-app: java.net.http.HttpClient)
       └→ MetadataBlobVerifier.verify(rawJwt, rootProvider.anchors())  (:webauthn)
            ├→ MdsJws.parse(rawJwt) → {header(x5c, alg), payload64, signature}
            ├→ MdsJwsVerifier: leaf(x5c[0])로 서명검증 + CertPathVerifier로 x5c→root 체인검증
            └→ MdsBlobParser: payload JSON → MdsBlob (no/nextUpdate/entries)
  └→ store.store(rawJwt, blob)                        (rawJwt 원본 저장 ← '{}' 대체)
  └→ blob.entries() 순회 → Redis AAGUID 캐시(원문 status CSV)  (자체 POJO 소비)
```

**의존 방향 변화**:
- `core`의 `api(webauthn4j-metadata)` **제거**.
- admin-app에 `implementation(project(":webauthn"))` 추가. MDS POJO·인터페이스를 `:webauthn`에서 가져옴.
- passkey-app `MdsVerifier`·`MdsAaguidCache`는 Redis CSV(문자열) 소비 → **무변경**.

## 5. `MetadataBlobVerifier` 계약 + 자체 POJO

```java
package com.crosscert.passkey.webauthn.mds;

public interface MetadataBlobVerifier {
    /**
     * MDS3 BLOB JWT를 검증·파싱한다. JWS 서명을 x5c leaf로 검증하고,
     * x5c 체인을 trustAnchors(FIDO root)까지 PKIX 검증한 뒤 payload를 파싱한다.
     * 네트워크 접근 없음 — rawJwt는 호출자가 다운로드해 넘긴다.
     */
    MdsBlob verify(String rawJwt, java.util.Set<java.security.cert.TrustAnchor> trustAnchors)
            throws MdsException;
}
```

**자체 POJO** (webauthn4j MetadataBLOB 계열 대체 — 소비처가 쓰는 필드만):

```java
/** MDS3 BLOB payload (FIDO MDS3 §3.1.6). */
public record MdsBlob(int no, java.time.LocalDate nextUpdate, java.util.List<MdsBlobEntry> entries) {}

/** BLOB entry (§3.1.1). aaguid는 null 가능(legacy U2F → 소비처가 스킵). */
public record MdsBlobEntry(byte[] aaguid, java.util.List<MdsStatusReport> statusReports) {}

/** status report (§3.1.3). status는 MDS3 원문 토큰 그대로 보존(미지 값도 거부 안 함). */
public record MdsStatusReport(String status, java.time.LocalDate effectiveDate) {}
```

**status enum 없음**: passkey-app `MdsVerifier`가 이미 문자열 BLOCKING 목록으로 판정하므로 enum 불필요(YAGNI). 미지 status 토큰은 원문 문자열로 보존 → MDS3가 새 토큰을 추가해도 BLOB 파싱이 깨지지 않음. admin-app은 `sr.status()`(원문) CSV로 캐시.

**예외**: `MdsException` (Reason enum: `MALFORMED_JWS`, `BAD_SIGNATURE`, `UNTRUSTED_CHAIN`, `MALFORMED_PAYLOAD`). admin-app `MdsBlobClient.fetch()`가 이를 잡아 기존처럼 `IllegalStateException("MDS fetch failed")`로 변환(현재 동작·로깅 보존).

**소비처 매핑** (조사 결과 기준):
- `MdsBlobStore.store`: `getPayload().getNo()` → `blob.no()`, `.getNextUpdate()` → `blob.nextUpdate()`
- `MdsSchedulerService`: `getEntries()` → `entries()`, `entry.getAaguid()` → `entry.aaguid()`, `entry.getStatusReports()` → `entry.statusReports()`, `sr.getStatus().getValue()` → `sr.status()`

## 6. JWS 검증 파이프라인 (`:webauthn` 내부)

`NativeMetadataBlobVerifier.verify(rawJwt, anchors)` 단계 (RFC 7515 JWS + FIDO MDS3 §3.2):

1. **`MdsJws.parse`** — `header.payload.signature`를 '.'로 3분할(정확히 3파트, no-pad base64url). header JSON → `alg`(RS256/ES256만; 그 외 `MALFORMED_JWS`), `x5c`(base64 STANDARD DER cert 배열; 비어있으면 `MALFORMED_JWS`).
2. **`MdsJwsVerifier`**:
   - leaf=x5c[0]로 서명 검증: signingInput = `header64 + "." + payload64`(ASCII), alg→JDK Signature(SHA256withRSA/ECDSA). ES256는 raw R‖S→DER 변환(`JwsEcdsa` 재사용). 실패 → `BAD_SIGNATURE`.
   - x5c 체인을 `CertPathVerifier`(기존 PKIX)로 trustAnchors까지 검증. 실패 → `UNTRUSTED_CHAIN`.
3. **`MdsBlobParser`** — payload JSON(Jackson) → `MdsBlob`. `no`/`nextUpdate`/`entries[].aaguid`(hex/UUID 문자열 → 16byte; 없으면 null)/`entries[].statusReports[].status`(원문)/`effectiveDate`(nullable). 형식 오류 → `MALFORMED_PAYLOAD`.

**기존 자산 재사용**: `CertPathVerifier`(Task 8), `JwsEcdsa`(Task 28), DER/base64url 오버플로·예외 정규화 교훈.

**trust anchor 출처**: admin-app `MdsRootCertProvider`가 `fido-mds-root.crt`(classpath) → `Set<TrustAnchor>`(`anchors()` 메서드 추가)로 만들어 `verify(...)`에 주입. MDS root cert 파일은 admin-app 리소스에 유지(`:webauthn` 리소스 무의존).

## 7. admin-app 통합

1. **`MdsBlobClient`** 재작성: webauthn4j provider 제거. `fetch()` → `java.net.http.HttpClient` GET → rawJwt → `MetadataBlobVerifier.verify(rawJwt, rootProvider.anchors())` → `FetchResult(rawJwt, MdsBlob)`. `Wiring`의 `fidoMds3MetadataBLOBProvider` 빈 제거 → `NativeMetadataBlobVerifier` 빈 + `HttpClient`. 기존 로깅(url/entries/durMs, signature-fail WARN) 보존.
2. **`MdsRootCertProvider`**: `Set<TrustAnchor> anchors()` 추가(기존 cert 로드 재사용). `get()`은 호환 위해 유지하거나 제거.
3. **`MdsBlobStore.store`**: 시그니처 자체 `MdsBlob`로. `blob.no()`/`blob.nextUpdate()`. **rawJwt에 원본 JWT 저장**(현재 `"{}"` → 실제 JWT). 스키마 변경 없음.
4. **`MdsSchedulerService`**: `MdsBlob` 소비. `client.fetch()`가 `FetchResult` 반환 → `store.store(result.rawJwt(), result.blob())`. `entry.aaguid()`/`entry.statusReports()`/`sr.status()`(원문) → Redis CSV.
5. **빌드**: `core/build.gradle.kts`의 `api(webauthn4j-metadata)` 제거. admin-app에 `implementation(project(":webauthn"))` 추가. (admin-app은 이미 core 의존; passkey-app은 이미 `:webauthn` 의존.)

## 8. 검증 & 전환 전략

1. **Differential** (`:webauthn` testImplementation):
   - `Webauthn4jMetadataBlobVerifier` — webauthn4j `MetadataBLOBFactory`로 동일 rawJwt를 파싱(다운로드 없이 로컬 BLOB 파싱+검증 분리 가능 — 조사로 `MetadataBLOBFactory` 존재 확인). `MetadataBlobVerifier` 인터페이스 구현.
   - `MetadataBlobDifferentialTest`: 고정 BLOB JWT vector를 양쪽에 넣어 `MdsBlob` 필드(no/nextUpdate/entries수/AAGUID/status) 일치 단언.
2. **실제/공식 BLOB vector**: FIDO conformance 샘플 또는 mds3.fidoalliance.org 실제 BLOB 스냅샷을 `webauthn/src/test/resources/mds/`에 저장. 실제 FIDO root로 서명검증·파싱·변조거부(서명 1바이트 flip → BAD_SIGNATURE) 테스트.
3. **자체 서명 BLOB** (BouncyCastle `TestCa` 재사용): 테스트 root CA로 서명한 BLOB JWT로 happy/체인거부/변조거부 — 양·음성 통제.
4. **회귀**: admin-app `MdsSchedulerIT`(자체 POJO로 fixture 전환)·`MdsVerifierTest` green. fetch→store→cache 흐름 유지.

**Differential 분리 방식**: 조사 결과 webauthn4j-metadata에 `MetadataBLOBFactory`(로컬 rawJwt → MetadataBLOB)가 존재하므로, WireMock 없이 `MetadataBLOBFactory`로 동일 rawJwt를 파싱해 자체 결과와 대조한다. 만약 0.31.5 API가 서명검증을 팩토리에 묶지 않아 검증까지 함께 못 돌리면, 파싱 결과(no/nextUpdate/entries/AAGUID/status) 대조에 집중한다(서명검증 정확성은 vector·자체서명 테스트가 담당).

## 9. 작업 경계 요약

- ✅ 변경: `:webauthn` mds 패키지 신설 / admin-app `MdsBlobClient`·`MdsBlobStore`·`MdsSchedulerService`·`MdsRootCertProvider` / `core`·admin-app build.gradle / `MdsSchedulerIT` fixture(자체 POJO)
- ❌ 무변경: passkey-app `MdsVerifier`·`MdsAaguidCache`(Redis CSV 소비) / DB 스키마(blob_jwt CLOB 재사용) / Tenant mdsRequired / 스케줄러 leader election / `MdsSyncJob`
- ⏭ 이번 완료 시: **core의 webauthn4j-metadata api 제거** → webauthn4j는 (a) `:webauthn` testImplementation(differential), (b) passkey-app testImplementation(Fido2TestAuthenticator) 둘 뿐. **프로덕션 런타임에서 webauthn4j 완전 제거 달성** (테스트 에뮬레이터만 후속 서브프로젝트).

## 10. 모듈 단위 정의 (isolation & clarity)

| 단위 | 역할 | 입력 | 출력 | 의존 |
|------|------|------|------|------|
| `MdsJws` | JWS compact 파싱 | rawJwt String | header/payload64/sig/x5c | base64url 디코드 |
| `MdsJwsVerifier` | 서명 + 체인 검증 | MdsJws, anchors | 검증 통과/예외 | JDK Signature, CertPathVerifier, JwsEcdsa |
| `MdsBlobParser` | payload → MdsBlob | payload JSON | MdsBlob | Jackson |
| `MetadataBlobVerifier` | 검증·파싱 오케스트레이션 | rawJwt, anchors | MdsBlob | 위 전부 |
| `MdsBlobClient` (admin-app) | HTTP 다운로드 + 검증 호출 | endpoint, anchors | FetchResult(rawJwt, MdsBlob) | HttpClient, MetadataBlobVerifier, MdsRootCertProvider |

각 단위는 단일 책임이며, 인터페이스로 통신하고 독립 테스트 가능하다. `:webauthn`의 MDS 검증은 네트워크·프레임워크 무의존이라 rawJwt 문자열만으로 단위 테스트된다.
