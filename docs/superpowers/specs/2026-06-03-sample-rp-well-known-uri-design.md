# Sample-RP Well-Known URI (Digital Asset Links / Apple App Site Association) 설계

날짜: 2026-06-03
대상 모듈: `sample-rp`
관련 문서: `docs/rp-server-api.md` §7.1, `docs/single-instance-deployment.md`

## 1. 배경 & 목적

네이티브 앱(Android/iOS)에서 패스키(WebAuthn)를 쓰려면 OS가 **"앱 ↔ 도메인"의
소유 관계**를 검증한다. 웹 브라우저는 주소창 도메인으로 `rpId`를 자동 검증하지만,
네이티브 앱은 주소창이 없으므로 OS가 `rpId` 도메인의 Well-Known URI를 직접 가져와
앱 서명·식별자를 대조한다. 이 "도메인 → 앱" 방향의 증명을 호스팅하는 것이 목적이다.

- **Android**: `https://<rpId>/.well-known/assetlinks.json` — 패키지명 + 서명 SHA-256 지문.
  Google Play services가 검증.
- **iOS/iPadOS 등**: `https://<rpId>/.well-known/apple-app-site-association` —
  `webcredentials.apps`에 `TeamID.BundleID`. Apple CDN이 가져가 캐싱·검증.

`docs/rp-server-api.md` §7.1에 "RP 서버에 호스팅해야 한다"는 **요구사항만 문서화**되어
있고 실제 호스팅 구현은 없다. 본 작업이 그 구현을 `sample-rp`에 추가한다.

## 2. 핵심 결정 (확정)

| 항목 | 결정 | 근거 |
|---|---|---|
| 호스팅 위치 | `sample-rp` (RP 서버, port 9090) | Well-Known은 `rpId`(=RP 도메인)에 묶임. passkey-app(8080)이 아님 |
| 방식 | **(B) 설정 주입** | sample-rp를 "고객사가 자기 앱 값만 채워 재사용하는 RP 레퍼런스 구현"으로 |
| 기본값 | 첨부 샘플 앱 값을 `application.yml` 기본값으로 | 환경변수 미설정 시 데모가 바로 동작 |
| 설정 유연성 | 리스트 지원 (지문 여러 개, iOS app 여러 개) | 드버그/릴리즈 서명, 멀티 번들 대응 |
| 검증 편의 | `/.well-known/**` permitAll + robots.txt + 운영 문서 | 주의사항(200·application/json·접근허용) 충족 |

**왜 (B)인가** — 패스키 서버(passkey-app)는 멀티테넌트 SaaS지만, Well-Known은 본질적으로
각 고객사 도메인(`rpId`)에 묶인다. 고객사는 보통 자기 도메인으로 자기 RP 서버를 운영하므로
(`single-instance-deployment.md` 토폴로지와 일치), sample-rp의 역할은 "복붙해 가는 레퍼런스
구현"이다. 앱 값(패키지명/지문/Team ID)을 설정으로 외부화하면 고객사는 코드 한 줄 안 고치고
환경변수만 채워 동작시킨다. 단일 인스턴스가 여러 고객 도메인을 직접 받는 (C) 동적 멀티테넌트는
범위 밖(필요 시 passkey-app 중앙 관리로 별도 설계).

## 3. 아키텍처 & 컴포넌트

```
브라우저/네이티브 앱
      │ GET https://<rpId>/.well-known/assetlinks.json
      │ GET https://<rpId>/.well-known/apple-app-site-association
      ▼
┌─────────────────────────── sample-rp (9090) ───────────────────────────┐
│  WellKnownController                                                     │
│   ├─ GET /.well-known/assetlinks.json            → 200 application/json  │
│   └─ GET /.well-known/apple-app-site-association → 200 application/json  │
│              │ reads                                                      │
│              ▼                                                            │
│  WellKnownProperties  (@ConfigurationProperties "sample-rp.well-known")  │
│   ├─ android: [ { packageName, sha256Fingerprints: [...] }, ... ]        │
│   └─ ios:     { appIds: [...] }                                          │
│              ▲ binds from                                                │
│  application.yml  (기본값 = 첨부 샘플 앱)                                  │
│                                                                          │
│  WebSecurityConfig:  /.well-known/**, /robots.txt permitAll 명시          │
│  static/robots.txt:  User-agent: * / Allow: /.well-known/                │
└──────────────────────────────────────────────────────────────────────┘
```

