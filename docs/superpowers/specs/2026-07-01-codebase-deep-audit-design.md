# 코드베이스 심층 감사 — 신규 발견 30건 (2026-07-01)

- **작성일**: 2026-07-01
- **방법론**: 서버 6개 모듈(core/passkey-app/admin-app/webauthn/sdk-java/rp-app, ~46K LOC)을
  code-quality·security·performance 3관점으로 모듈×관점 fan-out(6 subagent) → 기존 백로그
  124건(2026-05-31 10-lens 94건 + 2026-06-10 보안감사 30건) 제외 → 적대적 검증(2 fork, 코드 정독).
- **결과**: 37 후보 → **30 CONFIRMED** (거짓양성 4 + 강등 3 제거).
- **요구 제약**: "기존 작동하는 기능에는 변화가 없게" → **A그룹(동작 불변 14건)만 구현**,
  B그룹(동작 변경 16건)은 본 문서에 백로그로 기록만 한다.

## 실행 결과 (2026-07-01, worktree deep-audit-groupA, base de32596)

A그룹 14건을 subagent-driven 3-gate(spec+quality+codex)로 실행. **11건 구현 완료, 3건 B그룹 이관.**

| Task | 결과 | 비고 |
|------|------|------|
| F12 @PreAuthorize | ✅ | codex P1=false positive(TenantBoundary 반환이 Optional이라 Mockito 기본 empty), 방어 stub 추가 |
| F17 canonicalAaguid 가드 | ✅ | 순수 단위테스트 |
| F36 fido-u2f ES256 | ✅ | defense-in-depth(파서 부수효과 의존 분리) |
| F29 amr 정규화 | ✅ | 7→Long heap pollution 재현·차단 |
| F09 activity slug 배치 | ✅ | deleted fallback IT 신규, Testcontainers green |
| F19 challenge freshness | ✅ | Redis TTL과 동일 윈도우 공유, 정상경로 불변 |
| F34 CertPath 시각주입 | ✅ | 3-인자 오버로드, 2-인자는 Instant.now() 위임(호출부 불변) |
| F32 SafetyNet alg핀 | ✅ | alg-confusion 회귀 표면 차단 |
| F33 TPM alg검증 | ✅ | F32와 동형 |
| F37 ObjectMapper 통일 | ✅ | JsonMappers.secure(), 설정 강화는 안 함(동작 불변) |
| F16 LicenseBootstrap set 제거 | ✅ | 부트 소비처 0 검증(case B) |
| **F22 rename 비잠금** | **⛔ 롤백→B그룹** | codex P1 적발: Credential에 @Version/@DynamicUpdate 부재→전체컬럼 UPDATE가 동시 /authentication/finish의 signCount를 lost-update(replay 보호 약화). spec/quality 2명 놓침. **동작 불변 아님** |
| **F21 scope 캐시** | **⏭ B그룹 이관** | Caffeine 의존 부재(도입=동작불변 밖) + scope mutation이 admin-app 별프로세스라 cross-process evict 불가→TTL stale=동작변경 |
| **F27 JWKS 복원력** | ✅ (단 ⚠) | single-flight+stale 폴백. codex 4라운드 수렴(백오프 실패시각·성공 timestamp·stale 무한→TTL+grace 15분 상한·backoff 경로 일관화). **⚠ JWKS 서버 장애 시 동작 변경**(기존 예외 전파→stale 폴백 bounded). happy path·최초부팅 실패는 불변. 사용자 승인하 유지 |

**핵심 교훈**: ① Credential 락 제거는 전체컬럼 UPDATE 모델에서 항상 lost-update 의심(F22). ② codex 독립 게이트가 spec/quality 공유 맹점을 2회 적발(F22 lost-update, F27 백오프/stale 엣지). ③ "동작 불변" 판정은 정상경로뿐 아니라 장애·동시성 경로까지 봐야 정확(F27이 장애시 변경).

## 제거된 후보 (검증 기록)

- **F23 (REJECTED, 거짓양성)** — RegistrationFinishService:107 의 포맷 재검사는 verifier가
  *반환한* attestationFormat을 테넌트 정책에 대조하는 별개의 의도된 가드(중복 아님).
- **F03·F05 (REJECTED)** — MfaController 주석이 enroll-abandon 강등(126-131)과 공유 락아웃
  카운터(71-75)를 *의도적 설계 트레이드오프*로 명시. 결함 아님.
- **F13 (REJECTED, 거짓양성)** — TenantFilterAspect의 @Around는 단일 @Transactional 스코프이고
  open-in-view=false라 트랜잭션당 새 Session+filter가 생성·폐기됨 → "재사용 세션 stale filter"
  시나리오 미발생. tid==null cross-tenant는 주석에 명시된 의도. (TenantFilterAspectIT 존재.)
