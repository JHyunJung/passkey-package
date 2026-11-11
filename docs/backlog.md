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
- [x] ~~**actuator `health.show-details: always` 미인증 노출**~~ — `when-authorized` 로 변경(무인증은 status 만,
      admin-app 운영자만 상세). `ApiContractIT.actuatorHealth_unauthenticated_exposesStatusOnly` 로 고정. [2026-11-11]
- [ ] **passkey-app SecurityFilterChain 부재** — `passkey-app/src/main/kotlin` 전체에 `SecurityFilterChain` 0건.
      actuator 가 완전 미인증이고 nosniff/frame-options 등 기본 보안헤더도 적용되지 않는다. [master-backlog 2026-05-31]
- [ ] **HSTS/CSP 미설정** — `contentSecurityPolicy`/`httpStrictTransportSecurity`/`Strict-Transport-Security`/`Content-Security-Policy`
      가 admin-app·passkey-app·core·deploy·nginx 어디에도 0건. 앱과 프록시 중 어디서 넣을지 결정 필요. [master-backlog 2026-05-31]
- [ ] **recovery code 가 솔트 없는 단일 SHA-256** — `admin-app/.../auth/RecoveryCodeService.kt:42,51` = `CryptoUtils.sha256Hex(...)`.
      pepper/HMAC 없음. [master-backlog 2026-05-31]
- [x] ~~**`X-Trace-Id` 무검증 → 로그 인젝션**~~ — core·rp-app `TraceIdFilter` 가 `[A-Za-z0-9._-]{1,64}` 일 때만 수용,
      아니면 새 ID 발급(요청은 거부하지 않음). 양쪽 `TraceIdFilterTest` 에 CRLF·길이·문자집합 케이스. [2026-11-11]
- [x] ~~**시드 평문 비번이 prod 에 실리지 않는지 확인**~~ — 확인 완료(코드 변경 없음): `application-prod.yml` locations =
      `db/migration,db/prod`(seed-common 미포함). qa 는 의도적으로 seed-common 포함이나 QA 번들은
      `SPRING_FLYWAY_ENABLED=false`. [2026-11-11]
- [x] ~~**(릴리스 차단) QA 번들 이미지가 아직 Spring Boot 3.5 빌드**~~ — `scripts/build-images.sh`(NO_SAVE 없이)로
      main `aa28f5e7` 기준 amd64 이미지 3개 재빌드, `SHA256SUMS` 갱신. 이미지 내부 jar 에서 Boot 4.1.2·나눔고딕·
      `show-details: when-authorized` 확인. infra.tar.gz 는 불변. tar.gz 는 커밋 대상이 아니므로 반출 시 이 머신의
      `qa-bundle/images/` 를 그대로 가져간다. [2026-11-11]
- [x] ~~**sdk-java 패치 릴리스 + 변경 이력**~~ — 1.0.0 이후 공개 API 추가(`RegistrationRelayCodec`, 3-인자
      `verifyIdToken`)가 있어 패치가 아닌 **1.1.0** 으로 올리고 `sdk-java/CHANGELOG.md` 신설(보안·동작 변화 포함).
      외부 `passkey_rp` 저장소의 로컬 jar 는 별도 교체 필요. [2026-11-11]
- [x] ~~**Kotlin 전환(2단계) 착수 전 Kotlin 카탈로그 버전 상향**~~ — **해소** (passkey-app-kotlin 2026-11-06):
      카탈로그 `kotlin = "2.4.21"` 로 올리고 passkey-app 에 적용, Boot BOM `kotlin.version` 도 루트 `build.gradle.kts` 에서
      같은 값으로 덮어쓴다. passkey-app 은 본 코드·테스트 모두 Kotlin. [boot4-upgrade 2026-11-06]

## 1. 기능 개발 (별도 spec 필요)

