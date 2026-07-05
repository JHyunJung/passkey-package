# 제로베이스 재감사 후속 백로그

- 날짜: 2026-07-05
- 출처: `docs/superpowers/specs/2026-07-04-zero-base-reaudit-design.md` 실행
- confirmed 33건 중 32건 구현(main 머지 예정), 이 문서는 미실행·후속 항목

---

## 배포 전 필수 (whole-branch 최종 리뷰가 격상)

- **forward-headers 신뢰 프록시 조건** — 배치 B·F가 admin-app·passkey-app에
  `server.forward-headers-strategy: framework`를 켰다. 이 전략은 `X-Forwarded-*`를
  무조건 신뢰(화이트리스트 없음)하므로, **앞단 LB/리버스프록시가 반드시 클라이언트發
  X-Forwarded-For/Proto를 스트립/재작성**해야 한다. 안 하면 단일 배포 실수로
  G13(감사 IP 스푸핑)·F-cookieSecure(평문 Secure 쿠키 유도)·F-ratelimitProxy(IP 버킷 우회)
  3개 fix가 동시 무력화. 코드 변경 불필요, 배포 체크리스트 필수 항목.
  후속 하드닝: `server.tomcat.remoteip.internal-proxies` 화이트리스트 검토.
- **부팅 가드 환경변수** — G10(issuer-base)·G12(deployment.mode)가 fail-fast 부팅 가드다.
  prod 배포 시 `PASSKEY_ID_TOKEN_ISSUER_BASE`(절대 URL)와 `passkey.deployment.mode`가
  반드시 설정돼야 부팅 성공(현재 common.yml에 mode=saas 기본값 존재). 의도된 동작.

## F01 — 별도 spec 필요 (프론트+백 크로스커팅)

- **F01 mfaRequired 강제화**: 백엔드 게이트(`policy.mfaRequired() && !u.isMfaEnabled()` →
  enroll-pending)는 1줄이나, 단독 적용 시 MFA 미등록 운영자가 enroll UI(MfaChallenge=verify
  전용)에 도달 못 해 자기 계정 락아웃 = 새 가용성 장애. MfaPendingFilter·isPending()이
  pending 중 `/admin/api/mfa/enroll`을 차단하고 App.tsx가 mfaRequired=true면
  AuthenticatedApp(enroll 있는 Settings) 미렌더. 필요: **enroll-required vs verify-required
  세션 상태 구분 + admin-ui 라우팅/enroll 화면**. 코드 결함이 아니라 기능 개발이라 별도 spec.

## 미재발견 8건 — 이번 렌즈 맹점 (재확인 필요)

기존 2026-07-01 B그룹 중 이번 재감사가 못 잡음. 패턴 = **"파일당 1건 편향"**
(같은 파일 인접 결함 중 하나만 잡음). 향후 감사 시 이미 결함 발견된 파일도 전체 재정독.

- **F04** KeyRotationService:84 리스 미해제 — 연속 회전 TTL 30s 차단
- **F08** ApiKeyAdminService:73 ORDER BY 없는 findAll() 정렬 불안정
- **F10** FunnelService:54 rolling-window vs day-bucket 불일치(F06과 같은 파일 인접)
- **F11** MdsHistoryService:41 WITH TIME ZONE 왕복 JVM TZ 의존
- **F14** LicenseStateMachine:114 grace 정수절단(onprem)
- **F15** LicenseBootstrap:51 cache-vs-disk tenantId 미검증(onprem+변조 전제)
- **F30** NativeWebAuthnVerifier ~190 인증경로 BE/BS 검사(F31과 같은 파일 인접)
- **F35** CborDecoder:112 CTAP2 non-minimal int 인코딩 수용

## 구현 배치별 후속 (Minor·범위 밖·CI 대기)

### 동시성·검증 실증 (Docker 부재로 CI 대기)
- G06/G08/G04 Oracle FOR UPDATE write-skew 방어 실DB 실증(현재 이론·코드리뷰).
- G13/F-cookieSecure 감사 IP·실브라우저 Set-Cookie 실증(현재 unit+yml 검증).

### 세션·감사 완결성
- G03 세션 무효화: 로그인 게이트는 fail-closed지만 이미 발급된 활성 세션은
  idle timeout(~30분)까지 유효. SessionRegistry(Spring Session Redis 인덱스로 대상 유저
  세션 expire) 배선 필요. 위험 제한적(타임아웃 상한).
- G04 resendInvitation 미감사: resend도 lifecycle 변경이나 감사 append 5개에 미포함. Low.

### 인터페이스·오분류 명확화
- G01 DataIntegrityViolationException catch가 uq_credential_id 외 PK/FK도 409로 포괄.
  실질 위험 낮음(PK=Uuid TIME 자동, FK 사전확인). 향후 스키마 변경 시 오분류 → catch 주석
  또는 SQLState 구분. YAGNI로 이번 미수정.
- ActivityRepository feedPageRaw/feedFilteredPageRaw: beforeId!=null이면 :before 미참조
  (서브쿼리로 재조회). before/beforeId가 다른 행 가리키면 조용히 before 무시. 불변식 코드
  미강제(문서화만). 인터페이스 명확화.

### 표면 확장 (동일 결함 유형 잔존)
- G02 rp-app 동일 DoS 표면(StreamReadConstraints 미설정) — passkey-app만 수정됨.
- G10 rp-app issuer-base http://localhost:8080 폴백 드리프트 잔존(SDK 소비측).
- AuthenticationStartRequest.userHandle 길이 검증 일관화(조회 전용이라 500 없음).

### 성능·운영
- G07 스케줄러 lease 하트비트 부재: TTL 5분 1회, 사이클 5분 초과 시 다른 인스턴스가
  lease 탈취→이중 실행 가능(pre-existing). 하트비트/파이프라인 후속.
- G16 countKeys client-side O(N): SCAN 배치 다회 왕복(의도된 트레이드오프, KEYS 블로킹 제거).
- lockout @Value 기본값 AdminSecurityConfig 2곳+MfaController 1곳 중복(pre-existing).

### MDS·스펙 대조
- G21 nextUpdate null 시 "무조건 fresh" fail-open 성격(MDS3 payload 필수 필드인지 미확인).
- MDS3 §3.1.6 nextUpdate 의미(신선도 기준 적합)를 1차 문서로 미대조 — 배포 전 재확인.

### 반복 지적된 구조 패턴 (누적 리스크, 실위협은 아님)
- AdminUser·Credential @Version/@DynamicUpdate 부재 → 전체컬럼 UPDATE lost-update 여지.
  최종 리뷰 판정: 세션당 순차 접근이라 브랜치 전체에서 실위협 아님, 이론적 여지만.
  락/버전 도입은 동작변경이라 별도 검토.

## 테스트 주석 (경미)
- ActivityRepositoryFeedPageIT: UUID 정렬이 @UuidGenerator(Style.TIME) 바이트 레이아웃에
  암묵 의존(Style.RANDOM 전환 시 flaky). 주석 1줄 권고.
