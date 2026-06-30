# rp-app 고객사 샘플 주석·문서 재작성 설계

- 날짜: 2026-06-30
- 대상 모듈: `rp-app` (35개 main Java 소스 주석 + 신규 `rp-app/README.md`)
- 목적: rp-app을 **고객사 RP 구축 레퍼런스 샘플**로 명확히 하기 위해, 내부 개발 맥락 주석을 걷어내고 고객사 개발자가 이해할 수 있는 주석·문서로 재작성

## 1. 배경과 목표

rp-app은 두 역할을 겸한다: ① 로컬 데모 RP 서버, ② **고객사가 자사 RP를 구축할 때 참고하는 샘플 프로젝트**. 방금 Kotlin→Java 전면 환원([[2026-06-30-rp-app-kotlin-to-java-design]], main 머지 1b9abc3)으로 코드는 순수 Java가 됐으나, 주석에는 두 부류가 섞여 있다:

- **고객사에 유용한 도메인/설계 주석** — tenantId 정규화, iss/aud 검증, 무상태 relay 등
- **내부 개발 맥락 주석** — `spec §5`, `P0-4`, `core 의 twin`, `Mirror of com.crosscert.passkey.core…`, `원본 Java 는 record 였다`, `@JvmRecord 우회`, `drift 방지` 등. 고객사 개발자에겐 무의미하거나 혼란을 준다.

목표:
1. 35개 Java 소스의 주석을 **고객사 관점으로 전면 재작성**한다(내부 맥락 제거, 보안·설계 의도는 고객사 언어로 보존·강화).
2. `rp-app/README.md`를 신규 작성한다 — "이 SDK로 RP를 어떻게 조립하는가"의 통합 가이드. `sdk-java/README.md`(SDK 사용법)와 짝을 이루고 중복은 링크로 처리.
3. 언어: **한국어**(기존 코드베이스·sdk-java README와 일관).
4. 동작 변경 0 — 주석/문서만 변경.

### 비목표 (YAGNI)
- 코드 로직·시그니처·엔드포인트 변경.
- 테스트 코드 주석 재작성(테스트는 검증 증거이지 고객사 학습 대상이 아님 — 범위 외).
- 루트 README 수정(별도 작업). 단 rp-app/README가 루트 README의 구식 엔드포인트 표기와 모순되지 않도록 실제 경로를 정확히 쓴다.
- sdk-java/README.md 수정(이미 완성).

## 2. 주석 계층 구조 (파일 역할별 차등)

고객사 개발자가 "어디부터, 무엇을 봐야 하는지" 알 수 있도록 4계층으로 주석 밀도를 차등한다.

### 계층 1 — 핵심 플로우 (진입점, 가장 상세)
`web/WebAuthnController`, `web/relay/RegRelayCodec`, `config/PasskeyClientConfiguration`
- **클래스 Javadoc**: 이 클래스가 RP 플로우에서 맡는 역할 + passkey-app과의 관계.
- **메서드 Javadoc**: 각 엔드포인트의 2-step 시퀀스(begin/finish), 무상태 relay를 쓰는 이유.
- **인라인**: 보안 결정(HMAC 서명, iss/aud 검증, tenantId 정규화, 상수시간 비교)을 "고객사가 자기 RP에서도 해야 하는 이유"로 서술.

### 계층 2 — 설정/보안/저장소 (고객사가 직접 바꾸는 곳, 상세)
`config/PasskeyProperties`, `config/CorsProperties`, `config/RelayProperties`, `config/RelayKeyGuard`, `config/WellKnownProperties`, `config/WebSecurityConfig`, `config/ReloadableApiKeySupplier`, `user/InMemoryUserStore`
- "이 값을 어떻게 설정하는가 + 운영 시 주의"를 명시.
- **`InMemoryUserStore`는 특히 강조**: "데모용 인메모리 저장소 — 고객사는 자기 DB/영속 계층으로 교체하라"를 클래스 Javadoc 최상단에 둔다(샘플의 가장 흔한 오해 지점). 단일 인스턴스 전제·파일 미러링도 "데모 한정"임을 명시.
- 운영 주의 예: CorsProperties 와일드카드 금지 이유, RelayProperties.secret을 운영에서 반드시 교체(RelayKeyGuard가 강제), API key 주입 방식.

