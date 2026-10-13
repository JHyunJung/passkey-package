# Passkey2 보안·성능·코드 품질 점검 보고서

검증된 감사 결과를 정리했습니다. 근본 원인이 같은 항목은 모듈과 관점이 달라도 하나로 묶었습니다. 이 점검은 읽기만 했고 파일은 하나도 바꾸지 않았습니다.

## 요약

| 관점 | 항목 수 | 높음 | 중간 | 낮음 |
|---|---|---|---|---|
| 보안 | 47 | 3 | 8 | 36 |
| 성능 | 43 | 2 | 6 | 35 |
| 코드 품질 | 56 | 1 | 2 | 53 |

**가장 위험한 항목**
1. **[S1] passkey-app의 서명키 캐시가 갱신되지 않음.** admin-app에서 키를 회전하면 약 30분 뒤 새로 발급되는 ID 토큰이 전부 RP 검증에 실패합니다.
2. **[S2] 정지·매핑 해제·비밀번호 재설정을 해도 기존 세션이 살아 있음.** 권한을 회수해도 세션이 그대로 유효합니다.
3. **[S3] 미매핑 `/api/v1/rp/**` 경로.** 인증 없이 BCrypt-12를 무제한으로 돌리게 할 수 있어 CPU DoS가 됩니다.
4. **[P1] API 키 BCrypt-12를 매 요청마다 수행.** 주석이 말하는 `ApiKeyCache`는 코드에 없습니다.
5. **[Q1] Credentials 키워드 검색이 고장 났을 가능성이 높음.** native SQL에 스키마 접두사가 없어 운영 계정에서 ORA-00942 → 500이 날 것으로 보입니다.

---

## 보안

### 높음

| # | 제목 | 위치 | 근거 | 영향 | 개선안 | 기능 영향 |
|---|---|---|---|---|---|---|
| S1 | passkey-app이 회전된 서명키를 다시 읽지 않음 (가용성) | core/.../jwt/SigningKeyProvider.java:61,103 | `cachedActive`는 @PostConstruct에서만 채워짐. `reload()`는 admin-app KeyRotationService:127,133(다른 JVM)에서만 호출. JWKS는 매번 DB에서 조회 | 회전 후 grace(PT30M)가 지나면 서명에 쓰는 kid가 JWKS에서 빠져 모든 로그인 ID 토큰이 검증 실패. 재시작해야 복구 | passkey-app에서 ACTIVE kid를 주기적으로 비교(30~60초)하거나 pub/sub을 받아 `reload()` 호출. 실패해도 기존 캐시는 유지 | 동작 변화 있음: 회전 후 새 kid로 서명(의도된 계약을 따르게 됨) |
| S2 | 운영자 정지·테넌트 해제·비밀번호 재설정 시 세션 무효화 없음 | admin-app/.../operator/AdminUserService.java:103,85; PasswordResetService.java:105; TenantBoundary.java:48 | 바뀌는 것은 enabled 플래그와 매핑 행뿐. 권한·테넌트는 Redis 세션에 넣어 둔 AdminUserDetails에서 읽음. SessionRegistry 없음(backlog G03) | 정지된 사용자나 범위가 회수된 RP_ADMIN도 슬라이딩 idle timeout 동안 계속 접근 가능. 탈취 후 재설정해도 공격자 세션이 남음 | `FindByIndexNameSessionRepository.findByPrincipalName`으로 해당 세션 삭제, 또는 요청마다 version stamp 비교 | 동작 변화 있음: 해당 사용자 세션이 즉시 종료 |
| S3 | 미매핑 `/api/v1/rp/**`에서 인증 없는 BCrypt가 rate limit 없이 실행 | passkey-app/.../security/RateLimitFilter.java:108; ApiKeyAuthFilter.java:124,153 | `resolve()`가 null을 주면 필터를 건너뜀. 그런데 인증 필터는 모든 `/api/v1/rp/`에서 `matches(secret, DUMMY_HASH)`(cost 12) 실행. 끝에 `/`가 붙은 경로도 해당 | IP 하나로 요청당 약 200ms CPU를 무제한 소모시킬 수 있음 | resolve의 마지막 분기에 고정 scope(`/api/v1/rp:*`) catch-all tier 추가 | 동작 변화 있음: 가짜 경로가 임계 초과 시 401 대신 429 |

### 중간