- [ ] F01 **mfaRequired 강제화** — enroll-required vs verify-required 세션 상태 + 화면(Thymeleaf) 라우팅. 프론트+백 크로스커팅. [zero-base 2026-07-04]
- [ ] **MFA 복구 코드 재발급 백엔드 엔드포인트** — `MfaController` 에 `/verify`·`/enroll`·`/confirm`·`/disable` 만 있고
      재발급 경로가 없다. [admin-ui-followup-screens 2026-05-31]
- [ ] **NAV_RP dead code 해소** — (React 시절 `admin-ui/src/shell/Sidebar.tsx`; Thymeleaf 이식 후 위치는 `templates/admin/fragments/shell/sidebar.html`). RP_ADMIN 네비게이션이 정의돼 있으나
      호출부가 `tenant` 를 채워주지 않아 도달하지 않는다. (Thymeleaf 포트에서 RP_ADMIN 은 [관리] 설정만 노출 — 재확인 후 종결 후보) [rp-admin-role-gating 2026-05-31]
- [ ] **Activity 시간 윈도우 선택(24h/7d/30d)** — `ActivityService.kt:36` = `Duration.ofHours(24)` 하드코딩. [activity-page 2026-05-28]
- [ ] **p95 응답 metric** — Activity KPI 가 'N/A'. admin-app 에 p95 산출 0건. [activity-page 2026-05-28]
- [ ] **Activity 실시간 갱신을 push 로** — (React 시절 `ActivityPage.tsx`; 현재 `templates/admin/activity/*.html` + `web/activity/ActivityPageController`) 가 5초 htmx 폴링이다.
      WebSocket/SSE 로 바꾸면 유휴 부하와 지연이 함께 줄어든다. 서버 측 이벤트 발행 설계가 선행돼야 한다.
      [activity-page 2026-05-28]
- [ ] **Activity KPI·topTenants 초기값이 fixture** — (React 시절 `ActivityPage.tsx` 의 `activityFixture` 초기 state — Thymeleaf 포트(`templates/admin/activity/`, `ActivityPageController`)는 서버 렌더라 fixture 초기값이 없다. 갱신 실패·부분 응답 시 이전 값 잔존 여부만 재확인 필요). 원래 기록: 첫 로딩·첫 오류는 가드가 막지만,
      **갱신 실패(2회차 이후)나 KPI 만 비는 부분 응답에서는 이전 fixture 값이 그대로 남는다**.
      초기값을 null 로 두고 KPI 영역에도 로딩·오류 표시를 따로 두는 편이 안전하다. [master-backlog 2026-05-31]
- [ ] **`ADMIN_LOGIN` 감사 로그에 tenantId 가 없다** — `admin-app/.../config/AdminSecurityConfig.kt:260-262` 가
      `AuditAppendRequest` 의 tenantId 를 `null` 로 넘긴다. RP_ADMIN 로그인도 테넌트 스코프 없이 기록돼
      테넌트별 감사 조회에서 누락된다. [activity-page 2026-05-28]

## 2. 보안·정합성 (P2)

- [ ] **MDC `actorEmail` 이 평문 email 로 로그에 남는다** — `AdminMdcFilter.kt:34-37` 가 `auth.name` 을 request attribute 로
      넘기고 `RequestLoggingFilter.java:110` 이 그대로 MDC 에 넣는다. `SecretMaskingConverter` 는 `%msg` 에만 걸리고
      `%compactMdc` 는 거치지 않으므로, 메시지 본문은 `maskEmail` 로 가려도 MDC 칸에는 평문이 남는다(16자 절단).
      prod JSON 로그의 `includeMdcKeyName` 에도 그대로 실린다. 마스킹 위치를 MDC put 지점으로 옮길지 결정 필요.
      [2026-09-13 문서 감사에서 발견]
- [ ] **credential DELETE 가 registration scope 를 공유** — `passkey-app/.../security/ApiKeyScopeResolver.kt:28-31`.
      파괴적 작업의 scope 분리는 정책 결정 사항. 같은 파일의 `admin` scope 가 dead scope 인 것도 함께. [config-enforcement-B 2026-05-31, master-backlog 2026-05-31]