### 계층 3 — 인프라/유틸/부트스트랩 (참고용, 간결)
`RpAppApplication`, `web/SecretRedactor`, `web/SecretMaskingConverter`, `web/RedactingMessageJsonProvider`, `web/CompactMdcConverter`, `web/RequestLoggingFilter`, `common/filter/TraceIdFilter`, `common/exception/GlobalExceptionHandler`, `common/exception/BusinessException`, `common/exception/ErrorCode`, `common/response/ApiResponse`, `web/PageController`, `web/WellKnownController` (13개)
- **클래스 Javadoc 1~3줄**: "무엇을 하는 컴포넌트인지" + "고객사는 그대로 가져다 써도 됨 / 자기 정책으로 교체" 한 줄 가이드.
- WellKnownController는 네이티브 앱 패스키용 well-known 호스팅이라 계층 3이되 "고객사 앱 메타데이터로 교체" 가이드를 단다.
- `RpAppApplication`은 부트스트랩(@SpringBootApplication + @ConfigurationPropertiesScan + 기본 타임존 KST 고정). 클래스 Javadoc로 "앱 진입점 + KST 고정 이유" 간결 설명.

### 계층 4 — 데이터 구조 (최소, 11개)
`web/dto/*`(7개), `user/RpAppUser`, `common/response/ErrorDetail`, `common/response/FieldError`, `common/response/PageResponse`
- **한 줄 요약**. 요청/응답 DTO는 어느 엔드포인트의 것인지 표기.

## 3. 제거/변환할 내부 맥락 (코드 전반)

**제거:**
- 마이그레이션 흔적: "원본 Java 는 record 였다", "@JvmRecord 우회", "Kotlin data class 는…", "companion object…" 등.
- 내부 모듈 참조: "core 의 twin", "Mirror of com.crosscert.passkey.core…", ":core 비의존이라 중복", "drift 방지 — 두 파일 동기화", "core 와 동일하게 유지할 것".

**변환(의미 보존, 고객사 언어로):**
- 내부 spec/이슈 코드: "spec §5" / "P0-4" / "P2-a" / "W001 버그" 등 → 의미를 풀어 설명.
  - 예: "P0-4 무상태 계약" → "finish 요청이 begin 없이도 동작하도록 토큰에 사용자 정보를 봉인한다(서버에 pending 상태를 두지 않음)".
  - 예: "W001 버그를 막기 위함" → "begin 만 하고 finish 를 못 한 사용자가 같은 username 으로 재시도할 수 있게 한다".

**유지·강화 (절대 삭제 금지):**
- 보안·동작 근거: 상수시간 비교(HMAC), iss/aud 검증, issuerBase 누락 시 fail-fast, relay 4필드 null 거부, CORS 와일드카드 금지, 로그 비밀값 마스킹, putIfAbsent 원자적 점유. 이들은 고객사가 자기 RP에서 반드시 따라야 할 보안 패턴이므로 오히려 더 명확히 설명한다.

## 4. rp-app/README.md 구조

대상: 자사 RP를 구축하려는 고객사 개발자. `sdk-java/README.md`(SDK 사용법 9개 섹션)와 짝을 이루며, SDK 자체 사용법은 그쪽으로 링크해 중복을 피한다.

1. **개요** — rp-app이 무엇인가(passkey-app 뒤에 두는 RP 서버 샘플), 누가 보는가(자사 RP 구축 고객사 개발자), 무엇을 보여주는가(무상태 토큰 relay 기반 등록/인증 + ID Token 검증 + 네이티브 앱 well-known 호스팅).
2. **아키텍처** — `Browser ↔ rp-app ↔ passkey-app` 다이어그램. rp-app의 책임 경계: SDK 호출, 사용자↔credential 매핑, ID Token 검증, 정적 well-known 호스팅. 무엇을 passkey-app에 위임하는지.
3. **빠른 시작** — `./gradlew :rp-app:bootRun --args="--spring.profiles.active=local"` + 필수 설정(passkey base-url, API key, tenant-id, issuer-base). 로컬 데모 전제(passkey-app 8080).
4. **요청 흐름** — 등록 2-step·인증 2-step의 실제 엔드포인트와 시퀀스:
   - 등록: `POST /passkey/register/begin` → `POST /passkey/register/finish`
   - 인증: `POST /passkey/authenticate/begin` → `POST /passkey/authenticate/finish`
   - 무상태 relay 토큰(regRelayToken/authenticationToken)의 역할: 서버 세션 없이 begin↔finish를 잇고 userHandle 조작을 막음.