| # | 제목 | 위치 | 근거 | 영향 | 개선안 | 기능 영향 |
|---|---|---|---|---|---|---|
| S4 | Attestation 신뢰 체인을 강제하지 않아 AAGUID 허용목록·MDS 정책 우회 가능 | webauthn/.../NativeWebAuthnVerifier.java:53,250; passkey-app RegistrationFinishService.java:108,126-132; core DefaultAaguidPolicyChecker.java:43 | `EmptyTrustAnchorProvider`와 `SELF_ALLOWED` 고정. none/self/자기서명 x5c 모두 통과. RegistrationResult에 trust type 정보 없음 | 소프트웨어 인증기가 허용목록의 AAGUID를 주장하면 정책 통과. 장치 모델 제한이 사실상 효력 없음 | 우선 RegistrationResult에 type/chainVerified 추가(기능 영향 없음). 이후 mdsRequired나 AAGUID 정책이 있는 테넌트에 TRUST_CHAIN_REQUIRED와 MDS 루트 적용 | 필드 추가만: 없음. 강제 시: none/self 등록 거부 |
| S5 | `/mfa/enroll`이 TOTP 확인 없이 MFA를 끔 | admin-app/.../auth/MfaController.java:150 (disable:195는 코드 확인함) | enroll이 `setMfaEnabled(false)`로 바꾸고 secret을 덮어씀 | 세션을 탈취한 사람이 MFA를 끄거나 자기 인증기로 바꿔 소유자를 잠글 수 있음 | `isMfaEnabled()`이면 `validCode` 요구 | 동작 변화 있음: 재등록 API에 코드 필요(UI 영향은 없음) |
| S6 | 'MFA 필수' 정책이 저장만 되고 강제되지 않음 | admin-app/.../config/AdminSecurityConfig.java:274; SecurityPolicyTab.tsx:158 | 로그인 처리가 `u.isMfaEnabled()`만 봄. backlog F01 | 관리자는 강제된다고 믿지만 실제로는 비밀번호만으로 접근됨 | 등록 필수 세션 상태 도입. 미루려면 UI 문구를 '미적용'으로 수정 | 강제 시 변화 있음. 문구 수정은 없음 |
| S7 | `X-Forwarded-For`를 아무 클라이언트에게서나 신뢰 → rate limit 우회, 감사 IP 위조 | passkey-app application.yml:19; admin-app application.yml:50; RateLimitFilter.java:140,160; AdminSecurityConfig.java:246,320 | `forward-headers-strategy: framework`가 신뢰 프록시 목록 없이 가장 왼쪽 값을 사용. nginx는 `$proxy_add_x_forwarded_for`로 값을 덧붙임 | 무작위 XFF와 pk_ 접두사 조합으로 버킷을 매번 새로 받아 BCrypt DoS. 감사 체인 IP 위조 | `native`와 명시적 `internal-proxies` 사용, 또는 엣지에서 `X-Forwarded-For $remote_addr`로 덮어쓰기 | native 전환은 테스트와 Secure 판정에 영향. nginx 덮어쓰기는 변화 없음 |
| S8 | LicenseGuardFilter가 원본 `getRequestURI()`로 예외 경로를 판정 → 경로 순회로 DEAD 라이선스 우회 | core/.../license/LicenseGuardFilter.java:95 | `/actuator/health/../../api/v1/rp/...`가 예외로 통과. passkey-app에는 StrictHttpFirewall 없음. nginx `$proxy_add` 경로로도 도달 | 만료된 라이선스로 API 계속 사용 가능 | `getServletPath()+getPathInfo()`로 판정하고, 원본 URI에 `..`나 `;`가 있으면 예외에서 제외 | 정상 경로는 변화 없음 |
| S9 | 비밀번호 재설정 요청 응답 시간으로 계정 존재 여부가 드러남 | admin-app/.../operator/PasswordResetService.java:70 | 존재하는 계정만 INSERT와 동기 SMTP 전송 수행 | 인증 없이 관리자 이메일 목록을 확보할 수 있음 | AFTER_COMMIT 이벤트나 비동기 MailSender 래퍼로 발송 | 응답·발송 결과 변화 없음. 메일이 조금 늦게 감 |
| S10 | 정책 변경 감사 로그가 트랜잭션 밖에서 쓰이고 actorId가 항상 null | admin-app/.../policy/AaguidPolicyController.java:37; SecurityPolicyController.java:34-46 | `service.update`가 커밋된 뒤에 `append` 호출. `AdminUserDetails.getId()`가 있는데도 null 전달. `@Valid` 없음 | append 실패 시 감사 없이 정책만 변경됨. 해시체인에 운영자 식별자가 없음 | append를 서비스 트랜잭션 안으로 이동. actorId 전달 | 이동만 하면 성공 경로는 동일하나 실패 시 롤백됨. actorId·@Valid는 저장 데이터와 응답이 바뀜 |
| S11 | SDK의 1-인자 `verifyIdToken`이 iss/aud를 검사하지 않음 | sdk-java/.../PasskeyClient.java:94; IdTokenVerifier.java:40 | 모든 테넌트가 같은 키로 서명. README §6 첫 예시가 1-인자 | 다른 테넌트의 토큰이 통과할 수 있음(외부 RP가 오용하는 경우) | @Deprecated와 Javadoc 경고, README에서 3-인자를 우선 예시로 | 변화 없음 |

### 낮음