- [x] ~~**CORS allowlist origin 형식 검증**~~ — 화면은 이미 `PolicyValidation` 으로 검사했고 REST `PUT /admin/api/security-policy`
      만 빠져 있었다. 같은 규칙으로 400(서비스 미호출). `SecurityPolicyControllerSecurityTest` 에 `*`·경로·http 외부 호스트 케이스. [2026-11-11]
- [ ] **`IllegalArgumentException` 메시지를 클라이언트에 그대로 반환** — `core/.../api/GlobalExceptionHandler.java:114-118`
      = `ApiResponse.error(INVALID_INPUT, e.getMessage())`. **결정 필요(2026-11-11 재분류)**: passkey-app 의 IAE 메시지
      (`authentication token missing or expired` 등)는 RP 대상 API 계약이고 contract 스냅샷에 고정돼 있다. 일괄 일반화는
      외부 API 변경이므로, 의도된 메시지는 전용 예외(BusinessException)로 옮기고 나머지만 숨기는 식의 범위 결정이 먼저다. [master-backlog 2026-05-31]
- [x] ~~**`PasswordResetController.confirm` 에 `@Valid`/제약 부재**~~ — 실제로는 REST 경로가 화면의 12자 검사를 우회해 빈·짧은
      비밀번호로 재설정 가능했다. 서비스가 토큰 조회 전에 12–128자(가입과 동일)를 확인하고 DTO 에 `@Valid` 제약. [2026-11-11]
- [ ] **G03 세션 무효화 수단 없음** — 이미 발급된 활성 세션이 idle timeout 까지 유효. admin-app 에 `SessionRegistry` 0건. [zero-base 2026-07-04]
- [ ] **`AdminUser`·`Credential` 에 `@Version`/`@DynamicUpdate` 부재 → 전체컬럼 UPDATE lost-update**
      (core 전체에 두 애너테이션 0건). 락 없이 로드·수정·flush 하면 동시 `/authentication/finish` 의 `signCount` 갱신을 되돌린다.
      F22(rename 비잠금 전환)가 이 이유로 롤백됐다 — **락 제거형 성능개선은 항상 이걸 먼저 의심한다.** [zero-base 2026-07-04]
- [x] ~~**G02 rp-app `StreamReadConstraints` 미설정**~~ — `JacksonLimitsConfig`(Jackson 2 빌더 커스터마이저)로 중첩 64·문자열 256KB.
      문서 크기 상한은 같은 빈이 `InMemoryUserStore` 파일 로드에 쓰여 두지 않음. [2026-11-11]
- [x] ~~**G10 rp-app issuer-base localhost 폴백**~~ — `IssuerBaseGuard` 가 dev/local 외(미지정 포함)에서 비었거나 localhost 면
      기동 차단(`RelayKeyGuard` 와 같은 기준). [2026-11-11]
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
- [ ] **라이선스 DEAD 상태 예외 경로가 React 시절 그대로** — `LicenseGuardFilter` 예외 목록에 Thymeleaf `/admin/license` 화면과
      `/admin/css`·`/admin/js` 정적 자원이 없어 DEAD 상태에서 로그인 화면 자원과 라이선스 페이지가 503 이 된다(main 기존 결함,
      S8 경로 정규화와는 별개). [audit-top10-kotlin 2026-11-10]
- [ ] **탭 조각 로드 실패 시 재시도 URL 을 경로의 원본 id 로 이어 붙임** — `AaguidTabController.kt:101`·`ApiKeysTabController.kt:300`·
      `WebauthnTabController.kt:111`. 같은 사용자의 same-origin GET 이라 영향은 미미하지만 `@{}`/UriComponents 로 인코딩해 만들 것
      (React 시절 S23 의 Thymeleaf 잔여분). [audit-top10-kotlin 2026-11-10]