**컴포넌트 (각 단일 책임):**

1. **`WellKnownProperties`** — `@ConfigurationProperties(prefix = "sample-rp.well-known")`.
   설정값을 타입 세이프하게 바인딩만 한다. 외부 의존 없음.
   - `android`: `List<AndroidApp>`, 각 `AndroidApp = { packageName, sha256Fingerprints: List<String> }`
   - `ios`: `Ios = { appIds: List<String> }`

2. **`WellKnownController`** — 두 엔드포인트를 `produces = APPLICATION_JSON_VALUE`로 서빙.
   `WellKnownProperties`를 읽어 표준 wire format 객체를 조립해 반환. `JwksController`와 동일 패턴
   (envelope 없이 표준 포맷 직접 반환 — OS/CDN이 표준 포맷을 요구).

3. **`WebSecurityConfig`** (수정) — `requestMatchers`에 `/.well-known/**`, `/robots.txt` permitAll 명시.

4. **`static/robots.txt`** (신규) — `Allow: /.well-known/`.

## 4. 설정 구조 & 데이터 흐름

**application.yml (sample-rp) — 첨부 파일 값을 기본값으로:**

```yaml
sample-rp:
  well-known:
    android:
      - package-name: ${WK_ANDROID_PACKAGE:com.crosscert.sample.passkey}
        sha256-fingerprints:
          - ${WK_ANDROID_SHA256:63:0C:F8:85:C6:9A:80:A0:87:B0:A2:72:83:61:00:29:F8:36:65:FF:EF:8E:FE:71:71:74:13:ED:34:9D:7C:54}
    ios:
      app-ids:
        - ${WK_IOS_APPID:LTPC88ZFE8.com.crosscert.sample.passkey}
```

환경변수 미설정 → 첨부 샘플 값으로 바로 동작. 고객사는 환경변수만 자기 값으로 주입.

**데이터 흐름:**

```
GET /.well-known/assetlinks.json
  → WellKnownController.assetLinks()
  → props.android() 순회 → 표준 포맷 조립 → 반환:
    [ {
        "relation": [
          "delegate_permission/common.handle_all_urls",
          "delegate_permission/common.get_login_creds"
        ],
        "target": {
          "namespace": "android_app",
          "package_name": "com.crosscert.sample.passkey",
          "sha256_cert_fingerprints": [ "63:0C:..:54" ]
        }
    } ]
  → 200, Content-Type: application/json

GET /.well-known/apple-app-site-association
  → WellKnownController.appleAppSiteAssociation()
  → props.ios().appIds() 로 조립 → 반환:
    { "webcredentials": { "apps": [ "LTPC88ZFE8.com.crosscert.sample.passkey" ] } }
  → 200, Content-Type: application/json
```

**설계 결정:**

- **`relation`은 코드 상수로 고정.** 첨부 파일의 두 relation(`handle_all_urls` +
  `get_login_creds`)은 패스키 표준 조합이라 고객사가 바꿀 일이 거의 없다. 설정 표면을 줄이려
  컨트롤러 상수로 둔다. (필요해지면 그때 외부화)
- **출력은 첨부 파일과 동일한 구조.** 첨부 두 파일이 정답지이므로 조립 JSON이 그 구조(키 이름·
  relation 개수·namespace 등)와 정확히 일치해야 한다.

## 5. 엣지 케이스 & 에러 처리

1. **확장자 없는 iOS 파일의 Content-Type** (최대 함정) — `apple-app-site-association`은 확장자가
   없어 Spring이 Content-Type 추측 실패 → octet-stream 위험. `produces = APPLICATION_JSON_VALUE`로
   강제. 컨트롤러가 명시 조립·반환하므로 샐 여지 없음.
