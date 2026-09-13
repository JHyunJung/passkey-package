# 백로그 (미해소 항목 단일 소스)

> 2026-09-13 통합. 원천은 `docs/superpowers/followups/` 21개 파일(2026-05-25 ~ 2026-08-07)이며 통합과 함께 삭제했다.
> 완료·무효 항목은 제외했고, 각 행 끝의 `[파일-날짜]` 가 출처다. 원문이 필요하면 git 이력을 조회한다:
> `git log --all -- docs/superpowers/followups`
>
> 항목은 통합 시점의 코드로 재확인했다. 파일:줄 표기는 2026-09-13 기준이며 이후 이동할 수 있다.

## 0. 배포 전 필수 (P1 — 코드 변경이 필요 없는 운영 전제 포함)

- [ ] **forward-headers 신뢰 프록시 전제** — 앞단 LB/프록시가 클라이언트發 `X-Forwarded-*` 를 반드시 스트립해야 한다.
      `admin-app/src/main/resources/application.yml:50`, `passkey-app/src/main/resources/application.yml:19` 둘 다
      `forward-headers-strategy: framework` 이고 `internal-proxies` 화이트리스트가 없다.
      스트립하지 않으면 감사 IP 스푸핑·Secure 쿠키 오판·rate-limit IP 우회가 동시에 성립한다. [zero-base 2026-07-04]
- [ ] **actuator `health.show-details: always` 미인증 노출** — `core/src/main/resources/application-common.yml:46`.
      prod/qa 오버라이드 없음. 노출 엔드포인트는 `:51` 에서 `health,info` 로 제한돼 있다. [master-backlog 2026-05-31]
- [ ] **passkey-app SecurityFilterChain 부재** — `passkey-app/src/main/java` 전체에 `SecurityFilterChain` 0건.
      actuator 가 완전 미인증이고 nosniff/frame-options 등 기본 보안헤더도 적용되지 않는다. [master-backlog 2026-05-31]
- [ ] **HSTS/CSP 미설정** — `contentSecurityPolicy`/`httpStrictTransportSecurity`/`Strict-Transport-Security`/`Content-Security-Policy`
      가 admin-app·passkey-app·core·deploy·nginx 어디에도 0건. 앱과 프록시 중 어디서 넣을지 결정 필요. [master-backlog 2026-05-31]
- [ ] **recovery code 가 솔트 없는 단일 SHA-256** — `admin-app/.../auth/RecoveryCodeService.java:46,54` = `CryptoUtils.sha256Hex(...)`.
      pepper/HMAC 없음. [master-backlog 2026-05-31]
- [ ] **`X-Trace-Id` 무검증 → 로그 인젝션** — `core/.../api/TraceIdFilter.java:27-31` 이 `isBlank()` 만 보고 헤더 값을
      MDC 와 응답 헤더에 그대로 넣는다. CRLF·길이·문자집합 제한 없음. rp-app 의 동명 필터
      (`rp-app/.../common/filter/TraceIdFilter.java:30-35`)도 같다. [master-backlog 2026-05-31]
- [ ] **MDS3 §3.1.6 `nextUpdate` 의미를 1차 문서와 대조** — `webauthn/.../mds/NativeMetadataBlobVerifier.java:34-37` 은
      `nextUpdate != null` 일 때만 신선도를 강제하므로 null 이면 fail-open 이다. 사양상 필수 필드인지 확인. [zero-base 2026-07-04]
- [ ] **시드 평문 비번이 prod 에 실리지 않는지 확인** — `core/src/main/resources/db/seed-common/R__seed_operators.sql:4-5`
      에 "prod 는 이 디렉터리를 `flyway.locations` 에 포함하지 않는다" 로 명시돼 있다. 배포 전 실제 locations 로 재확인.
      (bob 은 더 이상 이 파일에서 시드하지 않는다 — alice 만.) [prod-hardening 2026-05-25]

## 1. 기능 개발 (별도 spec 필요)

- [ ] F01 **mfaRequired 강제화** — enroll-required vs verify-required 세션 상태 + admin-ui 라우팅. 프론트+백 크로스커팅. [zero-base 2026-07-04]
- [ ] **MFA 복구 코드 재발급 백엔드 엔드포인트** — `MfaController` 에 `/verify`·`/enroll`·`/confirm`·`/disable` 만 있고
      재발급 경로가 없다. [admin-ui-followup-screens 2026-05-31]