- **F18 (DOWNGRADED)** — known `sec-apikeyfilter-no-defensive-clear`의 잔여분. 원 finding(clear
  부재)은 line 137 entry-clear로 해소됨; 비-RP 경로 미clear는 잔여 갭이나 RP 경로 finally가
  실가드라 info급. 기존 항목의 잔여로 취급.
- **F24 (DOWNGRADED, info)** — RateLimitFilter.resolve() 2회 호출은 별개 Servlet 라이프사이클
  훅(shouldNotFilter/doFilterInternal)이고 비용 미미.
- **F28 (DOWNGRADED, info)** — InMemoryUserStore 비원자성은 실재하나 클래스 javadoc이 명시적
  데모 스토어(고객사 DB 교체 전제). 포팅 가이드 노트로 격하.

---

## A그룹 — 동작 불변 개선 (구현 대상, 14건)

각 항목은 관찰 가능한 출력/응답/순서/동작을 바꾸지 않는다(검증 fork가 none으로 재확인).

### A-1. Trivial (7건)

#### F12 [security/low] Credential 조회 GET에 @PreAuthorize 누락
- **위치**: `admin-app/.../credential/CredentialAdminController.java:31(list), :40(authEvents)`
- **현상**: revoke(:49)와 형제 컨트롤러(ApiKey/Tenant/Funnel/Activity) 전부 메서드 `@PreAuthorize`를
  다는데 이 두 GET만 없음. `/admin/api/**`는 `.authenticated()`만 강제 → 인가가 서비스
  `tenantBoundary.assertCanAccessTenant()` 한 겹에만 의존.
- **수정**: 두 GET에 `@PreAuthorize("hasAnyRole('PLATFORM_OPERATOR','RP_ADMIN')")` 추가.
  서비스 boundary와 동일 결과 → 동작 불변, 방어선만 이중화.

#### F17 [code-quality/low] canonicalAaguid 길이 가드 부재
- **위치**: `core/.../mds/MdsAaguidCache.java:44`
- **현상**: `aaguid[0..15]` 읽기에 null/length 체크 없음. <16바이트 입력 시
  ArrayIndexOutOfBoundsException(→500). lookup/put/canonicalAaguid 모두 public 진입점.
- **수정**: 상단에 `if (aaguid == null || aaguid.length != 16) throw new IllegalArgumentException(...)`.
  정상 16바이트 입력은 불변, 500 대신 typed error.

#### F09 [performance/low] Activity feed slug N회 findById
- **위치**: `admin-app/.../activity/ActivityService.java:105`
- **현상**: distinct 테넌트마다 `tenants.findById()` 반복(최대 페이지당 50 point query). 바로 옆
  `AuditChainMonitorController.buildLookups`는 `findAllById` 배치 사용 — 비대칭.
- **수정**: distinct tenantId Set → `tenants.findAllById(ids)` 단일 IN 쿼리 후 Map 구성.
  반환 데이터 동일 → 동작 불변.

#### F22 [performance/low] credential rename()의 불필요한 PESSIMISTIC_WRITE 락
- **위치**: `passkey-app/.../fido2/credential/CredentialSelfService.java:33`
- **현상**: 라벨만 바꾸는 rename()이 delete()와 동일한 `findOwnedForUpdate`(행 락)를 취득. 라벨
  쓰기는 read-check-update 불변식이 없어 락 불필요(delete는 정당).
- **수정**: rename용 비잠금 `findOwned(credentialId, userHandle)` 추가. `@Transactional`
  dirty-checking이 라벨 저장 유지. delete는 락 그대로.

#### F19 [security/low] challenge issuedAt dead field
- **위치**: `passkey-app/.../fido2/challenge/AuthenticationChallenge.java:9`,
  `RegistrationChallenge` 동일
- **현상**: issuedAt이 /start에 기록되지만 /finish에서 읽히지 않음(grep read-site 0). challenge
  freshness는 Redis TTL(5분, GETDEL one-shot) 단독.
- **수정(택1, 둘 다 동작 불변)**: (a) dead field 제거, 또는 (b) finish에서
  `clock.instant().minus(TTL).isAfter(ch.issuedAt())`면 거부하는 앱-레벨 방어심화 추가.
  권장=(b) 방어심화(정상 흐름은 TTL 내라 불변).