2. **트레일링 슬래시/리다이렉트** — 검증 에이전트는 3xx 거부. 컨트롤러 직접 매핑이라 정적 핸들러의
   트레일링 슬래시 리다이렉트에 안 걸림. 정확한 경로만 200.
3. **Spring Security 우회** — 현재 `anyRequest().permitAll()`로 통과하나, 의도 명시 위해
   `/.well-known/**` permitAll을 `requestMatchers`에 추가. CSRF는 GET이라 무관.
4. **설정 누락/빈 리스트** — `android` 비면 `[]` 반환(200 유지), `ios.app-ids` 비면
   `{ "webcredentials": { "apps": [] } }`. 부실 설정에도 200 + 유효 JSON 보장(크래시 금지).
5. **로깅** — `JwksController` 수준. `log.debug`로 "assetlinks served: androidApps=N" /
   "aasa served: iosApps=N" 정도. 지문·Team ID 본문 로깅 안 함.
6. **robots.txt 경로** — `static/robots.txt`를 Spring 정적 핸들러가 `/robots.txt`로 서빙.
   `WebSecurityConfig`에서 `/robots.txt`도 permitAll.

## 6. 테스트 전략

`WellKnownControllerTest` (기존 sample-rp 테스트 패턴 따름):

| # | 검증 항목 | 기대 |
|---|---|---|
| 1 | `GET /.well-known/assetlinks.json` | 200 + `Content-Type: application/json` |
| 2 | 응답 구조 | relation 2개, `namespace=android_app`, package_name·지문이 설정값과 일치 |
| 3 | `GET /.well-known/apple-app-site-association` | 200 + `Content-Type: application/json` (확장자 없어도) |
| 4 | 응답 구조 | `webcredentials.apps`가 설정 app-ids와 일치 |
| 5 | 지문 여러 개 | `sha256_cert_fingerprints` 배열에 전부 포함 (리스트 지원) |
| 6 | android 빈 리스트 | `[]` + 200 (크래시 없음) |
| 7 | Security 통과 | 인증 없이 두 경로 접근 가능 |

함정 ①(iOS Content-Type)과 리스트 지원이 회귀하지 않도록 명시적으로 못 박는 게 핵심.

## 7. 산출물

**신규:**
- `sample-rp/.../web/WellKnownController.java`
- `sample-rp/.../config/WellKnownProperties.java`
- `sample-rp/src/test/.../web/WellKnownControllerTest.java`
- `sample-rp/src/main/resources/static/robots.txt`

**수정:**
- `sample-rp/src/main/resources/application.yml` (well-known 설정 블록 + 기본값)
- `sample-rp/.../config/WebSecurityConfig.java` (`/.well-known/**`, `/robots.txt` permitAll 명시)

**문서:**
- `docs/rp-server-api.md` §7.1 — sample-rp가 요구사항을 구현했고 설정값만 바꾸면 된다는 내용 +
  운영 주의사항(도메인별 검증, HTTPS 필수, Apple CDN 캐싱, robots.txt) 추가.

**첨부 파일 처리:** 루트의 `assetlinks.json` / `apple-app-site-association`은 값의 출처(정답지)
역할을 마쳤고 설정 기본값으로 흡수됨. 커밋 시점에 삭제 또는 보존 결정.

## 8. 검증 (Verification)

- 단위 테스트 전체 통과.
- `./gradlew :sample-rp:bootRun` 후 `curl -i`로 두 엔드포인트의 **실제 Content-Type 헤더**까지
  눈으로 확인 (200 + application/json).

## 9. 범위 밖 (Non-goals)

- (C) 동적 멀티테넌트(단일 인스턴스가 여러 고객 도메인을 Host 기반으로 동시 수용) — passkey-app
  중앙 관리로 다룰 별도 주제.
- passkey-app(8080)에 well-known 호스팅 — `rpId`가 RP 도메인이므로 부적합.
- 실제 모바일 앱(Android/iOS) 클라이언트 구현 — 본 작업은 서버 측 호스팅만.