| # | 제목 | 위치 | 근거 / 영향 | 개선안 | 기능 영향 |
|---|---|---|---|---|---|
| S12 | 관리자 로그인과 공개 엔드포인트에 rate limit 없음. 계정 잠금으로 DoS 가능. 주석이 사실과 다름 | AdminSecurityConfig.java:70,310 | "upstream에서 제한한다"는 주석과 달리 제한기 없음. 실패 5회로 누구나 운영자를 잠글 수 있음. reset/signup 남용 가능 | 잘못된 주석 수정, IP·이메일 기준 제한기 추가 | 주석은 없음, 제한기는 429 추가 |
| S13 | 비밀번호 재설정 confirm에 서버측 정책 없음 | PasswordResetController.java:37,43 | `@Valid`나 `@Size` 없음. API로 직접 호출하면 1자 비밀번호 가능. token이 null이면 500 | `@NotBlank @Size(12,128)`과 `@Valid` | 400 응답 추가(UI 흐름은 동일) |
| S14 | `/actuator/health` 상세 정보를 인증 없이 노출 | core application-common.yml:46; AdminSecurityConfig.java:105 | show-details: always로 DB·Redis·disk·license jti 노출(nginx가 일부 차단, backlog에 있음) | show-components: always와 show-details: when-authorized 조합 | 본문 details 축소. license 모니터링 문서와 충돌 확인 필요 |
| S15 | `/admin/api/license`가 비인증으로 sub/jti/features 노출 | LicenseController.java:66 | onprem에서 고객 식별자 노출 | 인증된 경우에만 상세 정보 반환 | 비인증 JSON만 달라짐(UI 영향 없음) |
| S16 | bcrypt 해시가 Redis 세션에 직렬화됨 | AdminUserDetails.java:23 | CredentialsContainer 미구현이라 erase되지 않음 | eraseCredentials 구현, serialVersionUID 고정 | 없음(배포 때 1회 재로그인 가능) |
| S17 | `/mfa/disable`과 `/mfa/confirm`에 실패 카운트 없음 | MfaController.java:195 | 무제한 추측 가능(실효는 S5가 더 큼) | `recordFailedMfa` 적용 | 잠금 동작 추가 |
| S18 | 백그라운드 폴링이 세션을 계속 연장해 idle timeout이 클라이언트에만 의존 | admin-ui Sidebar.tsx:59; LicenseBanner.tsx:29 | 서버가 세션을 유휴 상태로 보지 못함 | 서버측 마지막 사용자 활동 추적 | 동작 변화 있음 |
| S19 | 로그아웃 POST 실패를 무시 | admin-ui App.tsx:198 | 서버 세션이 남아 새로고침하면 콘솔이 다시 열림 | authEpoch 가드를 둔 재시도 | 동작 변화 있음 |
| S20 | 테넌트 검색 캐시가 로그아웃 후에도 남음 | admin-ui lib/search.ts:11 | `invalidateTenantCache` 호출처 0곳. 30초 안에 RP_ADMIN에게 테넌트 목록 노출 | handleLogout에서 무효화 | 없음 |
| S21 | 데모 계정 비밀번호가 prod 번들에 포함 | LoginPage.tsx:60; R__seed_operators.sql:19 | `alice-temp-pw`. QA에도 시드되고 MFA는 꺼져 있음 | dev 백엔드가 prefill 값을 제공. QA 시드 제거 | prefill 방식은 없음. QA 시드 제거는 변화 있음 |
| S22 | CSP와 Referrer-Policy 없음. reset token이 URL에 남음 | admin-ui index.html:4 | XSS 2차 방어 없음 | admin-app에서 헤더 송출 | 헤더만은 없음(replaceState는 변화 있음) |
| S23 | 경로 세그먼트 인코딩 불일치(클라이언트측 경로 순회) | admin-ui api/tenants.ts:30 외; TenantDetailPage.tsx:247 | `..%2F`로 다른 GET 경로를 호출하게 할 수 있음. 영향은 GET에 한정 | `seg()` 헬퍼로 encodeURIComponent 적용 | 없음 |
| S24 | findByCredentialId에 tenant 조건 없음 → 중복 credentialId면 500 | core CredentialRepository.java:48,57; CredentialAdminService.java:104,132 | PLATFORM_OPERATOR 경로에서 IncorrectResultSize 발생 | tenant 범위 쿼리 + 기존 403 fallback | 중복 케이스만 500에서 정상으로 |
| S25 | 인증 경로의 tenant 격리를 Hibernate filter에만 의존 | CredentialRepository.java:48,99,103; AuthenticationFinishService.java:136 | 리팩터링으로 필터가 꺼지면 테넌트 간 credential이 섞일 위험 | RP 경로에 `cred.getTenantId()` 단언 추가 | 없음 |
| S26 | 예외 메시지 원문을 클라이언트에 반환 | core GlobalExceptionHandler.java:66,109,118; rp-app WebAuthnController.java:105,175 | JWKS URL, kid, 서버 시각, UUID 파싱 문자열 노출 | 고정 메시지 반환, 상세 내용은 로그로 | 메시지 문자열 변경(ResetPasswordPage가 의존) |
| S27 | X-Trace-Id를 검증 없이 수용하고 전파 | core TraceIdFilter.java:27; rp-app TraceIdFilter.java:30; sdk TraceIdPropagationInterceptor.java:27 | JSON 로그 오염, 상관관계 ID 위조 | 길이 상한과 제어문자 거부(관대한 패턴) | 규칙에 안 맞는 ID는 교체됨 |
| S28 | 사용자 입력값으로 로그 위조 가능 | webauthn ClientDataValidator.java:62,79; RegistrationFinishService.java:114; sdk IdTokenVerifier.java:47,62,66 | type/origin/fmt/kid의 CR/LF가 text 로그에 그대로 기록 | text appender 쪽에서 CR/LF 치환 | 로그만 바뀜 |
| S29 | 메일 발송 로그에 수신자 원문, DEBUG에서 reset 링크 노출 | core LogMailSender.java:16,18; SmtpMailSender.java:36,40 | LogRedact 정책 위반 | `maskEmail(to)` 적용. 선택된 발송기를 WARN으로 기록 | 로그만 바뀜(LogMailSender 마스킹은 수동 전달 운영에 영향) |
| S30 | DeploymentModeGuard가 trim을 허용해 `@ConditionalOnProperty`와 불일치 | core DeploymentModeGuard.java:40 | `" onprem"`이 가드를 통과하지만 라이선스 컴포넌트는 등록되지 않음 | 앞뒤 공백 거부 | 공백 값이면 기동 실패 |
| S31 | 라이선스 시계 롤백 보호를 우회할 수 있음 | core LicenseBootstrap.java:46 | lastVerifiedAt가 MAC 없이 디스크에 저장됨 | MAC 처리된 high-water mark 저장 | 변화 있음 |
| S32 | MDS BLOB 체인을 폐기 확인 없이, 실제 현재 시각으로 검증 | webauthn CertPathVerifier.java:41; MdsJwsVerifier.java:34 | MDS3 §3.2 CRL 요건 미충족 | 폐기 확인 활성화, asOf 전달 | 변화 있음 |
| S33 | WebAuthn 사양 미준수: credentialId 1023 제한, BE/BS(인증), crossOrigin/topOrigin, userHandle 미검사 | webauthn AuthenticatorDataParser.java:43; NativeWebAuthnVerifier.java:193,218; ClientDataValidator.java:57 | 각각 파싱만 하거나 무시함(실질 위험은 낮음) | 길이 검사만 추가(현재 DB 제약으로 이미 실패하는 입력). 나머지는 결과 필드로 노출 | 길이: 오류 코드만 바뀜. 나머지를 강제하면 변화 있음 |
| S34 | signCount를 검사하는 곳이 앱 한 곳뿐인데 주석은 반대로 설명 | StoredCredential.java:4; AuthenticationFinishService.java:184 | 이 블록을 지우면 복제 탐지가 사라짐 | 주석 수정, 단위 테스트 고정 | 없음 |
| S35 | AttestationException이 아닌 예외가 500으로 빠져나감 | NativeWebAuthnVerifier.java:137; FidoU2fAttestationVerifier.java:39 | 지원하지 않는 attStmt.alg나 x5c 타입 오류가 500과 ERROR 스택으로 처리됨 | CoseException과 ClassCast를 좁혀서 매핑 | 500이 400으로 바뀜 |
| S36 | 테넌트 접두사만 알면 그 테넌트의 key 버킷을 소진시킬 수 있음 | RateLimitFilter.java:154 | 비밀값 검증 전에 버킷을 차감 | 인증 후 차감 | 401과 429 응답 차이 |
| S37 | dev 프로필에 커밋된 master key와 DB 비밀번호 fallback | passkey-app application-dev.yml:19,29 | env 누락 시 저장소의 키로 서명키를 래핑 | 기본값 제거 | dev를 env 없이 띄우면 기동 실패 |
| S38 | rp-app 사용자명 열거 | rp-app WebAuthnController.java:111,135 | finish 전에 W001 반환. begin 응답의 allowCredentials로 존재 여부 노출 | 검사를 finish 이후로 이동하거나 응답 통일 | 변화 있음 |
| S39 | 미확정(pending) 사용자도 로그인 성공 | WebAuthnController.java:183 | 동시 finish 경합 시 고아 계정 생성 | `credentialId != null` 요구 | 엣지 케이스만 P004로 |
| S40 | pending 정리 작업이 요청마다 전체 스캔과 정렬 | rp-app InMemoryUserStore.java:171 | cap 도달 후 비인증 begin마다 O(N log N) | FIFO 구조와 카운터 | 없음 |
| S41 | Relay 데모키 가드가 서버 기동 후에 실행되고 강도 검사도 없음 | RelayKeyGuard.java:25,31; sdk RegistrationRelayCodec.java:49 | 짧은 창 동안 데모키로 서명. 짧은 키도 허용 | @PostConstruct로 이동. 짧은 키는 WARN | 이동은 없음, 길이 거부는 변화 있음 |
| S42 | `.env`를 0644 권한으로 생성 | rp-app scripts/bootstrap-rp-app.sh:83 | API 키와 relay secret 평문 | `chmod 600` | 없음 |
| S43 | SDK가 http baseUrl을 경고 없이 허용 | sdk PasskeyClientConfig.java:30 | API 키 평문 전송, JWKS 위조 가능 | 비loopback http면 WARN | 없음 |
| S44 | SDK DEBUG 마스킹 누락, 레코드 toString 토큰 노출 | sdk RedactingRequestInterceptor.java:64; dto/AuthenticationFinishResponse.java | registrationToken, PII, idToken 노출 | 마스킹 대상 확장, toString 재정의 | 로그만 바뀜 |
| S45 | SDK 응답 본문 크기 상한 없음 | sdk PasskeyResponseErrorHandler.java:35; PasskeyClient.java:115 | 비정상 upstream이면 OOM 가능 | 약 1MB 상한 | 비정상 본문만 C999 |
| S46 | `/authenticate/begin` username에 크기 상한 없음 | rp-app WebAuthnController.java:133 | 가입 경로와 일관성 없음(실제 위험은 미미) | `@Size(256)`와 `@Valid` | 긴 입력이 400으로 |
| S47 | TenantContext 이외 rp-app 오류 응답에 SDK 내부 정보 노출 | GlobalExceptionHandler(rp-app):95,102 | S26과 같은 근본 원인(위 항목에 통합) | S26 참조 | S26 참조 |