5. **고객사가 반드시 손봐야 할 곳** — 체크리스트:
   - `InMemoryUserStore` → 자사 DB/영속 계층으로 교체(가장 중요).
   - `rp.relay.secret` → 운영용 강한 키 주입(미설정 시 RelayKeyGuard가 기동 차단).
   - `rp.cors.allowed-origins` → 자사 웹 origin 정확 목록(와일드카드 금지).
   - `rp-app.well-known.*` → 자사 앱 패키지/지문/App ID.
   - `passkey.api-key`/`api-key-file` → 발급받은 API key 주입.
   - `passkey.tenant-id`/`issuer-base` → 자사 테넌트 값.
6. **설정 레퍼런스** — 프로퍼티 표(prefix·키·기본값·환경변수·의미): `passkey.*`, `rp.relay.*`, `rp.cors.*`, `rp-app.well-known.*`, `rp-app.user-store.*`. 실제 `application.yml`/`application-local.yml` 값과 1:1 대조.
7. **보안 노트** — 고객사가 자기 RP에서 따라야 할 패턴: ID Token iss/aud 검증, relay HMAC 바인딩, CORS 정확-origin 정책, 로그 비밀값 마스킹, CSRF 비활성+STATELESS 결정의 근거(무상태 클라이언트).
8. **SDK 연동** — `sdk-java/README.md`로 링크(PasskeyClient 설정·API 4종·ID Token 검증). rp-app의 `PasskeyClientConfiguration`이 레퍼런스 연동 예제임을 안내.

작성 시 elements-of-style 원칙(간결·능동) 적용. 모든 엔드포인트·설정·기본값은 실제 코드/yml과 대조해 정확히 쓴다.

## 5. 검증 전략

동작 변경 0(주석·문서만)이므로:
1. `./gradlew :rp-app:compileJava` — 주석 변경이 코드를 깨뜨리지 않음(특히 Javadoc 형식 오류 없음).
2. `./gradlew :rp-app:test` — 69 tests green 유지(회귀 0). 1 skip(@Disabled SmokeIT)은 기존과 동일.
3. 내부 맥락 잔존 grep 검사 → 0건:
   `grep -rnE "spec §|P0-[0-9]|P2-|W00[0-9]|core 의|:core|Mirror of|twin|원본 Java|@JvmRecord|drift|companion object|data class" rp-app/src/main/java`
4. README 정확성 대조: 엔드포인트 경로(`/passkey/...`), 프로퍼티 키·기본값을 실제 코드(WebAuthnController 매핑)·yml과 비교.
5. (선택) local 프로필 부팅 후 README의 빠른 시작 절차가 실제로 동작하는지 1회 확인.

## 6. 작업 순서 (구현 단계 개요)

격리 worktree에서(per-phase worktree, subagent cwd 격리 주의):
1. 계층 4(데이터 구조 11개: dto 7 + RpAppUser + ErrorDetail + FieldError + PageResponse) — 한 줄 요약, 빠른 win.
2. 계층 3(인프라/유틸/부트스트랩 13개) — 클래스 Javadoc 간결화 + 교체 가이드 한 줄.
3. 계층 2(설정/보안/저장소 8개) — 운영 주의 + InMemoryUserStore 교체 강조.
4. 계층 1(핵심 플로우 3개) — 상세 Javadoc + 보안 인라인.

총 35개 = 3 + 8 + 13 + 11.
5. `rp-app/README.md` 신규 작성(섹션 4).
6. 검증(섹션 5).
7. main으로 `--no-ff` 머지.

## 7. 리스크와 완화

| 리스크 | 완화 |
|---|---|
| 보안 근거 주석을 내부 맥락으로 오인해 삭제 | 섹션 3 "유지·강화" 목록을 명시적 가드로; 리뷰에서 보안 주석 보존 확인 |
| README 엔드포인트/기본값이 코드와 어긋남 | 섹션 5.4 코드/yml 대조 필수 |
| Javadoc `{@code}`/`{@link}` 형식 오류로 컴파일/문서 깨짐 | compileJava + (선택) javadoc 형식 점검 |
| 주석 과다로 샘플 가독성 저하 | 계층별 밀도 차등(계층 4는 한 줄) |
| 동작 변경 혼입 | 주석·문서만 — diff에서 코드 라인 변경 0 확인 |