#### F29 [code-quality/low] amr unchecked 캐스트 원소 미검사
- **위치**: `sdk-java/.../idtoken/IdTokenVerifier.java:68`
- **현상**: `(List<String>)` 캐스트가 `instanceof List<?>`로만 가드. 비-String 원소(숫자/객체)가
  amr()로 흘러 소비처에서 지연 ClassCastException(heap pollution).
- **수정**: 원소를 `instanceof String` 필터 또는 `String.valueOf`로 정규화. 주석(63-65)의 lenient
  의도 보존, well-formed 토큰은 불변.

#### F36 [security/low] fido-u2f ES256 명시 확인 부재
- **위치**: `webauthn/.../attestation/FidoU2fAttestationVerifier.java:40`
- **현상**: `credKey.publicKey() instanceof ECPublicKey`만 확인하고 ES256/P-256은 명시 검증 안 함.
  U2F는 스펙상 P-256 전용. 현재 P-256 강제가 CoseKeyParser 부수효과(EC2→ES256만)에만 의존.
- **수정**: `if (credKey.algorithm() != CoseAlgorithm.ES256) throw` 추가. valid U2F 불변,
  파서 정책 드리프트 회귀 갭 차단.

### A-2. Moderate (7건)

#### F21 [performance/medium] 요청당 scope 재조회(캐시 부재)
- **위치**: `passkey-app/.../security/ApiKeyAuthFilter.java:194`
- **현상**: 인증된 모든 RP 요청이 `findScopeValuesByApiKeyId`를 매번 실행(uncached). known
  BCrypt/PL-SQL 비용과 별개의 핫경로 DB 왕복.
- **수정**: apiKeyId 키로 짧은 TTL(예 60s) Caffeine 캐시. **scope 변경 시 evict 필수**(fail-closed
  유지). 캐시 미스는 기존 경로라 결과 불변.

#### F16 [code-quality/low] LicenseBootstrap @Bean ThreadLocal 누수
- **위치**: `core/.../license/LicenseBootstrap.java:65`
- **현상**: @Bean 팩토리에서 `TenantContextHolder.set()` 후 clear 없음 → 부트 스레드 ThreadLocal
  잔존. per-request는 OnpremTenantPinFilter가 커버하나 부트스레드 풀 재사용 시 누수.
- **수정**: 부트 set을 try/finally로 감싸 작업 후 clear, 또는 set 자체 제거. onprem 한정·방어적.

#### F27 [performance/low] JWKS 갱신 single-flight·negative-cache 부재
- **위치**: `sdk-java/.../idtoken/JwksCache.java:37`
- **현상**: TTL 만료 시 동시 verify가 각자 `JWKSet.load`(동기 블로킹)를 N회 실행(thundering herd).
  fetch IOException 시 snapshot 미갱신 → upstream 장애 시 매 요청 재시도(백오프 없음).
- **수정**: 갱신을 single-flight(한 스레드만 fetch, 나머지 직전 스냅샷)로, fetch 실패 시 짧은
  negative-cache/백오프. happy path 불변. (※ F26 kid-miss는 별개 — B그룹.)

#### F32 [security/low] SafetyNet alg↔leaf 키타입 명시 바인딩 부재
- **위치**: `webauthn/.../attestation/AndroidSafetyNetAttestationVerifier.java:99`
- **현상**: JWS header.alg(공격자 제어)를 leaf 키 타입에 고정하지 않고 verifyJws 예외를 일괄 false
  흡수. 현재 JCA가 키/alg 불일치를 거부해 익스플로잇 불가지만 alg-confusion 회귀 표면.
- **수정**: verifyJws 진입 시 leaf 키 타입↔alg 명시 매칭(ES256↔EC, RS256↔RSA), 불일치는 명시 거부.
  방어심화, 정상 경로 불변.

#### F33 [security/low] TPM attStmt.alg↔AIK 키타입 명시검증 부재
- **위치**: `webauthn/.../attestation/TpmAttestationVerifier.java:109`
- **현상**: F32와 동형. attStmt.alg(공격자 제어)로 JCA Signature 선택, AIK 인증서 키타입 일치
  명시검증 없음. ES256/RS256만 지원, JCA가 막음.
- **수정**: AIK 공개키 타입에서 기대 alg 도출 또는 verifyAikRequirements에서 호환성 명시 확인.

#### F34 [security/info] CertPathVerifier 검증 시각 미주입
- **위치**: `webauthn/.../trust/CertPathVerifier.java:27`
- **현상**: `PKIXParameters`에 `setDate` 미설정 → 항상 시스템 현재 시각 기준. Clock 주입 규약 없어
  결정적 검증 불가. (기본 EmptyTrustAnchorProvider로 TRUST_CHAIN_REQUIRED 경로 도달 드묾.)