---

## 성능

### 높음

| # | 제목 | 위치 | 근거 | 영향 | 개선안 | 기능 영향 |
|---|---|---|---|---|---|---|
| P1 | 모든 RP 요청에서 BCrypt-12를 실행하는데, 근거로 든 `ApiKeyCache`가 존재하지 않음 | passkey-app ApiKeyAuthFilter.java:171; core CoreSecurityConfig.java:13-19 | 저장소에서 grep하면 주석에만 있음 | 요청당 약 150~300ms CPU. 로그인 1회에 2번 발생. 처리량 상한을 이 비용이 결정 | apiKeyId 기준 캐시(keyHash와 SHA-256(secret) 바인딩, TTL 30~60초, 성공한 경우만 저장). DB 조회와 isActive는 매번 수행. 주석 수정 | 없음(수락·거부 결정 동일) |
| P2 | (S3과 같은 근본 원인) 미매핑 RP 경로의 BCrypt | RateLimitFilter.java:108 | S3 참조 | S3 참조 | S3 참조 | S3 참조 |

### 중간

| # | 제목 | 위치 | 근거 | 영향 | 개선안 | 기능 영향 |
|---|---|---|---|---|---|---|
| P3 | 감사 체인 overview가 audit_log 전체를 두 번 읽고, Sidebar가 이를 30초마다 호출(숨은 탭 포함) | admin-app AuditChainMonitorController.java:60,68; core AuditLogRepository.java:69; admin-ui Sidebar.tsx:59; AuditChainPage.tsx:149 | 검증할 때 한 번, 버킷 집계할 때 다시 한 번 전체 로드. audit_log는 purge되지 않음 | 운영자 탭마다 DB·힙·CPU 사용량이 테이블 크기에 비례해 증가 | 테넌트당 한 번만 로드해 재사용(break 이후 행도 계속 집계). `document.hidden`이면 폴링 중지. TRUNC 시간 집계는 버킷 경계가 달라져 결과가 바뀜 | 1회 로드와 hidden 중지: 없음. 공유 in-flight promise: 변화 있음 |
| P4 | 전역 체인 검증이 audit_log 전체를 List로 메모리에 올림 | admin-app AuditChainVerifier.java:55,102 | CLOB을 포함한 엔티티 전체 | 힙 사용량이 계속 늘어 OOM 위험 | 같은 ORDER BY로 keyset 페이징 또는 Stream(clear 호출 금지) | 없음 |
| P5 | 감사 append가 전역 락을 잡은 상태에서 인덱스 없는 정렬 실행 | core AuditLogRepository.java:31,57 | `(created_at, id)` 복합 인덱스 없음 | 감사 대상 작업마다 테이블 크기에 비례해 지연 | `CREATE INDEX audit_log_created_id_ix (created_at, id)`. EXPLAIN으로 확인 | 없음 |
| P6 | afterCommit/afterRollback 이벤트 기록이 두 번째 커넥션을 요청 → 풀 고갈 정지 | core CeremonyEventRecorder.java:61; CredentialAuthEventRecorder.java:61,85; application-common.yml:34 | 커넥션을 쥔 채 REQUIRES_NEW 실행. pool 10, Tomcat 스레드 200 | 동시 세리머니 10개 이상이면 30초 정지 후 이벤트 유실 | 풀 크기 확대 또는 Tomcat 스레드를 pool/2 이하로. 구조적 해결은 비동기 executor | 설정 변경: 없음. 비동기: 행 기록 시점이 늦어짐 |
| P7 | 라이선스 heartbeat RestClient에 타임아웃 없음 | core LicenseHeartbeatScheduler.java:50 | 서버가 응답하지 않으면 영구 블록. NETWORK_GRACE로 전이하지 않음. admin 스케줄러 스레드 1개 점유 | 철회를 감지하지 못하고 admin 작업이 직렬화됨 | JdkClientHttpRequestFactory에 connect·read 타임아웃(넉넉하게) | 없음 |
| P8 | JWKS를 처음 가져오기 전에는 negative cache가 없음 | sdk JwksCache.java:160 | 스냅샷이 없으면 backoff가 적용되지 않음 | JWKS 장애가 나면 cold start 시 k번째 호출자가 k×13초 대기 | 짧은 negative cache | 변화 있음 |