## 3. 성능 (P2/P3)

- [ ] **트랜잭션 안에서 동기 메일 발송(가입 요청)** — `SignupRequestService.kt`(`sendQuietly` → `mail.send`). SMTP 지연이
      DB 커넥션 점유 시간이 된다. 비밀번호 재설정은 `AfterCommitMailDispatcher` 로 커밋 후 전용 스레드 발송으로 해소됐다(S9) —
      같은 dispatcher 로 옮길 수 있다. [master-backlog 2026-05-31, audit-top10-kotlin 2026-11-10]
- [ ] **S9 메일 큐 포화 시 요청 스레드 직접 발송** — `AfterCommitMailDispatcher` 큐(100)가 차면 호출 스레드에서 보내므로 그 동안
      응답 시간 차이가 다시 생긴다(메일 유실 방지를 우선한 원 설계). [audit-top10-kotlin 2026-11-10]

- [x] ~~**API key BCrypt(cost 12) 를 매 요청 수행**~~ — `VerifiedSecretCache`(30초, apiKeyId·keyHash·SHA-256(secret))로 해소(P1).
      [master-backlog 2026-05-31, audit-top10-kotlin 2026-11-10]
- [ ] **`VerifiedSecretCache` 상한(10,000)이 근사값** — 용량 확인과 삽입이 원자적이지 않아 동시 삽입 시 잠깐 넘을 수 있다.
      인증 판정에는 영향 없음. 원자적 admission 이나 문서 수정. [audit-top10-kotlin 2026-11-10]
- [ ] **P5 체인 헤드 조회가 아직 O(n) 인덱스 전체 스캔** — `findLatestForUpdate` 는 V6 인덱스로 정렬은 없어졌지만(20,005행 376→92 gets)
      Hibernate 잠금 래퍼(`id in (… fetch first 1) for update`)가 STOPKEY 를 잃는다. ROWNUM 네이티브 쿼리
      (`where id in (select id from (… order by sys_extract_utc(created_at) desc, id desc) where rownum <= 1) for update`)는
      5 gets(O(1)) — 네이티브 쿼리 도입 승인 필요. [audit-top10-kotlin 2026-11-10]
- [ ] **사이드바 chain-card 가 숨은 탭에서도 30초마다 폴링** — `sidebar.html` htmx `every 30s`. React 시절 수정(97c08e53·b0506e3e:
      숨은 탭 중지 + 가벼운 `/admin/api/me` keep-alive 로 세션 유지)을 Thymeleaf/Alpine 으로 옮길 때 세션 keep-alive 의미를
      브라우저로 실검증할 것. [audit-top10-kotlin 2026-11-10]
- [ ] **`ApiKeyAdminService.list()` 가 `findAll()` + scopes N+1 + ORDER BY 부재** — `:73` = `repo.findAll().stream()`.
      (zero-base F08 과 동일 지점) [master-backlog 2026-05-31, zero-base 2026-07-04]
- [ ] **`TenantAdminService.list()` KPI 3쿼리 N+1** [master-backlog 2026-05-31]
- [ ] **credential 검색이 `RAWTOHEX(...) LIKE '%q%'` 풀스캔** — `CredentialRepository.java:78-89`. [master-backlog 2026-05-31]
- [ ] **`searchByTenantId` native query 에 ORDER BY 미명시** — `Pageable` 의 Sort 에만 의존해 페이지 경계가 불안정.
      `CredentialRepository.java:78-93`. [admin-tenant-detail 2026-05-27]
- [x] ~~**audit chain overview/verify 가 전체 스캔 + 중복 수행**~~ — overview 가 tenant 당 한 번만 읽어 검증·버킷 집계에 재사용(P3).
      [master-backlog 2026-05-31, audit-top10-kotlin 2026-11-10]