- **수정**: verify에 검증 기준 Instant 파라미터 추가, `params.setDate(...)`, 호출부가 주입 Clock 공급.
  테스트 가능성·결정성 개선, 기본 동작 불변.

#### F37 [code-quality/info] ObjectMapper 정책 드리프트
- **위치**: `webauthn/.../attestation/AndroidSafetyNetAttestationVerifier.java:55`, MDS/MdsBlobParser 동일
- **현상**: 각자 `new ObjectMapper()` 생성 → NativeWebAuthnVerifier 주입 mapper와 분리. 주입
  mapper에 보안 설정(max string length, FAIL_ON_*) 적용해도 SafetyNet/MDS JWS 파싱 경로엔 미반영.
- **수정**: 주입 mapper 공유 또는 공통 보안 설정 단일 팩토리 사용.

---

## B그룹 — 동작 변경 (백로그, 16건 — 별도 승인 후 구현)

각 항목은 관찰 가능한 동작(응답/순서/거부 여부/타이밍)을 바꾸므로 본 라운드 제외. ⚠ = 실제 버그.

### 고가치 (우선 검토 권장)

- **F01 [security/high] ⚠ `mfaRequired` 정책 완전 no-op** —
  `admin-app/.../config/AdminSecurityConfig.java:248`. SecurityPolicy.mfaRequired가 저장·감사
  (SECURITY_POLICY_UPDATED)·UI 표시되지만 어떤 로그인/MfaPendingFilter 게이트도 `isMfaRequired()`를
  읽지 않음(유일 소비처는 view 직렬화). MFA 미등록 운영자가 정책 ON에서도 비밀번호만으로 로그인.
  → 거짓 안전감. **수정**: 로그인 핸들러에서 `policy.isMfaRequired() && !u.isMfaEnabled()`면 강제
  enroll-pending. **동작 변경**: 미등록 운영자 로그인 흐름이 바뀜.

- **F06 [code-quality/high] ⚠ Funnel 차트 KST/UTC off-by-one** —
  `admin-app/.../funnel/FunnelService.java:93,104`. created_at은 KST 저장, Oracle TRUNC도 KST
  자정인데 `atZone(ZoneOffset.UTC).toLocalDate()` + `LocalDate.now(UTC)`로 재해석 → 일별 라벨/막대
  하루 밀림(00:00~09:00 KST 조회 시). **수정**: 두 줄을 `KstTime.ZONE`으로 교체(trivial).
  **동작 변경**: 차트 수치가 (올바르게) 바뀜. MEMORY KST 마이그레이션 "육안검증 필요" 항목 확정.

- **F25 [code-quality/medium] ⚠ SDK Retry-After 파싱 → 429가 500으로** —
  `sdk-java/.../internal/PasskeyResponseErrorHandler.java:55`. `Long.parseLong(retryAfter)`가
  try/catch 밖. RFC 7231이 허용하는 HTTP-date 형식이면 NumberFormatException이 handleError를
  탈출 → 고객사 RP에서 PasskeyRateLimitException 대신 500. **수정**: try/catch로 retry=0 폴백(trivial).
  **동작 변경**: 비숫자 Retry-After 케이스 동작이 (올바르게) 바뀜.

- **F26 [security/medium] ⚠ JWKS kid-miss 시 강제 갱신 없음** —
  `sdk-java/.../idtoken/JwksCache.java:31`. 키 회전 직후 ~5분(TTL) 정상 토큰이 'unknown kid'로
  거부(가용성), compromise 회전 시 폐기키가 TTL만큼 계속 신뢰(보안 지연). **수정**: kid-miss 시 1회
  강제 refetch(짧은 쿨다운). **동작 변경**: 회전 직후 검증 결과가 바뀜. (F27 single-flight와 별개.)

- **F07 [code-quality/medium] ⚠ Activity 'before' 페이지네이션 행 드롭** —
  `admin-app/.../activity/ActivityService.java:155`. before 경로가 `createdAt < :before` 단독
  비교 → 동일 microsecond 행 그룹이 페이지 경계 걸치면 미표시 행 영구 드롭(정방향 sinceId는
  (createdAt,id) 튜플 커서라 안전 — 비대칭). **수정**: before도 (createdAt,id) 튜플 커서. 24h
  대시보드라 영향 제한적이나 실재 버그.

- **F02 [security/medium] TOTP 코드 replay** —
  `admin-app/.../auth/TotpService.java:53`. last-used step 미기록 → 동일 6자리 코드 ~90초 윈도우 내
  재사용(verify/confirm/disable 공유). **수정**: AdminUser에 last_verified_totp_step 컬럼+마이그레이션
  (RFC6238 §5.2). **동작 변경**: 코드 재사용 거부.