### 낮음

| # | 제목 | 위치 | 개선안 | 기능 영향 |
|---|---|---|---|---|
| P9 | JWKS 갱신 중 락 안에서 HTTP를 수행해 모든 검증이 대기 | sdk JwksCache.java:146 | stale-while-revalidate | 변화 있음 |
| P10 | ActivityPage가 1초마다 피드 전체를 재렌더 | admin-ui ActivityPage.tsx:312 | groupByDay의 today/yesterday 키를 미리 계산 | 없음(ticker 이동은 자정 라벨 처리 주의) |
| P11 | Activity가 5초마다 전체 + 집계 4개를 재조회하고 숨은 탭에서도 계속 | ActivityPage.tsx:394 | hidden 중지, sinceId 사용 | 변화 있음 |
| P12 | TenantOverview가 전역 피드를 받아 클라이언트에서 필터 | TenantOverview.tsx:205 | tenantId 파라미터 사용 | 변화 있음(표시 데이터) |
| P13 | 드롭다운용으로 전체 테넌트 집계(audit 집계 포함)를 조회 | ActivityPage.tsx:307; AdminUsersTab.tsx:86; search.ts:23 | summary 전용 엔드포인트 추가(경계·순서 동일하게) | 없음 |
| P14 | 팔레트 검색이 키 입력마다 테넌트 목록 요청 | search.ts:22 | in-flight promise 캐시 | 없음 |
| P15 | 같은 데이터 중복 조회: 테넌트(crumb와 page), apiKeys, aaguid | useCrumbTenant.ts:13; TenantDetailPage.tsx:177 | 부모가 받은 데이터를 자식에 전달하거나 최초 로드만 공유 | 공유 dedupe는 stale 위험이 있어 변화 있음 |
| P16 | 만료 임박 수를 세려고 모든 API 키를 내려받음 | TenantsListPage.tsx:82 | 서버에서 count 제공 | 없음 |
| P17 | qrcode 라이브러리를 메인 번들에 정적 포함 | QrCode.tsx:2 | dynamic import(cancelled 가드 포함) | 없음 |
| P18 | LicenseBanner가 SaaS에서도 폴링하고 조회가 중복 | LicenseBanner.tsx:29 | SaaS면 중지(세션 keep-alive 확인 후) | 변화 있음 |
| P19 | Backfill이 네이티브 UPDATE마다 auto-flush → O(M²) | admin-app AuditChainBackfillService.java:57 | read-only 힌트 또는 FlushMode COMMIT, 테넌트별 clear | 없음 |
| P20 | 감사 목록이 버려지는 COUNT를 매번 실행, catch-all 조건절 | AuditLogController.java:60; AuditLogRepository.java:108 | List 또는 Slice 변형, 동적 Specification(빈 문자열은 null 처리) | 없음 |
| P21 | MDS 동기화가 블로킹 KEYS와 엔트리별 SET 사용, 접두사 중복 | admin-app MdsSchedulerService.java:102,133; MdsAaguidCache.java:73 | SCAN과 파이프라인, 접두사 상수화 | 없음(테스트 mock 수정 필요) |
| P22 | API 키 목록이 findAll 후 메모리에서 필터 | admin-app ApiKeyAdminService.java:73 | findByTenantId(정규 UUID 문자열일 때만) | 없음 |
| P23 | 정적 자산에 no-store가 붙고 압축 없음 | admin-app AdminWebMvcConfig.java:17; rp-app WebSecurityConfig.java:26 | `/admin/assets/**`에 immutable 캐시, rp-app은 `cachecontrol.no-cache=true`, 압축 MIME 제한 | 없음 |
| P24 | Activity top5를 findById로 개별 조회, 피드가 CLOB 로드 | ActivityService.java:99,110 | findAllById 일괄 조회, 프로젝션(별도 메서드) | 없음 |
| P25 | Credential 정렬이나 credential_id 조회에 쓸 인덱스 없음 | core CredentialRepository.java:58,65 | credential_id 인덱스 추가. 정렬 인덱스는 NULLS LAST 때문에 효과가 불확실하니 EXPLAIN 확인 | 없음(쓰기 비용 증가) |
| P26 | LicenseStateMachine이 요청마다 synchronized 두 번 | core LicenseStateMachine.java:64 | 불변 스냅샷과 AtomicReference(입력값 단일 holder) | 단순 lock-free는 torn read 위험으로 변화 있음 |
| P27 | 콘솔 appender가 동기식 | core·rp-app logback-spring.xml:39 | AsyncAppender(discardingThreshold=0) | 없음 |
| P28 | 공개 JWKS 엔드포인트가 요청마다 DB 조회(private LOB 포함) | core JwksAssembler.java:28 | kid와 public_jwk만 조회하는 projection | 없음(TTL 캐시는 변화 있음) |
| P29 | 요청마다 last_used_at UPDATE, 인증 필터 왕복 3회 | passkey-app ApiKeyLookupService.java:88; ApiKeyAuthFilter.java:194 | scope를 한 번에 조회(tenant 조건 명시) | 조회 통합: 없음. touch 스로틀: 변화 있음 |
| P30 | finish 서비스의 tenant LAZY 컬렉션을 순차 조회, auth start가 BLOB 로드 | RegistrationFinishService.java:83; AuthenticationStartService.java:82 | EntityGraph 사용, `findCredentialIdsByUserHandle` 사용 | 없음 |
| P31 | rp-app이 확정할 때마다 JSON 전체 재기록 | InMemoryUserStore.java:115 | O(N) 비용임을 주석으로 명시 | 없음 |
| P32 | SecretRedactor가 로그 줄마다 정규식 7회 | rp-app SecretRedactor.java:46 | 리터럴 사전 필터(`pk_`와 `eyJ`는 대소문자 무시로 비교해야 함) | 대소문자 처리를 지키면 없음 |
| P33 | 디버그 페이로드 로깅이 본문 전체를 버퍼링 | rp-app RequestLoggingFilter.java:53 | request 캐시 상한 | 로그만 바뀜 |
| P34 | 사용하지 않는 spring-session-core 의존성 | rp-app build.gradle.kts:15 | 제거 후 테스트 | 없음 |
| P35 | MDS BLOB을 약 5번 복사하고 전체 트리를 생성 | webauthn MdsJws.java:102; MdsJwsVerifier.java:25 | 패키지 내부에서 clone 생략, x5c 1회 호출 | 없음 |
| P36 | COSE 키 재파싱: assertion마다, 등록 시 2회, AuthenticatorData 이중 decode | NativeWebAuthnVerifier.java:115,204; PackedAttestationVerifier.java:52; AuthenticatorDataParser.java:55 | 슬라이스를 한 번 복사해 재사용, 파싱한 키 전달 | 없음 |
| P37 | RSASSAVerifier와 Mac을 호출마다 생성 | sdk IdTokenVerifier.java:69; RegistrationRelayCodec.java:105 | (스냅샷, kid) 단위 캐시, ThreadLocal Mac(lazy 생성) | 없음 |
| P38 | 운영자 Sidebar 폴링이 부하를 배가 | Sidebar.tsx:59 | P3에 통합 | — |
| P39 | AuditChainPage와 Sidebar가 overview를 동시에 호출 | AuditChainPage.tsx:149 | P3에 통합 | 공유 promise는 변화 있음 |
| P40 | Activity 검색 캐시 per keystroke | — | P14에 통합 | — |
| P41~P43 | 위 항목에 병합된 중복 | — | — | — |