- [ ] **(superseded: Thymeleaf 포트로 해당 없음) admin-ui 코드 스플리팅 부재** — React 시절 372KB 단일 청크. 번들러가 없어졌다(htmx + Alpine vendor 파일). [master-backlog 2026-05-31]
- [ ] **F04 `KeyRotationService` 리스 미해제 · G07 lease 하트비트 부재** — `release` 0건. [zero-base 2026-07-04]
- [ ] **`KeyExpirationJob`/`MdsSyncJob` 도 lease release 패턴으로 통일** — 둘 다 `release` 0건, TTL 만료에만 의존. [data-retention-D 2026-05-31]
- [ ] **F10 `FunnelService` rolling-window vs day-bucket 불일치 · F11 `MdsHistoryService` WITH TIME ZONE 왕복이 JVM TZ 의존**
      — KST 전환(`988d909`) 이후 재확인 필요. [zero-base 2026-07-04]
- [ ] **(React 시절 항목, Thymeleaf 포트에서 재평가 필요) 가상화/디바운스/폰트 CSS/license 3중 fetch/chain overview 중복/idle 리스너 재바인딩** (6건) [master-backlog 2026-05-31]
- [ ] (하위) AAGUID 정책 등록마다 DB 조회, rate-limit Redis 2 RTT, 로그 마스킹 정규식 short-circuit 부재,
      onprem 매 요청 `LicenseStateMachine.recompute()`, CORS negative cache, alert executor 포화. [master-backlog 2026-05-31]

- [ ] **`ADMIN_INVITE_BASE_URL` 이 admin 콘솔 주소와 어긋난다** — 가입 승인·비밀번호 재설정 메일의 링크 base 인데
      `qa-bundle/setup.sh:200` 은 passkey FQDN(`https://${PASSKEY_FQDN}`)을 넣는다. admin-app 은 nginx 뒤가 아니라
      `deploy/docker-compose.yml:73` 의 `ADMIN_BIND_ADDR:ADMIN_HOST_PORT` 로 직접 바인딩되므로 메일 링크가
      실제 콘솔 주소와 다르다. 변수명(invite)도 초대 기능 제거 후 낡았다. [2026-09-13 문서 감사에서 발견]

## 4. 구조·위생 (P3)

- [ ] **RP 연동 체크리스트 문서화** — 삭제된 `2026-05-26-rp-migration-guide.md` 의 미완료 체크리스트
      (RP 측 JWKS 캐시 TTL·키 회전 대응, ID Token 클레임 저장 스키마 점검, 에러 코드 분기 갱신)는
      특정 RP 사업자 cutover 용이라 지금은 수행 주체가 없다. 외부 RP 온보딩이 재개되면
      `docs/rp-server-api.md` 의 "RP 서버 구현자용" 절에 체크리스트로 편입한다. [rp-migration-guide 2026-05-26]

- [ ] **관리자 비밀번호 self-service 변경 경로 부재** — 지금은 운영자가 재설정 메일로만 바꿀 수 있다.
      로그인 상태에서의 변경 엔드포인트, 회전 정책(주기적 변경 요구), 최초 로그인 시 강제 변경이 모두 없다.
      [prod-hardening 2026-05-25]

- [ ] **`AdminUser.role` 을 Java enum 으로** — `core/.../entity/AdminUser.java:16-17` 이 `String` 이고
      `AdminUserDetails.kt:28,38-43` 등이 문자열 리터럴로 비교한다. DB CHECK 제약은 있으나 컴파일 타임
      안전성이 없다. [admin-role-separation 2026-05-27]