- [ ] **NAV_RP dead code 해소** — `admin-ui/src/shell/Sidebar.tsx:32,175`. RP_ADMIN 네비게이션이 정의돼 있으나
      호출부가 `tenant` 를 채워주지 않아 도달하지 않는다. [rp-admin-role-gating 2026-05-31]
- [ ] **Activity 시간 윈도우 선택(24h/7d/30d)** — `ActivityService.java:43` = `Duration.ofHours(24)` 하드코딩. [activity-page 2026-05-28]
- [ ] **p95 응답 metric** — Activity KPI 가 'N/A'. admin-app 에 p95 산출 0건. [activity-page 2026-05-28]

## 2. 보안·정합성 (P2)

- [ ] **MDC `actorEmail` 이 평문 email 로 로그에 남는다** — `AdminMdcFilter.java:42` 가 `auth.getName()` 을 request attribute 로
      넘기고 `RequestLoggingFilter.java:110` 이 그대로 MDC 에 넣는다. `SecretMaskingConverter` 는 `%msg` 에만 걸리고
      `%compactMdc` 는 거치지 않으므로, 메시지 본문은 `maskEmail` 로 가려도 MDC 칸에는 평문이 남는다(16자 절단).
      prod JSON 로그의 `includeMdcKeyName` 에도 그대로 실린다. 마스킹 위치를 MDC put 지점으로 옮길지 결정 필요.
      [2026-09-13 문서 감사에서 발견]
- [ ] **credential DELETE 가 registration scope 를 공유** — `passkey-app/.../security/ApiKeyScopeResolver.java:24-27`.
      파괴적 작업의 scope 분리는 정책 결정 사항. 같은 파일의 `admin` scope 가 dead scope 인 것도 함께. [config-enforcement-B 2026-05-31, master-backlog 2026-05-31]
- [ ] **CORS allowlist origin 형식 검증** — `SecurityPolicyDto.java:29` 에 `@NotBlank @Size(max=256)` 는 있으나
      `^https?://[^/]+$` 형식·`*` 금지 검증은 없다. [master-backlog 2026-05-31]
- [ ] **`IllegalArgumentException` 메시지를 클라이언트에 그대로 반환** — `core/.../api/GlobalExceptionHandler.java:114-118`
      = `ApiResponse.error(INVALID_INPUT, e.getMessage())`. 내부 메시지 노출. [master-backlog 2026-05-31]
- [ ] **`PasswordResetController.confirm` 에 `@Valid`/제약 부재** — `confirm(@RequestBody ConfirmBody body)` 에 검증 애너테이션 0건.
      실패는 서비스의 `IllegalArgumentException` → 400 으로만 매핑된다. [master-backlog 2026-05-31]
- [ ] **G03 세션 무효화 수단 없음** — 이미 발급된 활성 세션이 idle timeout 까지 유효. admin-app 에 `SessionRegistry` 0건. [zero-base 2026-07-04]
- [ ] **`AdminUser`·`Credential` 에 `@Version`/`@DynamicUpdate` 부재 → 전체컬럼 UPDATE lost-update**
      (core 전체에 두 애너테이션 0건). 락 없이 로드·수정·flush 하면 동시 `/authentication/finish` 의 `signCount` 갱신을 되돌린다.
      F22(rename 비잠금 전환)가 이 이유로 롤백됐다 — **락 제거형 성능개선은 항상 이걸 먼저 의심한다.** [zero-base 2026-07-04]
- [ ] **G02 rp-app `StreamReadConstraints` 미설정** — rp-app 에 0건. passkey-app 만
      `PasskeyAppJacksonConfig.java:31-37` 로 설정돼 있다. [zero-base 2026-07-04]
- [ ] **G10 rp-app issuer-base localhost 폴백** — `rp-app/src/main/resources/application.yml:30`
      = `${PASSKEY_ISSUER_BASE:http://localhost:8080}`. 환경변수 누락 시 조용히 localhost 로 떨어진다. [zero-base 2026-07-04]