---

## 코드 품질

### 높음·중간

| # | 제목 | 심각도 | 위치 | 근거 | 영향 | 개선안 | 기능 영향 |
|---|---|---|---|---|---|---|---|
| Q1 | Credential 키워드 검색 native SQL에 스키마 접두사가 없고 ORDER BY도 없음 | 높음 | core CredentialRepository.java:75 | `FROM credential`. 다른 native 쿼리는 모두 `{h-schema}` 사용. 앱은 PSK_APP_ADMIN_USER로 접속하고 synonym 없음 | 검색어를 넣으면 500(ORA-00942)일 가능성이 높음. 페이징 순서 불안정 | `{h-schema}credential`과 ORDER BY 추가, Oracle IT 추가 | 동작 변화 있음: 고장 난 경로가 동작하게 됨 |
| Q2 | 감사 overview 로직이 컨트롤러에 있고 MonthlyReportService가 컨트롤러를 주입받음 | 중간 | AuditChainMonitorController.java:60; MonthlyReportService.java:28 | 계층이 역전됨. 버킷 계산 테스트 없음 | 재사용과 테스트가 어려움 | AuditChainOverviewService 추출(P3와 함께) | 없음 |
| Q3 | LicenseCache와 LicenseBootstrap 조정 로직 테스트 없음 | 중간 | core LicenseBootstrap.java:44 | 아무도 캐시 경로를 실행하지 않음 | 회귀가 생기면 잠금(DEAD) 또는 grace 연장 | 임시 디렉터리 기반 단위 테스트 | 없음 |

### 낮음