- [ ] **`LogRedact` 죽은 헬퍼 정리** — `core/.../logging/LogRedact.java` 를 main 에서 호출하는 곳이 0건인데
      `SecretMaskingConverter.java:21` Javadoc 이 "개발자가 직접 호출" 로 가리켜 오해를 만든다. 실제 표준은
      `CryptoUtils.maskEmail`. 클래스를 지우거나 Javadoc 을 정정한다. [2026-09-13 문서 감사에서 발견]

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
- [ ] **JWT TTL 900 하드코딩** — `AuthenticationFinishService.kt:40` 의 `private const val ID_TOKEN_TTL_SEC = 900L`(사용처 215,217) — 설정으로 외부화되지 않음. [master-backlog 2026-05-31]
- [ ] **`MfaPendingFilter` 가 `getRequestURI()`** — 다른 필터는 `getServletPath()`. `:74`(및 `:71` javadoc).
      **보류(2026-11-11)**: 보안 체인 안이라 `StrictHttpFirewall` 이 비정규 URI(`..`·`;`·인코딩 슬래시)를 먼저 거부하고, 허용 판정이
      정확일치라 우회 경로가 없다. servletPath 로 바꾸면 MockMvc/MockHttpServletRequest 테스트에서 servletPath 가 "" 라 판정이
      조용히 바뀔 위험이 더 크다 — 일관성 외 이득이 없어 손대지 않는다. [master-backlog 2026-05-31]
- [ ] **(superseded: React 제거로 해당 없음) `TweaksPanel` 이 prod 에서도 무조건 렌더 + `as any`** — `:125` 에 `as any` 잔존, `import.meta.env.DEV` 가드 0건.
      `App.tsx` 의 `handleSwitchRole` stub·잔여 `as any` 도 함께. [master-backlog 2026-05-31, rp-admin-role-gating 2026-05-31]
- [ ] **(React 시절 항목, Thymeleaf 포트에서 재평가 필요) 거대 컴포넌트 분리, 확인 다이얼로그 double-submit 가드(Rotate/Revoke), Header '내 계정' 링크, `/admin/favicon.ico` 500(현재 favicon 은 `/admin/img/favicon.png`)**
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
- [x] ~~**월간 감사 보고서 PDF 가 항상 500**~~ — `<meta charset='utf-8'/>` 로 닫아 해결, 테스트는 실제 PDF 텍스트 검증으로 교체. [2026-11-10]
- [x] ~~**월간 감사 보고서 PDF 에 한글이 안 나옴**~~ — 나눔고딕 Regular·Bold 정적 TTF(SIL OFL 1.1, `fonts/OFL.txt` 동봉)를
      `admin-app/src/main/resources/fonts/` 에 번들하고 `useFont(subset=true)` 로 등록해 해결(jar +약 4MB, PDF 는 쓰인 글리프만 포함).
      Noto Sans KR 은 Google Fonts 에 가변 글꼴만 있어 PDFBox 2 미지원이라 제외. 테스트가 PDF 텍스트의 한글을 단정. [2026-11-10]
- [x] ~~**`alert.html` 기본 아이콘 알림이 아이콘 3개를 모두 렌더**~~ — `th:case` 를 바깥 블록으로 분리해 해결, 렌더 스냅샷 갱신. [2026-11-10]
- [ ] **admin-app 기존 약한 테스트** — `AaguidTabControllerTest.saveCallsRestAndTriggersSaved` 의 `Elements` `isNotNull()`(항상 참),
      `CredentialsTabControllerTest` 의 미사용 `holder`, AdminUsers 의 `substring(0,1)` 류 약한 검사. [admin-app-kotlin 2026-11-09]
- [ ] **null tenantId `?: throw IllegalArgumentException` 3곳** (`ApiKeyAdminService`, `TenantLifecycleService`, `WebauthnDiffService`) —
      기준 Java 는 실 컨텍스트에서 `InvalidDataAccessApiUsageException`(500) 이었고 지금은 IAE(400 매핑). 정상 경로에서는 도달 불가이며,
      응답 DTO 필드 nullability tightening 시 함께 정리한다. [admin-app-kotlin 2026-11-09]