- [ ] **F30 `NativeWebAuthnVerifier` BE/BS 플래그 검사 · F35 `CborDecoder` non-minimal int 거부** [zero-base 2026-07-04]
- [ ] **alert throttle/dedup 미구현** — 고빈도 이벤트 폭주 시 알림 스톰. 훅만 존재. [observability-C 2026-05-31]
- [ ] **SDK 429 → 500 오분류** — `PasskeyClientContractIT` 의 429 path 보강. [sdk-and-sample-rp 2026-05-27]
- [ ] **F14 onprem license grace 정수절단 · F15 cache-vs-disk tenantId 미검증** [zero-base 2026-07-04]
- [ ] **Oracle `FOR UPDATE` write-skew · Set-Cookie 실증** — Docker 부재로 미실증, CI 대기. [zero-base 2026-07-04]
- [ ] **G01 `DataIntegrityViolation` 오분류 · `ActivityRepository` before·beforeId 불변식 미강제** [zero-base 2026-07-04]
- [ ] **password-reset request 타이밍 사이드채널** — 2회 지적. [admin-account-security 2026-05-31, admin-ui-followup-screens 2026-05-31]
- [ ] **비번재설정 토큰 prefix 8자를 로그·DB 에 기록 · confirm 오류 메시지가 토큰 상태 단계를 노출** [master-backlog 2026-05-31]
- [ ] **mfa_secret 평문 → 암호문 백필 배치 부재** — `MfaSecretCipher.open()` 의 passthrough 에 의존.
      legacy 평문 판별이 프리픽스 부재에만 의존하는 것도 함께. [admin-account-security 2026-05-31, master-backlog 2026-05-31]
- [ ] **signing key 자동 회전 스케줄러 부재** — `KeyRotationService` 는 수동, `KeyExpirationJob` 은 만료 전용. [master-backlog 2026-05-31]
- [ ] **`AuthenticationStartRequest.userHandle` 길이 검증 일관화 · unknown-prefix 타이밍 오라클** [zero-base 2026-07-04, master-backlog 2026-05-31]

## 3. 성능 (P2/P3)

- [ ] **API key BCrypt(cost 12) 를 매 요청 수행 + 전역 캐싱 인프라 부재** — `@EnableCaching`/Caffeine/`@Cacheable`
      이 core·passkey-app·admin-app 통틀어 0건. [master-backlog 2026-05-31]
- [ ] **`ApiKeyAdminService.list()` 가 `findAll()` + scopes N+1 + ORDER BY 부재** — `:73` = `repo.findAll().stream()`.
      (zero-base F08 과 동일 지점) [master-backlog 2026-05-31, zero-base 2026-07-04]
- [ ] **`TenantAdminService.list()` KPI 3쿼리 N+1** [master-backlog 2026-05-31]
- [ ] **credential 검색이 `RAWTOHEX(...) LIKE '%q%'` 풀스캔** — `CredentialRepository.java:78-89`. [master-backlog 2026-05-31]
- [ ] **`searchByTenantId` native query 에 ORDER BY 미명시** — `Pageable` 의 Sort 에만 의존해 페이지 경계가 불안정.
      `CredentialRepository.java:78-93`. [admin-tenant-detail 2026-05-27]
- [ ] **audit chain overview/verify 가 전체 스캔 + 중복 수행** [master-backlog 2026-05-31]
- [ ] **admin-ui 코드 스플리팅 부재** — 372KB 단일 청크. `App.tsx`/`vite.config.ts` 에 `React.lazy`·`manualChunks` 0건.
      `QrCode.tsx:2` 의 `qrcode` eager import 포함. [master-backlog 2026-05-31]
- [ ] **F04 `KeyRotationService` 리스 미해제 · G07 lease 하트비트 부재** — `release` 0건. [zero-base 2026-07-04]
- [ ] **`KeyExpirationJob`/`MdsSyncJob` 도 lease release 패턴으로 통일** — 둘 다 `release` 0건, TTL 만료에만 의존. [data-retention-D 2026-05-31]
- [ ] **F10 `FunnelService` rolling-window vs day-bucket 불일치 · F11 `MdsHistoryService` WITH TIME ZONE 왕복이 JVM TZ 의존**
      — KST 전환(`988d909`) 이후 재확인 필요. [zero-base 2026-07-04]
- [ ] **admin-ui 가상화/디바운스/폰트 CSS/license 3중 fetch/chain overview 중복/idle 리스너 재바인딩** (6건) [master-backlog 2026-05-31]
- [ ] (하위) AAGUID 정책 등록마다 DB 조회, rate-limit Redis 2 RTT, 로그 마스킹 정규식 short-circuit 부재,
      onprem 매 요청 `LicenseStateMachine.recompute()`, CORS negative cache, alert executor 포화. [master-backlog 2026-05-31]