| # | 제목 | 위치 | 개선안 | 기능 영향 |
|---|---|---|---|---|
| Q4 | adminFetch가 본문이 빈 4xx/5xx를 성공으로 처리 | admin-ui api/adminUsers.ts:56 | `!res.ok` 검사를 앞으로 이동 | 빈 본문 오류만 예외로 바뀜 |
| Q5 | fetch/CSRF/401 전송 로직이 4벌, 공개 경로 목록이 3벌이고 이미 서로 다름 | client.ts:39; adminUsers.ts:10; mfa.ts:4; App.tsx:177 | 공통 `send()` 추출(각 변형의 특성은 유지) | 없음 |
| Q6 | 죽은 API 헬퍼와 타입, AdminUserView 사본이 서로 다름 | client.ts:167; types.ts:350 | 삭제, knip 도입 | 없음 |
| Q7 | chain/verify 클라이언트 2개, 도달 불가 분기, verifiedRows가 0으로 고정 | audit.ts:71; auditChain.ts:15 | 하나로 통합 | 없음 |
| Q8 | 실패 이벤트 판정 로직 중복 | ActivityPage.tsx:71; auditHelpers.ts:17 | 이름 기반 판정만 공용화 | 없음 |
| Q9 | noImplicitAny가 꺼져 있고 rules-of-hooks는 warn, lint가 빌드에 없음 | tsconfig.json:15; eslint.config.js:38 | 켜기(현재 위반 0건) | 없음 |
| Q10 | `tsc -b`가 vite.config.js를 생성하고 Vite가 이를 우선 로드 | tsconfig.node.json:3 | outDir 지정, 생성된 파일 삭제 | 없음 |
| Q11 | adminFetch, mfa, monthlyReport 전송 계층 테스트 없음 | adminUsers.ts:15 | fetch stub 테스트(현재 동작 고정) | 없음 |
| Q12 | 오래된 "클라이언트 타임아웃 없음" 주석 | AdminUsersTab.tsx:181 | 주석 수정 | 없음 |
| Q13 | 비즈니스 규칙 위반이 IllegalStateException → 500 | admin-app AdminUserService.java:83; AaguidPolicyService.java:47 | 409/404 매핑(spec §8 backlog) | 변화 있음 |
| Q14 | actorId 조회가 11곳 중복이고 방식이 세 가지 | TenantAdminController.java:51 외 | 공통 헬퍼(@WithMockUser 테스트 수정 필요) | 없음 |
| Q15 | 해시 재계산 코드 3벌, computeHash 시그니처가 불필요한 인자를 받음 | AuditChainVerifier.java:74; AuditChainBackfillService.java:71; AuditLogService.java:120 | 헬퍼로 추출(verifier만 JSON 검증 유지) | 없음 |
| Q16 | 예외를 로그 없이 삼킴 | PasswordResetService.java:88; MdsAdminController.java:56 | WARN 로그 추가 | 없음 |
| Q17 | 키 만료 처리에서 revoke와 감사가 서로 다른 트랜잭션, lease를 해제하지 않음 | KeyExpirationJob.java:66 | 키별 트랜잭션(별도 bean 또는 TransactionTemplate) | 실패 경로만 바뀜 |
| Q18 | 감사 action이 흩어진 문자열 리터럴, 분류 집합이 어긋남 | ActivityService.java:31 | 상수 클래스(값과 분류는 그대로) | 없음 |
| Q19 | 마스킹 헬퍼 중복·분기, 죽은 의존성, 틀린 javadoc | MfaController.java:262; AdminUserService.java:139; AaguidPolicyService.java:23,34; TenantAdminService.java:275 | CryptoUtils로 통일, 죽은 코드 제거 | 로그 문자열만 바뀜 |
| Q20 | snapshot 저장을 catch(Exception)로 넓게 감싸고 코드 중복 | TenantAdminService.java:157,199 | `saveSnapshot` 헬퍼 | 없음 |
| Q21 | SigningKeyProvider의 미사용 필드와 테스트 전용 생성자 | core SigningKeyProvider.java:56,88 | 제거(테스트 4곳 수정) | 없음 |
| Q22 | AuditLog가 리플렉션으로 시간 필드 주입 | core AuditLog.java:90 | BaseEntity에 protected 메서드 | 없음 |
| Q23 | LicenseVerifier의 unchecked 캐스트가 예외 계약을 벗어남 | core LicenseVerifier.java:86,110 | instanceof 검사(비-list features는 기존처럼 유지) | 잘못된 토큰의 예외 타입만 바뀜 |
| Q24 | 알림 drop 로그 라벨 오류 | core AlertConfig.java:24 | 라벨 분리 | 없음 |
| Q25 | 라이선스 필터와 health가 Instant.now() 사용 | core LicenseGuardFilter.java:81,112; LicenseHealthIndicator.java:26 | Clock 주입 | 없음 |
| Q26 | 이벤트 recorder와 UUID 변환 중복 | core CredentialAuthEventRecorder.java:33; IdTokenIssuer.java:91 | 공용 헬퍼 | 없음 |
| Q27 | expiresIn 900 하드코딩 | passkey-app AuthenticationFinishService.java:234 | TTL 프로퍼티 주입 | 없음 |
| Q28 | finish 서비스 핵심 분기 단위 테스트 없음 | AuthenticationFinishService.java:143,190 | Mockito 테스트 | 없음 |
| Q29 | 인증 start가 userHandle을 검증하지 않음 | AuthenticationStartService.java:75 | 등록 쪽 검증 재사용(null 허용) | 변화 있음 |
| Q30 | 만료 challenge가 C001과 F001 두 코드로 나뉨, IAE를 도메인 오류로 사용 | RegistrationFinishService.java:59 | BusinessException | 코드가 바뀜(테스트 영향) |
| Q31 | finish 공통 전처리 코드 중복 | RegistrationFinishService.java:62 | 헬퍼(origins 검사 위치는 유지) | 없음 |
| Q32 | b64url, idTail, tokenTail, COSE alg, 접두사 상수 중복 | CredentialSelfService.java:63; RegistrationStartService.java:93; RateLimitFilter.java:57; ApiKeyAuthFilter.java:59 | 공용 상수와 헬퍼(형식은 동일하게) | 없음 |
| Q33 | 예외 cause 누락 | AuthenticationFinishService.java:129 | cause 추가 | 없음 |
| Q34 | RegistrationChallenge의 미사용 PII 필드 | RegistrationChallenge.java:9 | 제거와 `@JsonIgnoreProperties` | 없음 |
| Q35 | 중복된 format 검사, 위치가 틀린 주석 | RegistrationFinishService.java:120 | 집합을 한 번만 계산 | 없음 |
| Q36 | X.509, DER, JWS, 서명·해시 헬퍼 중복. Apple 경계 검사가 Android와 다름 | webauthn AppleAttestationVerifier.java:97,119; AndroidSafetyNetAttestationVerifier.java:70; NativeWebAuthnVerifier.java:226 | 공용 Der, X5c, Crypto 유틸(메시지는 그대로) | 없음(Apple 경계를 바꾸면 변화) |
| Q37 | 실패 Reason 오분류 | NativeWebAuthnVerifier.java:116,138 | 세분화 | reason 값이 바뀜 |
| Q38 | JsonMappers.secure() 이름이 실제와 다름 | JsonMappers.java:22 | 이름 변경 | 없음 |
| Q39 | MetadataBlobVerifier 기본 메서드가 asOf를 무시(fail-open) | MetadataBlobVerifier.java:21 | 위임 방향 반전 | 없음 |
| Q40 | MdsBlobParser가 entries 누락을 빈 목록으로 처리 | MdsBlobParser.java:40 | 필수 필드로 | 변화 있음 |
| Q41 | issuerBase 미설정을 NPE로 표현 | rp-app WebAuthnController.java:166 | IllegalStateException(요청 시점 헬퍼) | 없음 |
| Q42 | try/catch 로그 후 재던짐 반복 | WebAuthnController.java:84 | `upstream()` 헬퍼(SDK 호출 4곳) | 없음 |
| Q43 | relay decode 실패와 unknown sub 테스트 없음 | WebAuthnControllerTest.java:200 | 테스트 추가(null sub는 현재 500) | 없음 |
| Q44 | 죽은 코드(PageResponse, ok()), 문서의 W003와 코드 불일치 | PageResponse.java:7; ErrorCode | 템플릿임을 명시, 문서와 코드 정합 | 없음 |
| Q45 | actorEmail MDC 경로를 채우는 곳이 없고 catch(Throwable) | RequestLoggingFilter.java:94 | catch 범위 축소, 주석 수정 | 없음 |
| Q46 | 사용자명 점유 규칙 중복, 고아 credential 로그 없음 | InMemoryUserStore.java:214 | predicate 추출, WARN 로그 | 없음 |
| Q47 | WellKnown packageName이 null이면 NPE | WellKnownController.java:45 | 시작 시 검증 | 변화 있음 |
| Q48 | JWKS URL이 baseUrl 경로를 무시 | sdk JwksCache.java:77 | 문서화와 고정 테스트 | 없음 |
| Q49 | 전송 예외가 Spring 타입으로 새고 로그 없음 | sdk PasskeyClient.java:116 | 로그 후 재던짐, README 보강 | 없음 |
| Q50 | 로깅 인터셉터가 인증 헤더도 주입하고 죽은 규칙 보유 | sdk RedactingRequestInterceptor.java:49 | 클래스 분리(순서와 logger 이름 유지) | 없음 |
| Q51 | cred_id 캐스트, 약 100줄짜리 verify(), iat 이중 읽기 | sdk IdTokenVerifier.java:40,129 | 헬퍼 추출, getStringClaim | 없음 |
| Q52 | Retry-After 계산이 실제 시계를 사용 | sdk PasskeyResponseErrorHandler.java:92 | Clock 주입 | 기본 설정은 동일 |
| Q53 | 200+success=false 응답일 때 code가 null | sdk PasskeyClient.java:130 | 계약 테스트 | 없음 |
| Q54 | 요청 직렬화에 설정한 ObjectMapper를 쓰지 않음 | sdk PasskeyClient.java:41 | Jackson converter만 교체 | 없음 |
| Q55 | Relay codec 생성 시 빈 키와 0 이하 TTL 허용 | RegistrationRelayCodec.java:49 | 문서화 | 검증을 넣으면 변화 |
| Q56 | 테넌트 snapshot catch와 기타 중복 | — | Q20에 통합 | — |