### 기타 동작 변경 (10건)

- **F04 [low] KeyRotationService 리스 미해제** — `keymgmt/KeyRotationService.java:84`. tryAcquire 후
  release 없음(RetentionPurgeJob만 finally-release) → 연속 회전이 TTL 30s간 KEY_ROTATION_CONFLICT.
  트랜잭션 PESSIMISTIC로 동시성 이미 방어돼 조기 release 안전. KeyExpirationJob도 동일. **동작 변경**:
  연속 회전 차단 해제.
- **F08 [low] ApiKey 목록 정렬 불안정** — `apikey/ApiKeyAdminService.java:73`. ORDER BY 없는
  findAll() → 새로고침마다 순서 변동 가능. **수정**: 정렬 쿼리/`.sorted()`. **동작 변경**: 순서 결정적화.
- **F11 [low] ⚠ MdsHistory TZ 왕복 비대칭** — `mds/MdsHistoryService.java:41`. WITH TIME ZONE 컬럼을
  `getTimestamp().toInstant()`로 읽어 JVM TZ 의존(append는 `Timestamp.from(Instant)`). JVM TZ=UTC면
  9h 어긋남. **수정**: `getObject(OffsetDateTime.class)`. 운영 JVM TZ=Asia/Seoul이면 우연히 맞음.
- **F14 [low] ⚠ 라이선스 grace 정수절단** — `license/LicenseStateMachine.java:114`.
  `toHours() > grace`가 정수절단 → 유효 grace 최대 ~1h 연장. **수정**:
  `compareTo(Duration.ofHours(grace)) >= 0`. onprem 한정.
- **F15 [low] 라이선스 cache-vs-disk tenantId 미검증** — `license/LicenseBootstrap.java:51`. 더 늦은
  exp 토큰을 tenantId 일치 확인 없이 채택 → 변조 캐시가 배포를 다른 tenant로 전환. **수정**: tenantId
  일치 가드. 정상시 동일 → 사실상 불변, 변조 엣지만 차단. onprem 한정.
- **F20 [low] registration 입력 크기 무제한** — `api/v1/rp/dto/RegistrationStartRequest.java:7`.
  username/displayName(+userHandle)에 @Size 없음 → multi-MB 문자열이 Redis challenge에 적재+응답
  echo. **수정**: @Size(max). **동작 변경**: oversized 입력 400 거부. (known finish-body-cap과 별개.)
- **F30 [low] BE/BS 일관성 검사 인증 경로 누락** — `verifier/NativeWebAuthnVerifier.java:~190`.
  verifyRegistration엔 BE/BS 검사 있으나 verifyAuthentication에 없음 → BS=1·BE=0 불가능 조합 수락.
  **수정**: 인증 경로에 동일 검사. WebAuthn 강제 사항은 아님(하드닝). **동작 변경**: malformed assertion 거부.
- **F31 [low] rawId↔credentialId 등록 경로 미대조** — `verifier/NativeWebAuthnVerifier.java:136`.
  인증 경로(170)는 대조하나 등록 경로는 안 함 → 클라이언트 응답 무결성 바인딩 한 겹 누락. **동작 변경**:
  malformed 등록 거부.
- **F10 [low] Funnel rolling-window vs day-bucket 불일치** — `funnel/FunnelService.java:54`.
  since=now-Ndays(N×24h)인데 버킷은 day-granular → 가장 오래된 버킷이 time-of-day에 따라 partial. F06과
  연관·하위. **동작 변경**: 첫 버킷 수치 변동.
- **F35 [low] CBOR non-canonical 인코딩 미거부** — `cbor/CborDecoder.java:112`. CTAP2 canonical
  요구하나 non-minimal int 인코딩 수락 → COSE 키 복수 바이트 표현(coseKeyBytes 지문 약화). **수정**:
  minimal-encoding 강제. **동작 변경**: 현재 수락 입력 일부 거부.

---

## 검증·구현 게이트

- A그룹은 동작 불변이므로 각 모듈 기존 테스트가 그대로 통과해야 한다(회귀=즉시 중단).
- 회귀 판정은 메모리 교훈에 따라 **base worktree 대조**로 확정(전체 `./gradlew build`는 SliceConfig
  충돌·Oracle 컨테이너 경합으로 항상 빨감 — 머지 게이트로 쓰지 않음).
- F21(scope 캐시)은 캐시 도입이라 evict 정확성 단위 테스트 필수.
- F19(b 방어심화 선택 시), F32/F33(거부 경로 추가)은 negative test로 신규 거부 경로 검증.