## 4. 구조·위생 (P3)

- [ ] **core 엔티티 23개가 단일 패키지** → passkey-app 이 쓰지 않는 엔티티까지 스키마 검증·권한을 요구한다.
      `SchedulerLease`(2026-05-25) → `SecurityIncident`(2026-08-07) 로 재발했고 V4 GRANT 로 우회 중. **구조적 미해소.**
      [prod-hardening 2026-05-25, docker-deployment 2026-08-07]
- [ ] **`AdminITBase` 공통 추출** — admin-app 테스트에 `*ITBase*` 0건. 3회 반복 지적.
      [admin-role-separation 2026-05-27, admin-tenant-detail 2026-05-27]
- [ ] **`timeAgo`/`fmtDateTime` 이 10개 파일에 복붙** → `src/lib/formatDateTime.ts` 로 통합.
      (Sidebar, ActivityPage, AuditChainPage, TenantsListPage, CredentialsTab, ApiKeysTab, TenantOverview, AuditTab, AdminUsersTab, MdsStatusTab) [master-backlog 2026-05-31]
- [ ] **이벤트 타입 분류/색상이 ActivityPage vs AuditTab 로 이원화** [master-backlog 2026-05-31, activity-page 2026-05-28]
- [ ] **`uuidToBytes`/`uuidFromBytes` 를 `CryptoUtils` 로** — `IdTokenIssuer.java:91`, `DefaultAaguidPolicyChecker.java:100` 의
      private 중복. `sha256Hex`/`hex`/`maskEmail`/`randomToken` 은 이미 통합됐다. [master-backlog 2026-05-31]
- [ ] **JWT TTL 900 하드코딩** — `AuthenticationFinishService.java:232,234` 리터럴 2곳. [master-backlog 2026-05-31]
- [ ] **`MfaPendingFilter` 가 `getRequestURI()`** — 다른 필터는 `getServletPath()`. `:74`(및 `:71` javadoc). [master-backlog 2026-05-31]
- [ ] **`TweaksPanel` 이 prod 에서도 무조건 렌더 + `as any`** — `:125` 에 `as any` 잔존, `import.meta.env.DEV` 가드 0건.
      `App.tsx` 의 `handleSwitchRole` stub·잔여 `as any` 도 함께. [master-backlog 2026-05-31, rp-admin-role-gating 2026-05-31]
- [ ] **거대 컴포넌트 분리, 확인 다이얼로그 double-submit 가드(Rotate/Revoke), Header '내 계정' 링크, `/admin/favicon.ico` 500**
      [master-backlog 2026-05-31, admin-ui-followup-screens 2026-05-31, admin-ui-mfa-suspend 2026-05-30]
- [ ] **`PasskeyResponseErrorHandler` deprecation** — Spring major bump 시 필수.
      `sdk-java/.../internal/PasskeyResponseErrorHandler.java` 존재. [monorepo-merge 2026-05-28]
- [ ] **`JwksCache` thundering herd** — sdk-java. [sdk-and-sample-rp 2026-05-27]
- [ ] **Tenant rpId 변경 워크플로우 미구현** [admin-tenant-detail 2026-05-27]
- [ ] **ceremony metrics 4xx/5xx 미구분 · `ApiKeyAuthFilter` malformed header 미계측(6분기 산재)
      · `AlertConfig` 로그 라벨 오기 · `MailAlertChannel` 테스트 미커버 · `SECURITY_ALERT` 마커 라우팅 미설정** [observability-C 2026-05-31]
- [ ] **rotation minor** — grace `@Value` / inactive rotate 404 / DTO naming / 동시 rotate. [config-enforcement-B 2026-05-31]
- [ ] **lockout `@Value` 3곳 중복 · G16 `countKeys` O(N)** [zero-base 2026-07-04]
- [ ] **`ActivityRepositoryFeedPageIT` 가 UUID 정렬에 암묵 의존** — 주석으로만 표시. [zero-base 2026-07-04]
- [ ] **`CredentialRecord` 봉투가 `uvInitialized`/`backupState` 를 폐기하는 건** — 자체 verifier 전환 + V47 의
      `credential.backup_state` 컬럼 제거로 전제가 바뀌었다. 재평가 필요. [prod-hardening 2026-05-25]