---

## 기능 변경 없이 바로 적용 가능한 개선 Top 10

1. **[P1] 검증된 secret 캐시.** apiKeyId와 keyHash, SHA-256(secret)에 묶은 TTL 캐시를 두고, DB 조회와 active 검사는 매번 수행합니다. CoreSecurityConfig 주석도 고칩니다.
2. **[P3] 감사 overview 1회 로드와 숨은 탭 폴링 중지.** break 이후 행도 계속 집계해야 합니다. TRUNC 시간 집계는 쓰지 않습니다.
3. **[P5] `audit_log (created_at, id)` 인덱스 추가.** 새 Flyway 마이그레이션으로 넣고 EXPLAIN으로 확인합니다.
4. **[P7] heartbeat RestClient에 connect·read 타임아웃 설정.**
5. **[P6] Hikari 풀 확대 또는 Tomcat 스레드 상한.** afterCommit 이중 커넥션으로 인한 정지를 막습니다.
6. **[S8] LicenseGuardFilter를 `servletPath + pathInfo` 기준으로 판정.** 원본 URI에 `..`나 `;`가 있으면 예외로 보지 않습니다.
7. **[S23] admin-ui 경로 세그먼트에 `encodeURIComponent` 일괄 적용.**
8. **[S20] 로그아웃 시 `invalidateTenantCache()` 호출.**
9. **[S16] AdminUserDetails에 CredentialsContainer 구현.** serialVersionUID를 고정합니다.
10. **[S9] 비밀번호 재설정 메일을 AFTER_COMMIT 이벤트나 비동기 래퍼로 발송.** 응답과 발송 결과는 그대로이고, 테스트의 verify만 조정하면 됩니다.

부수 후보(모두 기능 무변경): S29 메일 로그 마스킹, S28 text 로그 CR/LF 치환, P21 MDS KEYS를 SCAN으로, P23 정적 자산 캐시 헤더, Q9 lint·tsconfig 게이트, S11 1-인자 `verifyIdToken` deprecate, Q12·Q45 주석 수정.

## 기능 변화가 따르는 항목 (별도 결정 필요)

| 항목 | 바뀌는 것 |
|---|---|
| S1 passkey-app 서명키 reload | 회전 후 새 kid로 서명(현재는 재시작 전까지 옛 kid) |
| S2 세션 무효화 | 정지·해제·재설정 시 기존 세션 즉시 종료 |
| S3/P2 미매핑 RP 경로 catch-all rate tier | 가짜 경로가 임계 초과 시 401 대신 429 |
| S4 attestation 신뢰 강제 | 정책을 쓰는 테넌트에서 none/self 등록 거부 |
| S5 enroll에 TOTP 요구, S17 disable/confirm 실패 카운트 | MFA 사용자의 API 호출 계약, 계정 잠금 |
| S6 mfaRequired 강제 | MFA 미등록 운영자는 로그인 시 등록 강제 |
| S7 forward-headers native 전환 | 신뢰 프록시 설정이 필요하고 기존 테스트 2건이 깨짐 |
| S10 정책 감사의 트랜잭션·actorId·@Valid | 감사 실패 시 롤백, 저장 데이터와 400 응답 변화 |
| S12·S36 rate limit 추가·순서 변경 | 429와 401 응답 변화 |
| S13 비밀번호 확인 길이 검증, Q13 409/404, Q30 F001 통일 | HTTP 오류 코드 변화 |
| S14·S15 actuator·license 노출 축소 | 응답 본문 변화(license 모니터링 문서 영향) |
| S18 서버측 idle, S19 로그아웃 재시도 | 세션 수명 의미 변화 |
| S21 QA 시드 제거, S37 dev fallback 제거 | QA 계정과 dev 기동 방식 |
| S26 오류 메시지 일반화 | 화면에 보이는 message 문구 |
| S27 TraceId 형식 제한 | 규칙에 맞지 않는 ID는 교체 |
| S30·S41·Q47·Q55 기동 시 검증 강화 | 설정 오류면 기동 실패 |
| S31·S32 라이선스 high-water mark, MDS 폐기 확인 | 시계 역행 시 DEAD, CRL 의존 |
| S38·S39 rp-app 사용자명 열거, pending 로그인 | 오류 시점과 엣지 케이스 응답 |
| Q1 검색 스키마 접두사·정렬 | 고장 난 검색이 결과를 반환 |
| Q4 adminFetch `!ok` 우선 검사 | 빈 본문 오류가 성공에서 예외로 |
| Q29·Q40 입력 엄격화 | 비정상 입력 거부 |
| P8·P9 JWKS negative cache와 SWR | 장애 구간의 실패·수락 시점 |
| P11·P12·P18 Activity·Overview·Banner 폴링 변경 | 데이터 신선도와 표시 내용 |
| P15·P39 in-flight 공유 | 변경 직후 stale 응답 가능 |
| P29 touchLastUsed 스로틀 | last_used_at 정밀도 |