- [ ] **응답 DTO(`TenantView` 등) nullable 필드로 web 컨트롤러에 `!!` 잔존** — DTO tightening 을 별도 작업으로 진행. [admin-app-kotlin 2026-11-09]
- [ ] **MDS 설정 화면의 endpoint 표시가 하드코딩** — `templates/admin/settings/mds.html` 이 `https://mds.fidoalliance.org/` 를
      문자열로 박아 두어 `passkey.mds.blob-endpoint` 를 바꿔도 화면은 그대로다. 컨트롤러 모델에 실제 설정값을 실어 렌더하고
      contract 스냅샷 4개(`contract/html/settings/mds.*.html`)를 갱신한다. (기본 주소 `mds3.`→`mds.` 301 동기화 실패는 2026-11-10 해결) [backlog-fix 2026-11-10]

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
  (React 시절 `admin-ui/package.json` = `"^5.4.6"`; admin-ui 는 삭제됨)
- prod 는 `vite build` 정적 산출물만 서빙하므로 **dev-server-only** 이고 운영 노출은 0 이다.
- **재평가 트리거**: (admin-ui 삭제로 **superseded** — Vite 사용 안 함) [QW-3 2026-05-31]

### 추적만 (조치 불요)

- flyway 핀 `10.20.1`(`libs.versions.toml:6`) 이 Boot BOM 11.7.2 로 무시된다 — 핀이 무의미하다는 인지. [master-backlog 2026-05-31]
- testcontainers `1.20.4` + `api.version=1.43` 핀(`libs.versions.toml:5`). [prod-hardening 2026-05-25]
- pdfbox/fontbox 2.0.24 transitive(openhtmltopdf 경유) — 미확인. [master-backlog 2026-05-31]

## 6. 의도적 보류 결정 (재검토 트리거만 기록 — 실행 대상 아님)

- **MDS `nextUpdate` null fail-open 우려는 사실무근** — 원 백로그(zero-base 2026-07-04)가
  `NativeMetadataBlobVerifier.java:34` 의 `nextUpdate != null` 가드를 fail-open 으로 봤으나,
  블롭은 항상 `MdsBlobParser.parse()` 를 거치고 `:36` 이 `parseDate(..., required=true)` 로
  누락·null 을 `MALFORMED_PAYLOAD` 예외로 먼저 막는다. 검증기의 null 분기는 도달 불가.
  파서를 우회하는 새 경로를 만들 때만 재검토. [2026-09-13 문서 감사에서 확인]

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
- **감사 Top 10 배포 노트** [audit-top10-kotlin 2026-11-10]
  - **배포 전 게이트 — Oracle 세션 여유**: passkey-app DB 풀 기본값이 10→20. 대상 DB 에서
    `SELECT name, value FROM v$parameter WHERE name IN ('processes','sessions')` 와 계정 `SESSIONS_PER_USER` 를 확인한다.
    필요 세션 ≈ (롤링 피크 포함 passkey-app 인스턴스 수) × 20 + admin 10 + Flyway + DBA·모니터링(README 구성 기준 약 251, 변경 전 약 131).
    여유가 없으면 `.env` 의 `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE`·`SERVER_TOMCAT_THREADS_MAX` 를 **워커 ≤ 풀 − 1** 로 함께 낮춘다
    (기동 시 이 관계를 검사하지 않으므로 풀만 낮추면 30초 정지 문제가 되살아난다).
  - **Tomcat 워커 200→19(passkey-app)**: 처리량이 바뀌는 변경 — 목표 동시성으로 부하 테스트.
  - **V6 인덱스**: SE2 라 ONLINE 빌드 불가 → audit_log 테이블 락. admin-app 한 대로, 관리 작업이 적은 시간에. ORA-00054 로
    실패하면 막는 세션 정리 후 `flyway repair` → 재기동. QA 번들(Flyway off)은 DBA 가 `scripts/full-schema-part2-schema.sql` 의
    V6 문장을 수동 실행. 앱·DB 배포 순서는 무관(V6 없이도 결과 동일).
  - **S16 세션**: 강제 로그아웃 불필요 — 기존 Redis 세션은 만료·재로그인 전까지 해시 보유, 새 로그인부터 미보유. 롤백 안전.