## 5. 의존성 — 결정 기록 (KEEP + 근거, 재평가 트리거)

취약점 조회 근거와 "우리 코드가 왜 안전한가"를 보존한다. 삭제하면 다음 감사에서 같은 CVE 를 다시 조사하게 된다.

### nimbus-jose-jwt **9.40 — KEEP**

- 취약점: CVE-2025-53864 / GHSA-xwmg-2g98-w7v9.
- 9.x 내 forward fix 없음 — 9.37.4 는 백포트이고 전향 수정은 major `10.0.2` 다.
  (`gradle/libs.versions.toml:8` = `"9.40"`)
- 노출이 gated 인 이유: `IdTokenVerifier`·`LicenseVerifier` 둘 다 `jwt.verify()` 가 성공한 **뒤에만**
  `getJWTClaimsSet()` 을 호출한다. 우리/벤더 키로 서명된 토큰만 파서에 도달한다.
- **재평가 트리거**: Spring Boot major bump, 또는 SDK 를 외부 배포할 때 `10.0.2` 전환. [QW-3 2026-05-31]

### vite **^5.4.6 — KEEP**

- 취약점: GHSA-67mh-4wv8-2f99 (esbuild 0.21.5 경유). 수정은 Vite 6(major).
  (`admin-ui/package.json:34` = `"^5.4.6"`)
- prod 는 `vite build` 정적 산출물만 서빙하므로 **dev-server-only** 이고 운영 노출은 0 이다.
- **재평가 트리거**: admin-ui 차기 업그레이드 시 Vite 6. [QW-3 2026-05-31]

### 추적만 (조치 불요)

- flyway 핀 `10.20.1`(`libs.versions.toml:6`) 이 Boot BOM 11.7.2 로 무시된다 — 핀이 무의미하다는 인지. [master-backlog 2026-05-31]
- testcontainers `1.20.4` + `api.version=1.43` 핀(`libs.versions.toml:5`). [prod-hardening 2026-05-25]
- pdfbox/fontbox 2.0.24 transitive(openhtmltopdf 경유) — 미확인. [master-backlog 2026-05-31]

## 6. 의도적 보류 결정 (재검토 트리거만 기록 — 실행 대상 아님)

- **`NavBtn` React.memo 미적용** — map 내부 인라인 `onClick` 때문에 memo 효과가 0. nav 항목이 폭증하면 재검토. [qw5 2026-05-31]
- **`AuditTab` 행당 `JSON.stringify`** — payload 가 큰 테넌트에서 측정상 병목이 확인되면 재검토. [qw5 2026-05-31]
- **lockout lost-update** — defender-favorable 방향이라 변경 불요. **pwreset confirm TOCTOU** — 1회용·operator-initiated. [admin-account-security 2026-05-31]
- **audit_log purge 제외** — 이중 hash-chain 때문. 감사 보존기간 단축 요구가 생기면 anchor-based purge 를 별도 phase 로. [data-retention-D 2026-05-31]
- **credential soft-delete 미도입** — revoke 즉시 hard-delete, 이력은 audit_log 로 남긴다. [data-retention-D 2026-05-31, admin-tenant-detail 2026-05-27]

## 7. 운영 지식 (코드 백로그 아님)

- `/actuator/prometheus` 기본 비노출 — 이미 [deploy/README.md](../deploy/README.md) 의 환경변수 표에 기재돼 있다. [observability-C 2026-05-31]
- **최초 기동 순서**: admin-app(Flyway) 이 healthy 가 된 뒤 passkey-app. 반대면 passkey-app 의 validate 실패 로그가 정상이다. [docker-deployment 2026-08-07]
- **빌드 전 `docker pull eclipse-temurin:17-jre` 선행** — VM 내부 네트워크에서 타임아웃이 난다. [docker-deployment 2026-08-07]
- **dev 시드가 남은 DB 에 prod 프로필로 기동하면 Flyway 가 거부한다**(의도된 동작). 볼륨을 재생성한다. [docker-deployment 2026-08-07]
- **GRANT 누락 전수 조사**: `user_tab_privs_made` 안티조인 쿼리. 컬럼 GRANT 는 `user_col_privs_made` 를 따로 봐야 한다. [docker-deployment 2026-08-07]
- **`MASTER_KEY` 는 정확히 32바이트** — `openssl rand -base64 32`. [docker-deployment 2026-08-07]
